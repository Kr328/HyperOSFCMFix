# HyperOS `MILLET_NO_RESTRICT_APP` 机制调查笔记

> 调查环境:Xiaomi pudding (25113PN0EC),HyperOS OS3.0,Android 16 (SDK 36),user build。
> (原记录写"HyperOS V816",与本机属性不符:实测 `ro.mi.os.version.name=OS3.0`、
> `ro.mi.os.version.incremental=OS3.0.319.0.WPCCNXM`,已按实际构建号修正。)
> ROM 全量提取于 `../hyperos`,反编译工件在其 `work/` 目录。
> 本文所有"已验证"结论均有真机实验或 ROM 反编译源码支撑;随版本更新行为可能变化,修订前请复核。
>
> 复核(同一台 pudding,构建 `OS3.0.319.0.WPCCNXM` / Android 16 SDK 36):已按当前 ROM 重新
> 反编译核对 ContentObserver 投递延迟判定链与 Greezer binder-proxy 行为,修正 §5.3 的常量标注、
> 新增 §5.4 完整链路,并把 §7 索引改到当前 jadx 工件;jadx 输出在 `../hyperos/work/framework/`。

---

## 1. 背景与问题

SimpleFCMfix 在国行 HyperOS 上修复 FCM 推送的核心手段:把 `com.google.android.gms` 注入
`Settings.System.MILLET_NO_RESTRICT_APP`(逗号分隔的包名列表),使系统冻结器不冻结 GMS。

本调查回答四个问题:

1. 这个键的完整读写链路是什么?写入何时会被系统覆盖?
2. 能否不走这个键,直接把 GMS 的"省电策略"(PowerKeeper userTable)改成 `noRestrict`?
3. 注入后多久生效?如何立即生效?
4. 如何**稳定监听**这个键的变化(相比基于 logcat 的监听)?

---

## 2. `MILLET_NO_RESTRICT_APP` 完整链路(已验证)

### 2.1 消费方:Greeze/Aurogon 冻结器(system_server)

- 实现类:`com.miui.server.greeze.AurogonImmobulusMode`(在 `system_ext/framework/miui-services.jar`)。
- `getNoRestrictApps()`:`Settings.System.getString()` 读值 → `split(",")` → `trim()` → 跳过空串
  → 存入 `mNoRestrictAppSet`(**存包名,不分 uid**)。
- **实时性**:对 `Settings.System.getUriFor(KEY)` 注册了 `ContentObserver`
  (`AurogonImmobulusMode$SettingsObserver.onChange` 的分支只做两件事:
  `mNoRestrictAppSet.clear(); getNoRestrictApps();`)。
  真机实测:纯 `adb shell settings put` 写入后 **1 秒内** logcat 出现
  `mNoRestrictAppSet=[...]` 更新(`logcat -s AurogonImmobulusMode`)。
- **重要边界**:排除集合只影响**未来的冻结决策**。onChange 分支没有任何主动解冻动作。
  已冻结的进程要等外部触发解冻——哪些触发真正有效见 2.4 的对照实验。

### 2.2 覆盖方:PowerKeeper(单向发布,这是注入被"清掉"的根源)

- `PowerKeeper` 的 `ActiveStateController.dealNoRestrictApp()`:
  读自有 provider(`content://com.miui.powerkeeper.configure/userTable`)中
  `bgControl='noRestrict'` 的行,把包名集合**整体覆写**到 `MILLET_NO_RESTRICT_APP`;
  userTable 无 noRestrict 行时覆写为空串。
- 触发时机:**userTable 发生变更时**(onContentChange):用户在 UI 改任意应用省电策略、
  云端配置同步、应用安装/删除(PowerKeeper 在 `onPackageAdded` 给每个新装应用插
  `bgControl=miuiAuto` 默认行)。**系统重启本身不主动触发**(无 boot 路径调用它)。
- 覆写动作的日志 tag 为 `ActiveStateController`(内容形如 `add noRestr:<pkg>`,冒号后无空格);
  用户经 UI/服务改省电策略产生的日志 tag 则是 `PowerSaveConfigureManager`——
  按 tag 监听日志只能覆盖后一类事件,包安装/云同步触发的覆写不会出现被监听的日志。
- 方向是单向的:PowerKeeper 从不回读该设置。因此注入不会在 PowerKeeper 的
  "省电策略" UI 里显示,反之 UI 里改成"无限制"的应用会进入该列表。

### 2.3 写权限

- `Settings.System` 写需要 `WRITE_SETTINGS`。普通 App 未授权写不了 → 这正是经 Shizuku
  (shell uid)写入的原因;**adb shell 直接可写**(实测幂等写回成功)。
- 注意值含空格时需要远端引号:
  ```bash
  adb shell "settings put system MILLET_NO_RESTRICT_APP '$cur, com.google.android.gms'"
  ```

### 2.4 解冻触发器(2026-09-17 真机对照实验)

让 GMS 冻结后逐一测试各触发方式,以 `dumpsys greezer` 历史的 `THAW ... reason` 为准:

| 触发方式 | 是否解冻 | 证据 |
|---|---|---|
| `am broadcast -a com.google.android.intent.action.GCM_RECONNECT -p com.google.android.gms`(plain) | **否** | 冻结状态下发送后连续 20s 保持 FROZEN;thawReason 统计从未出现 broadcast |
| 同上,加 `--foreground` | **否**(无及时性) | 17s 内仍 FROZEN;其后的解冻 reason 为 `Sync Binder4`(系统行为,非广播) |
| `content query --uri content://com.google.android.gms.chimera` | **是,亚秒级,3/3 复现** | `THAW uid = 10136 ... reason : provider caller : 1000`;查询真实到达 GMS 进程(返回空结果而非 AMS 拒绝) |
| `content query` 权限墙后的 provider(gservices/games) | 否 | AMS 在获取 provider 阶段拒绝,调用未到达 GMS 进程 |
| `content query` 不存在的 authority | 否 | resolveContentProvider 找不到,无调用发生 |

结论:**对冻结进程的 manifest receiver 广播走延迟投递,不触发解冻**;可靠的用户侧解冻手段是
**发起一次真正到达 GMS 进程的 provider 调用**——`content://com.google.android.gms.chimera`
导出且无权限要求,shell 可访问。冻结期间的自然解冻 reason 均来自 system_server 侧活动:
`Sync Binder4` / `Excute Service` / `provider` / `LAUNCH_MODE` / `screen on`。

另两项相关观察:
- **冻结进程的 TCP 套接字在 `/proc/net/tcp` 中仍显示 ESTABLISHED**(内核维持连接),
  因此不能用套接字状态判断冻结中进程的 FCM 连接(端口 5228-5230)是否实际可用。
- 灭屏会显著加速冻结(FZ reason: screen off);冻结时长从数秒到数分钟不等,
  期间 system_server 的偶发交互(Sync Binder/Excute Service/provider)会打断冻结。

### 2.5 写入该键的通知语义

- 值**变化**时,SettingsProvider 自身会对精确 key URI 发 `notifyChange`
  (`SettingsProvider$SettingsRegistry.notifyForSettingsChange` → `MyHandler`),
  观察者(如 Aurogon)因此实时重读——写入方无需手动补发通知。
- 值**未变化**时,`SettingsState$Setting.update()` 返回 false 短路,**不发通知**
  (幂等写入不产生观察者回调)。

---

## 3. 为什么只能用这个键(userTable 直写不可行,已验证)

GMS 是系统应用,UI 不提供"省电策略"选项(实测选择后 userTable 不变)。其余路径全部被封:

| 路径 | 结果 | 原因 |
|---|---|---|
| `content insert/update/query` PowerKeeper provider | SecurityException | AMS 在**获取 provider**时(`ContentProviderHelper.checkContentProviderPermission`)即校验 `miui.permission.powerkeeper.HIDDEN_MODE_PROVIDER`(signatureOrSystem,仅平台签名/uid 1000 包持有) |
| `content call --method userTableupdate` | 同上 | `Transport.call()` 虽不校验权限,但 `content` 命令走 `getContentProviderExternal`,获取时被拦 |
| 普通 App 代写(装个 APK 调 provider) | 同上 | 普通/External 两种 provider 获取路径都做同样校验 |
| MIUI 企业服务 `setApplicationSettings`(system_server 内部会写 userTable) | 不可用 | 服务未运行(`service check EnterpriseManager` not found),且 `getService()` 前置 `checkEnterprisePermission()` 企业证书校验 |
| PowerKeeper 导出广播 | 无入口 | `PowerKeeperReceiver` 只认 `BOOT_COMPLETED`/`ACTION_SHUTDOWN`;`CloudUpdateReceiver` 只认 `BOOT_COMPLETED`/`CONNECTIVITY_CHANGE`/`action_alarm`/`SECRET_CODE`(Manifest 实测),没有可用来写 noRestrict 列表的入口;配置仅来自 assets 与云端 |
| `adb root` / 直接改 SQLite | 不可用 | user build;`/data/data/com.miui.powerkeeper` shell 不可写 |

附:官方写入 userTable 的正常路径是绑定 `com.miui.powerkeeper.PowerKeeperBackgroundService`
调 `IPowerKeeper.setPowerSaveAppConfigure(Bundle{App, AppConfigure, UserId})`
(AppConfigure 取值 `no_restrict/miui_auto/restrict_bg/no_bg`),
但绑定需 `com.miui.powerkeeper.permission.BIND_SERVICE`(同为 signatureOrSystem)。

**结论:`MILLET_NO_RESTRICT_APP` 是无 root/shell 下唯一可写的"电池优化白名单"执法通道,
且由 Greeze/Aurogon 实时消费。**

---

## 4. Shizuku 进程内监听:不可行(实验+源码双重证实)

### 4.1 现象

用 javac+d8 构建测试 dex,在 `app_process` 中以 shell uid 运行(与 Shizuku 用户服务同类:
同 uid、同样无 AMS 进程记录),绕过 `ContentResolver` 框架层,反射取
`ContentObserver.getContentObserver()` 的 transport binder,直接调
`IContentService.registerContentObserver()`:

- binder 调用**成功返回、无异常**;
- 但系统日志:`ContentService: Ignoring content changes for content://settings/system/MILLET_NO_RESTRICT_APP from 2000: Failed to find PID 22273`;
- `dumpsys content` 观察者树中**无此条目**(回调永远不来)。

### 4.2 根因

`ContentService.registerContentObserver` → `ContentProviderHelper.checkContentProviderAccess`:

```
mPidsSelfLocked.get(callingPid) == null → return "Failed to find PID <pid>"
```

ContentService 收到该错误串后:targetSdk<26 静默忽略;≥26 抛 SecurityException——两条路都进不去。
**按 calling pid 无条件检查,与 uid/权限无关,没有豁免**(system_server 自身 pid 天然在表内,
所以 Aurogon 不受影响)。这就是"ContentObserver 需要登记在 AMS 的 pid"的确切出处。

---

## 5. 采用的架构:Shizuku 进程 observer + App 进程 ProxyProvider 转发注册

### 5.1 可行性实证

`dumpsys content` 观察者树中存在(2026-09-18 复核):

```
settings/system/MILLET_NO_RESTRICT_APP: pid=15742 uid=10334 user=0
```

uid 10334 = `com.github.kr328.simplefcmfix`(旧记录里的 10332 是重装前的段号),
pid 15742 则是它的独立进程 **`com.github.kr328.simplefcmfix:proxy`**——
`ProxyProvider` 在 Manifest 里声明了 `android:process=":proxy"`,
观察者条目的 pid 其实是**发起注册事务的 `:proxy` 进程**,不是主进程
(旧记录里的 pid=16286 同样只是当时的 `:proxy` 实例)。
**App 的 `:proxy` 进程注册同一 URI 完全可行**。

进一步推论(本方案的立足点):§4.2 的 pid 检查只看**注册事务的发起进程**;
observer transport binder 只是 Parcel 里的普通 binder 对象,本体可在任何进程。
因此让注册事务"借道"App 进程发起即可通过 pid 检查,观察者仍留在 Shizuku 进程。

### 5.2 设计(已实现)

```
Shizuku 用户服务进程(shell uid,无 ProcessRecord)     App 的 :proxy 进程(有 ProcessRecord)

[注册/注销] ContentResolver.registerContentObserver(uri, false, observer)
    └ Compat 反射替换 ContentResolver.sContentService 为动态代理
       1) call(".proxy", "wrapBinder", {target: 真实 ContentService binder})
            ───────────────────────────────────────────▶ ProxyProvider.call()
                                                        回复 {wrapper: 转发 binder}
       2) wrapper.registerContentObserver(原参数原样)
            ───────────────────────────────────────────▶ BinderProxyWrapper.onTransact()
                                                        └ target.transact() 原样转发
                                                          → ContentService 看到的
                                                            callingPid = :proxy pid ✓

[回调] ContentService 直接派发到 observer transport binder(指向 Shizuku 进程,不经 App 进程)
```

要点:
- **观察者留在 Shizuku 进程**:`ShizukuRemote` 持有 `ContentObserver` + 1s 防抖 → 重注入;
  注册仍用精确 URI + `notifyForDescendants=false`(与 Aurogon 相同)。
  回调不经过 App 进程;观察者条目记录的 pid 是发起注册的 `:proxy` 进程,该进程死亡**不会**注销条目
  (死亡监听挂在 observer transport binder 上,已在当前 ROM 源码中核实);
  主进程/`:proxy` 重启后由 Shizuku 侧重注册——见 §5.4 的真机旁证。
- **转发 binder 只做原始 Parcel 透传**(`BinderProxyWrapper.onTransact → target.transact`):
  不依赖 AIDL 方法表/transaction code,跨版本稳定(rikka `ShizukuBinderWrapper` 同款模式);
  入站 Parcel 的 interface token 未被消费,直接转发合法;远端异常经 reply 原样带回。
- **hideapi 的空 `IContentService.aidl` 仅为编译期类型**:运行期经父类加载器解析到
  framework 同名类,动态代理按方法名拦截、按 framework transaction code 编组,
  与原始转发的 wrapper 自动兼容,无需镜像完整方法表。
- **失败回退**:provider 调用异常(如 App 进程拉起超时)时 Compat 退回直连注册
  → 被 HyperOS 静默忽略 → 只剩 10 分钟 watchdog 兜底。
- **Provider 安全**:exported=true(调用方是 shell/root uid,与 App uid 不同),
  但声明 `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`(signature)
  防止三方 App 把它当作以本应用身份发起 binder 调用的跳板;
  `ActivityManager.checkComponentPermission` 对 root/shell uid 无条件放行,Shizuku 侧不受影响。
- debug 构建 `applicationIdSuffix=".debug"` 下,`${applicationId}` 占位符与
  `BuildConfig.APPLICATION_ID + ".proxy"` 天然对齐,两种构建类型各自成立。
- 曾考虑的替代方案——App 进程直接持有 observer、onChange 经 binder 调 Shizuku 重注入:
  需要 App 侧常驻注册与生命周期管理,进程死亡即丢监听,弃用。

实现位置:
- `Compat.createContentServiceDelegate` / `applyContentServiceCompatForShizuku`:拦截与借道;
- `ProxyProvider`(authority `${applicationId}.proxy`,method `wrapBinder`,
  extras key `target`,reply key `wrapper`):包装协议 + 原始转发;
- `ShizukuRemote`:构造时安装 compat,start()/stop() 的注册/注销均走上述路径。

### 5.3 投递延迟规则(量化,来自 `ContentService$ObserverCollector.dispatch()`)

- 判定条件:观察者条目里**注册时固化的 uid** 的当前进程状态
  `getUidProcessState(entry.uid) <= 6` → 立即投递;`> 6` → `postDelayed(task, 10_000)`:
  **固定 10 秒延迟,不丢弃、不累计**。阈值常量是
  `PROCESS_STATE_IMPORTANT_FOREGROUND = 6`(**不是** `PROCESS_STATE_SERVICE`,后者为 10;
  旧版本此处标注有误,已按当前 ROM 修正)。App 处于 cached 状态(或 uid 无任何进程)即如此。
  对本场景足够(冻结评估远慢于 10s)。
- 判定是**每次 notifyChange 现查**当前进程状态,且**只看 uid、与 pid 无关**
  (pid 仅用于 dump / 观察者泄漏计数 / Greezer binder-proxy 归属)。
  因此代码里没有"App 进程是否死亡"这个专门分支:进程全部死亡 → AMS 移除该 uid 的
  `UidRecord` → 查询返回 `PROCESS_STATE_NONEXISTENT = 20` → 必落 10 秒档。完整链路见 §5.4。
- 新架构下观察条目记录的 uid 为 **App uid**、pid 为发起注册的 **`:proxy` 进程**(注册经其转发);
  延迟按 App uid 的进程状态计算(与 pid 无关);回调最终派发到 Shizuku 进程的 transport binder,
  上述结论原样适用。`:proxy`/主进程死亡只引入 10s 档延迟,注册本身不受影响
  ——例外见 §5.4 末(App uid 曾处于冻结态时,该 observer binder 会被 Greezer 代理截流)。
- 要**即时投递 + 进程保活**:同 uid 存在 `procState <= 6` 的进程
  (TOP / BOUND_TOP / FOREGROUND_SERVICE / BOUND_FOREGROUND_SERVICE / IMPORTANT_FOREGROUND,
  例如前台服务)→ 立即投递;
  是否值得常驻通知自行权衡(观察注册在 Shizuku 侧 daemon 服务,无需 App 侧重注册)。
- 通知方带 `flags & 0x8000`(`NOTIFY_NO_DELAY = 32768`)可绕过延迟,但该 flag 由通知方
  (SettingsProvider)决定,客户端无法注入;当前 ROM 实测 SettingsProvider 走的是
  `notifyChange(uri, null, true, user)` → `flags = NOTIFY_SYNC_TO_NETWORK(1)`,不含 no-delay。

### 5.4 "App 进程死亡后是否延迟"的完整判定链(当前 ROM 源码核实)

1. **注册**:`ContentService.registerContentObserver()` 取 `Binder.getCallingUid()/getCallingPid()`
   (`ContentService.java` L225-226),存入 `ObserverEntry`(L1253-1312;字段 `uid`/`pid` L1257-1258);
   收集投递对象时只把 `entry.uid` 交给 `ObserverCollector`(L1435/L1438),
   `ObserverCollector.Key` 里没有 pid(L386-412)。
2. **死亡监听**:挂在 `observer.asBinder()`(observer transport binder,本体在 Shizuku 进程)上——
   `sObserverDeathDispatcher.linkToDeath(this.observer, this)`(L1269;
   `BinderDeathDispatcher.java` L47-65),其 `binderDied()` 只做 `removeObserverLocked`
   (L1292-1297)。因此 **App 进程死亡不会注销观察者**;只有 Shizuku 进程死亡或显式
   `unregisterContentObserver` 才会移除条目。
3. **延迟判定**`ContentService$ObserverCollector.dispatch()`(L424-444):
   `boolean noDelay = (key.flags & 32768) != 0;`
   `int procState = ActivityManagerInternal.getUidProcessState(key.uid);`(L437)
   `if (procState <= 6 || noDelay) task.run(); else postDelayed(task, 10_000);`(L438-441)。
4. **状态来源**:`ActivityManagerInternal.getUidProcessState` → `AMS.getUidState`
   (`ActivityManagerService.java` L14649-14651、L5247-5259)→ `ProcessList.getUidProcStateLOSP`
   (`ProcessList.java` L5371-5379):
   `UidRecord uidRec = mActiveUids.get(uid); return uidRec == null ? 20 : uidRec.getCurProcState();`
5. **死亡即 20**:uid 最后一个进程死亡时 `ProcessList.removeProcessNameLocked()` 在
   `getNumOfProcs() == 0` 分支执行 `mActiveUids.remove(uid)` 并
   `noteUidProcessState(uid, 20)`(L3485-3494);uid 仍活跃但 idle 则 `UidRecord.reset()` 置 19
   (`UidRecord.java` L298-307)。19/20 都 `> 6` → 10 秒档。
6. **常量**(`framework.jar` → `android.app.ActivityManager`):
   `PROCESS_STATE_IMPORTANT_FOREGROUND = 6`、`PROCESS_STATE_SERVICE = 10`、
   `PROCESS_STATE_CACHED_EMPTY = 19`、`PROCESS_STATE_NONEXISTENT = 20`。

推论(可直接指导实现):延迟档次只取决于**该 App uid 有没有 `procState <= 6` 的活进程**;
App 死后通知仍会送达(回调 binder 在 Shizuku),但统一 10 s 延迟;要即时投递,就必须让**同一个 uid**
存在前台/前台服务/TOP 级进程。

**HyperOS 附加层(冻结时生效,非死亡时)**:`ContentService.addObserverLocked()` 还会调用
`ContentServiceStub.getInstance().addBinderProxy(observer.asBinder(), uid, pid)`
(`ContentService.java` L1377-1378),把该 observer binder 注册进 Greezer
(`ContentServiceStubImpl.java` L136-142 → `GreezeBinderProxyManager` L90-108,按 desc 生成
`IContentObserverProxy`)。App uid 被冻结成功后 `proxyBinderAction(uid)`
(`GreezeManagerService.java` L2008/L2264)打开代理:oneway 的 `onChangeEtc` 会被截住并按
`code + media + path + userId` 去重缓存(`GreezeBinderProxyImpl.java` L36-78、L95-127;
`IContentObserverProxy.java` L86-92),解冻时 `unProxyBinderAction` 重放(L80-85、L110-127)。
注意代理登记的是 **App uid / 发起注册的 `:proxy` pid**、而 binder 本体属于 Shizuku
——所以"已判定立即"的回调在 App 冻结期间仍可能被缓存到解冻(该层由冻结/解冻驱动,不是死亡驱动)。

**⚠️ 已核实:App 死在冻结态会让 Shizuku observer 静默挨饿。** `mStarted` 只在
`GreezeBinderProxyManager.stopProxy(uid)` 里被清掉(该文件 L231-242;`setProxyState(false)` 另见
L84、L124),而 App 死亡的所有路径都不调用它:
- `IUidObserver.onUidGone`(`GreezeManagerService.java` L429-436)只发 `MSG_UID_GONE`;
- `case 13` 只做 `info.setFrozen(false,"uidGone")` + `info.onUidGone()`(同上 L4571-4604);
- native `reportSignal` 的死亡分支只 `mFrozenPids.remove(pid)` 并记 `Died uid = ...`
  (同上 L1099、L1115)。

而 `mFrozenPids` 条目一旦被移除,`thawUid()` 会因 `toThaw.size()==0` 提前返回
(同上 L2678-2680),`thawUidAsync()` 又要求 `isUidFrozen(uid)`——于是 `stopProxy(uid)`
再无人调用。后果:该 observer binder 上 oneway 的 `onChangeEtc` 会被
`GreezeBinderProxyImpl.transaction` 持续截住、按 path 去重缓存(`BinderProxy.transact` →
`mExt.transact` 返回 true 即跳过真实 transact),Shizuku observer 收不到通知。
能恢复的只有三条路径:
1. 同 uid 再次冻结后再解冻(`unProxyBinderAction` → `stopProxy` → `restoreTransaction` 重放,
   见 `GreezeManagerService.java` L2747、L2891、L3437);
2. observer 重新注册(`ContentService.removeObserverLocked` → `removeBinderProxy` →
   `removeFromProxyMap` 先 `setProxyState(false)` 冲刷队列再移除,`GreezeBinderProxyManager.java` L110-131);
3. `cmd greezer 30 enable 0` / 云端关闭 binder proxy(`unProxyAll`,同上 L75-88)。

真机旁证(uid 10334 = `com.github.kr328.simplefcmfix`;观察条目的 pid 是 `:proxy` 进程):
- 曾在 `dumpsys content` 看到 `settings/system/MILLET_NO_RESTRICT_APP: pid=15058 uid=10334`,
  而同一时刻 `ps` 里该 uid 只剩主进程 26585、15058 已不存在
  ——**注册确实挺过了 `:proxy` 进程死亡**(注册方不是主进程,旧记录的 uid 10332 是重装前段号);
- 随后观察树里的 pid 变成 15742(新的 `:proxy` 实例),说明 Shizuku 侧发生过重新注册
  ——这正是上面第 2 条恢复路径,会在实践中把残留的 started 代理冲刷掉;
- `dumpsys greezer` 的 `Frozen processes` 曾列出 26585,历史有
  `FZ uid = 10334 pid = [26585] reason : tobg`(其后无 THAW)与两次 `Died uid = 10334`,
  即确实出现过 "proxy started 而该 uid 进程已死" 的窗口:窗口内对本键的写入
  不会以 observer 事件到达 Shizuku。对 SimpleFCMfix 的实际影响:事件驱动重注入会失效一段时间,
  由 watchdog(`ShizukuRemote.WATCHDOG_PERIOD`,10 分钟)或下一次重注册兜底;
  把 `com.github.kr328.simplefcmfix` 自身也加入白名单(或让其常驻前台、不被冻结)可规避该 uid。

---

## 6. 诊断命令速查

```bash
# 读/写白名单(shell 可写;值含空格要远端引号)
adb shell settings get system MILLET_NO_RESTRICT_APP
adb shell "settings put system MILLET_NO_RESTRICT_APP '<旧值>, com.google.android.gms'"

# PowerKeeper userTable 实时内容(行格式: _id|userId|pkgName|lastConfigured|bgControl|bgDelayMin)
adb shell dumpsys activity service com.miui.powerkeeper/.PowerKeeperBackgroundService | grep "com.google.android.gms|"

# 冻结器状态(冻结名单/解冻原因/每 uid 冻结统计)
adb shell dumpsys greezer

# ContentService 观察者树(确认 observer 注册成败:Shizuku 直连注册不会出现;
# 经 ProxyProvider 转发注册后应以 App 的 pid/uid 出现)
adb shell dumpsys content | grep -A3 "Observer tree"

# Aurogon 重读该键的日志(写入后 1 秒内应出现 mNoRestrictAppSet=[...])
adb shell "logcat -s AurogonImmobulusMode"

# 注册被静默忽略的证据(把 <pid> 换成测试进程 pid)
adb shell "logcat -d | grep 'Failed to find PID'"

# provider poke:解冻冻结中的 GMS(亚秒级,reason=provider;对冻结进程发广播无效)
adb shell "content query --uri content://com.google.android.gms.chimera"
```

---

## 7. 关键源码索引(本 ROM 反编译,便于复核)

| 内容 | 位置 |
|---|---|
| Aurogon 读键/存 mNoRestrictAppSet | `work/framework/miui-all/sources/com/miui/server/greeze/AurogonImmobulusMode.java` `getNoRestrictApps()`(L2059-2075:`Settings.System.getString` → `split(",")` → `trim()` → 非空加入 `HashSet<String>`,存包名) |
| Aurogon 对该键的 ContentObserver | 同上 `SettingsObserver`(L1921 起);注册在 L277 `registerContentObserver(Settings.System.getUriFor(KEY_NO_RESTRICT_APP), false, observer, -1)`;`onChange` 分支 L1990-1992 `clear()+getNoRestrictApps()` |
| PowerKeeper 覆写该键 | `work/framework/pk-jadx/sources/com/miui/powerkeeper/controller/ActiveStateController.java` `dealNoRestrictApp()`(L536-553),由内部类 `.6` 的 `onContentChange` Runnable 末尾调用(L576-620,L619);数据源 `UserConfigureHelper.getNoRestrictApps()`(`userId=0 AND bgControl=noRestrict`) |
| 新装应用插默认行(bgControl=miuiAuto) | `work/framework/pk-jadx/sources/com/miui/powerkeeper/provider/PowerKeeperConfigureManager.java` 内部类 `.5`(jadx 标注 `from class: ...PowerKeeperConfigureManager.5`)`onPackageAdded`(L142 起),L175/L177 写入 `BG_CONTROL="miuiAuto"` |
| userTable 列定义与取值映射 | `.../provider/UserConfigure.java`(`BG_CONTROL_NO_RESTRICT="noRestrict"`、`MIUI_AUTO="miuiAuto"`、`RESTRICT_BG="restrictBg"`、`NO_BG="noBg"`、`Columns.METHOD_UPDATE="userTableupdate"`);`.../provider/PowerSaveConfigureManager.java`(`miui_auto/no_restrict/restrict_bg/no_bg`,L18-21) |
| provider 权限(获取时拦截) | 权限串在 PowerKeeper Manifest:`PowerKeeperConfigureProvider` + `android:permission="miui.permission.powerkeeper.HIDDEN_MODE_PROVIDER"`(protectionLevel signatureOrSystem);拦截点 `work/framework/jadx-all/sources/com/android/server/am/ContentProviderHelper.java` `checkContentProviderPermission`(L1214 起,调用点 L306-307/L798),实测 `content query` 报同名 Permission Denial |
| 同值写入不发通知 | `work/framework/settings-jadx/sources/com/android/providers/settings/SettingsState.java` `insertSettingLocked`(L401 起)在 L443 `if (!setting.update(...)) return false;`;`Setting.update` L1218/L1222;真机同值 `settings put` 后 Aurogon 无日志、改值 1s 内有日志 |
| **ContentObserver 按 pid 拦截** | `work/framework/jadx-all/sources/com/android/server/am/ContentProviderHelper.java` `checkContentProviderAccess`(L765-799):`resolveContentProvider` 失败→`"Failed to find provider …"`;随后 `mPidsSelfLocked.get(callingPid)==null` → `"Failed to find PID "+callingPid`(L792-795) |
| **回调 10s 延迟规则** | `work/framework/ContentService.java` `ObserverCollector.dispatch()`(L424-444):`procState = getUidProcessState(key.uid)`;`procState <= PROCESS_STATE_IMPORTANT_FOREGROUND(6) \|\| NOTIFY_NO_DELAY` → 立即,否则 `postDelayed(task, 10000)` |
| 死 uid 的进程状态(→ 20) | `work/framework/src/ProcessList.java` `getUidProcStateLOSP()`(L5371-5379,`mActiveUids.get(uid)==null → 20`)与 `removeProcessNameLocked()`(L3485-3494,最后进程死亡时 `mActiveUids.remove`+`noteUidProcessState(uid,20)`);`work/framework/src/UidRecord.java` `reset()` 置 19(L298-307) |
| 观察者死亡监听(挂 observer transport binder) | `work/framework/ContentService.java` `ObserverNode.ObserverEntry` 构造器 L1269 `sObserverDeathDispatcher.linkToDeath(observer, this)`;`binderDied()` L1292-1297 → `removeObserverLocked`;分发本体 `work/framework/src/BinderDeathDispatcher.java` L47-65 |
| HyperOS:observer → Greezer binder proxy | `work/framework/src/ContentServiceStubImpl.java` L136-142 `addBinderProxy`(uid/pid = 注册调用方,即 App uid / `:proxy` pid)→ `work/framework/miui-all/sources/com/miui/server/greeze/GreezeBinderProxyManager.java`(L90-108、L218-242)与 `.../greeze/binderproxy/IContentObserverProxy.java`;冻结时缓存 oneway `onChangeEtc`(`GreezeBinderProxyImpl.java` L36-78、L95-127),解冻重放 |
| 请求方不带 no-delay | `work/framework/settings-jadx/sources/com/android/providers/settings/SettingsProvider.java` `SettingsRegistry$MyHandler` L3038 `notifyChange(uri, null, true, user)` → `work/framework/src/ContentResolver.java` L1602-1604 映射为 `flags = NOTIFY_SYNC_TO_NETWORK(1)` |

> 复核说明:上表已从旧的 smali 提取路径(`work/miui-services/...`、`work/PowerKeeper/...`、
> `work/SettingsProvider/...`,当前工作区已不存在)**改为当前 jadx Java 工件**,
> 行号按本机反编译产物核对(旧文里的 `checkContentProviderAccess` "~13162 行起、mPidsSelfLocked
> ~1378-1386" 两个行号互相矛盾,已删除)。
> 工件位置:`../hyperos/work/framework/`——`ContentService.java`、`src/*.java`(services.jar 单类)、
> `jadx-all/sources/`(services.jar 全量)、`miui-all/sources/`(miui-services.jar)、
> `pk-jadx/sources/`(PowerKeeper.apk)、`settings-jadx/sources/`(SettingsProvider.apk)。
> 另有真机对照:`PowerKeeper.apk` 的 Manifest 用 `aapt2 dump xmltree` 核对,
> `dumpsys`/`logcat` 结论见 §2、§3、§5.4。

---

## 8. 已验证事实与推断的边界

**已验证(真机实验)**:
- 纯 `settings put` 后 1 秒内 Aurogon 重读(临时标记包实测后已还原);
- shell 可直接写该键;写后被 Aurogon 实时消费;
- Shizuku 类进程(app_process/shell uid)注册 ContentObserver 被静默忽略("Failed to find PID");
- App 的 `:proxy` 进程(uid 10332 → 现 10334)成功注册同一 URI;`dumpsys content` 观察树可见该条目;
- GMS 在白名单期间从未出现在 greezer 冻结记录中;列表外应用被反复冻结;
- **解冻触发器对照实验(2026-09-17)**:移出白名单后 GMS 反复冻结(灭屏后
  `FZ uid = 10136 reason: screen off`,冻结时长从 2s 到 2min+ 不等);冻结中
  plain/`--foreground` GCM_RECONNECT 广播**不解冻**(20s/17s 全程 FROZEN,
  thawReason 统计无 broadcast);`content query content://com.google.android.gms.chimera`
  **亚秒级解冻**(3/3 复现,reason=provider);权限墙后/不存在的 provider 不解冻。

**代码推导(未逐一实验但源码明确)**:
- 覆写触发条件仅为 userTable 变更;重启不主动触发;
- 排除集合不影响已冻结进程,需等外部触发解冻(~~广播投递可触发解冻~~ 已被实验推翻,
  见 2.4;冻结进程的广播走延迟投递);
- 10s 延迟规则(固定、非累计、非丢弃):阈值为 `PROCESS_STATE_IMPORTANT_FOREGROUND = 6`,
  死 uid 的进程状态为 `PROCESS_STATE_NONEXISTENT = 20`(见 §5.3/§5.4);
- 观察者死亡监听挂在 observer transport binder 上,故 App 进程死亡不影响**注册**;
  但投递同时受 App uid 的冻结状态约束(见 §5.4 末);
- HyperOS 附加的 Greezer binder-proxy 层:App 冻结期间 oneway `onChangeEtc` 被缓存去重、解冻重放;
  **若 App 死在冻结态,代理无人 `stopProxy`,observer 被持续截流**,只有同 uid 再次解冻、
  observer 重注册或关闭 binder proxy 才能恢复(真机 uid 10334 已出现该窗口,见 §5.4)。

**实现状态(v1.5 工作区,2026-09-18 真机复核通过)**:
- §5.2 的 ProxyProvider 转发方案已落地(`Compat` 动态代理 + `ProxyProvider` 原始 Parcel 转发);
  真机 `dumpsys content` 出现 `settings/system/MILLET_NO_RESTRICT_APP: pid=<:proxy pid> uid=10334`
  条目;`logcat -d -s ContentService | grep 'Failed to find PID'` 为空。

**随版本可能变化的点**(升级 HyperOS 后建议复核):
- `MILLET_NO_RESTRICT_APP` 键名与解析格式;
- ContentService 的 pid 检查、`BACKGROUND_OBSERVER_DELAY = 10000` 与延迟阈值常量
  (`PROCESS_STATE_IMPORTANT_FOREGROUND = 6`)及其编号;
- `getUidProcessState` 对无进程 uid 的返回值(`PROCESS_STATE_NONEXISTENT = 20`);
- greeze 解冻触发条件集合与 Greezer binder-proxy 行为。
