# HyperOS `MILLET_NO_RESTRICT_APP` 机制调查笔记

> 调查环境:Xiaomi pudding (25113PN0EC),HyperOS V816,Android 16 (SDK 36),user build。
> ROM 全量提取于 `../hyperos`,反编译工件在其 `work/` 目录。
> 本文所有"已验证"结论均有真机实验或 ROM 反编译源码支撑;随版本更新行为可能变化,修订前请复核。

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
- 覆写动作的日志 tag 为 `ActiveStateController`(内容形如 `add noRestr: <pkg>`);
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
| PowerKeeper 导出广播 | 无入口 | `PowerKeeperReceiver`/`CloudUpdateReceiver` 只认 BOOT/SHUTDOWN/SECRET_CODE 等固定 action,配置仅来自 assets 与云端 |
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

`dumpsys content` 观察者树中存在:

```
settings/system/MILLET_NO_RESTRICT_APP: pid=16286 uid=10332 user=0
```

uid 10332 = `com.github.kr328.simplefcmfix` 自身(设备上安装的测试版)——
**App 进程注册同一 URI 完全可行**。

进一步推论(本方案的立足点):§4.2 的 pid 检查只看**注册事务的发起进程**;
observer transport binder 只是 Parcel 里的普通 binder 对象,本体可在任何进程。
因此让注册事务"借道"App 进程发起即可通过 pid 检查,观察者仍留在 Shizuku 进程。

### 5.2 设计(已实现)

```
Shizuku 用户服务进程(shell uid,无 ProcessRecord)          App 进程(有 ProcessRecord)

[注册/注销] ContentResolver.registerContentObserver(uri, false, observer)
    └ Compat 反射替换 ContentResolver.sContentService 为动态代理
       1) call(".proxy", "wrapBinder", {target: 真实 ContentService binder})
            ───────────────────────────────────────────▶ ProxyProvider.call()
                                                        回复 {wrapper: 转发 binder}
       2) wrapper.registerContentObserver(原参数原样)
            ───────────────────────────────────────────▶ BinderProxyWrapper.onTransact()
                                                        └ target.transact() 原样转发
                                                          → ContentService 看到的
                                                            callingPid = App pid ✓

[回调] ContentService 直接派发到 observer transport binder(指向 Shizuku 进程,不经 App 进程)
```

要点:
- **观察者留在 Shizuku 进程**:`ShizukuRemote` 持有 `ContentObserver` + 1s 防抖 → 重注入;
  注册仍用精确 URI + `notifyForDescendants=false`(与 Aurogon 相同)。
  回调不经过 App 进程,App 进程死亡不影响已注册观察者继续收通知
  (系统侧死亡监听挂在 observer binder 上,代码推导)。
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

- 观察 uid 的 `getUidProcessState() > PROCESS_STATE_SERVICE(6)` → 回调被
  `postDelayed(task, 10_000)`:**固定 10 秒延迟,不丢弃、不累计**。
  App 处于 cached 状态即如此。对本场景足够(冻结评估远慢于 10s)。
- 新架构下观察条目记录的 uid/pid 为 **App 进程**(注册经其转发),延迟按 App 进程状态计算;
  回调最终派发到 Shizuku 进程的 transport binder,上述结论原样适用。
  App 进程死亡只引入 10s 档延迟,注册本身不受影响。
- 要**即时投递 + 进程保活**:前台服务(procState ≤ FOREGROUND_SERVICE)→ 立即投递;
  是否值得常驻通知自行权衡(观察注册在 Shizuku 侧 daemon 服务,无需 App 侧重注册)。
- 通知方带 `flags & 0x8000`(no-delay)可绕过延迟,但该 flag 由通知方
  (SettingsProvider)决定,客户端无法注入。

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
| Aurogon 读键/存 mNoRestrictAppSet | `work/miui-services/miui-services-dex/.../greeze/AurogonImmobulusMode.smali` `getNoRestrictApps()`(~1795 行起) |
| Aurogon 对该键的 ContentObserver | 同上 `AurogonImmobulusMode$SettingsObserver.onChange` 的 `KEY_NO_RESTRICT_APP` 分支;注册在 `init()`(~8795-8806) |
| PowerKeeper 覆写该键 | `work/PowerKeeper/smali/.../controller/ActiveStateController.smali` `dealNoRestrictApp()`(~516-746),由 `$6`(`onContentChange` Runnable)调用 |
| 新装应用插默认行(bgControl=miuiAuto) | `work/PowerKeeper/smali/.../provider/PowerKeeperConfigureManager$5.smali` `onPackageAdded` |
| userTable 列定义与取值映射 | `.../provider/UserConfigure$Columns.smali`;`.../provider/PowerSaveConfigureManager.smali`(noRestrict/miuiAuto/restrictBg/noBg ↔ no_restrict/miui_auto/restrict_bg/no_bg) |
| provider 权限(获取时拦截) | `services.jar` 反编译 `com/android/server/am/ContentProviderHelper.smali` `checkContentProviderPermission`(~497 行起) |
| 同值写入不发通知 | `work/SettingsProvider/smali/.../SettingsState.smali` `insertSettingLocked` 内 `Setting.update()` 短路(~5068-5074) |
| **ContentObserver 按 pid 拦截** | `ContentProviderHelper.smali` `checkContentProviderAccess`(~13162 行起;`mPidsSelfLocked` 检查 ~1378-1386) |
| 回调 10s 延迟规则 | `services.jar` 反编译 `com/android/server/content/ContentService$ObserverCollector.smali` `dispatch()`(procState>6 → `postDelayed(task, 10000)`) |

---

## 8. 已验证事实与推断的边界

**已验证(真机实验)**:
- 纯 `settings put` 后 1 秒内 Aurogon 重读(临时标记包实测后已还原);
- shell 可直接写该键;写后被 Aurogon 实时消费;
- Shizuku 类进程(app_process/shell uid)注册 ContentObserver 被静默忽略("Failed to find PID");
- App 进程(uid 10332,本应用官方安装版)成功注册同一 URI;
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
- 10s 延迟规则(固定、非累计、非丢弃)。

**实现状态(v1.5 工作区,待真机复核)**:
- §5.2 的 ProxyProvider 转发方案已落地(`Compat` 动态代理 + `ProxyProvider` 原始 Parcel 转发);
  预期 `dumpsys content` 观察树出现 App pid/uid 条目、logcat 不再出现 "Failed to find PID"。

**随版本可能变化的点**(升级 HyperOS 后建议复核):
- `MILLET_NO_RESTRICT_APP` 键名与解析格式;
- ContentService 的 pid 检查与 10s 延迟数值;
- greeze 解冻触发条件集合。
