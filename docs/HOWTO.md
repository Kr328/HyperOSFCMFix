# 国行 HyperOS 的 FCM 修复：当前实现与机制

本文说明 HyperOSFCMfix **当前代码**如何借助 Shizuku 修复 FCM，以及修复仍受哪些系统策略限制。机制证据主要来自 Xiaomi pudding（25113PN0EC）、HyperOS OS3.0.319.0.WPCCNXM、Android 16（SDK 36）、国行 user 构建的 ROM 反编译与真机实验。文中的「源码」指该版本 ROM 的反编译结果，「真机」指该设备上的观察；其他 HyperOS 版本需要重新验证。本文只描述代码已经执行的动作，研究建议另列于末尾。

## 1. FCM 为什么会断

FCM 有两段独立的路径：

```text
Google 服务器 ── MCS 长连接/心跳 ──▶ GMS ── c2dm 广播等 ──▶ 目标 App
                                    上游                     下游
```

- **上游：GMS 被冻结。** 国行 HyperOS 的 Greezer 可以冻结 `com.google.android.gms` 所在 uid；冻结后进程不能处理 socket 或心跳。TCP 显示 `ESTABLISHED` 只说明内核还保留连接，不能证明 GMS 正在收消息。AOSP 的 Doze 电池优化白名单也不能阻止这层冻结。【源码、真机】
- **下游：目标 App 被冻结或不能自启动。** GMS 收到消息后，常用 `com.google.android.c2dm.intent.RECEIVE` 广播交给目标 App。国行策略不会默认放行已冻结 App 的这个广播；冷进程还可能被 MIUI 的自启动检查拒绝。被 `force-stop` 后处于 `stopped=true` 的包也不能靠广播重新启动。【源码、真机】

因此只让 GMS 保持运行，不能保证所有目标 App 都收到通知。当前 App 分别处理 GMS 冻结、目标 App 的 c2dm 广播门，以及部分应用的自启动门。

## 2. 当前 App 的修复链路

用户在界面授权 Shizuku 并开启服务后，`ShizukuHelper` 绑定 daemon UserService。运行在 Shizuku 用户服务进程中的 [`ShizukuRemote.start()`](../app/src/main/java/com/github/kr328/simplefcmfix/ShizukuRemote.java) 先扫描 FCM 接收应用并调用 [`Applier.apply()`](../app/src/main/java/com/github/kr328/simplefcmfix/Applier.java)，成功后启动 [`Monitor`](../app/src/main/java/com/github/kr328/simplefcmfix/Monitor.java)。Shizuku 提供执行 Settings.Global 写入和 app-op 修改所需的 shell/root 身份。

| 动作 | 当前实现 | 解决的问题与边界 |
| --- | --- | --- |
| 保护 GMS | `FCMCompat.replaceGMSInMilletList()` 确保 `com.google.android.gms` 在 `Settings.System.MILLET_NO_RESTRICT_APP` 中；`Applier` 只在列表有变化时通过 `MilletCompat` 写回。 | Greezer/Aurogon 读取此列表并在后续冻结决策中排除 GMS；写入本身不会主动解冻已冻结进程。【源码、真机】 |
| 放行目标广播 | `FCMCompat.findAllFCMPackages()` 查询当前可解析的 c2dm receiver，排除 `android`；`Applier` 在 `Settings.Global.aurogon_enable` 的 `broadcastctrl:true` 段为这些包配置 `包名/com.google.android.c2dm.intent.RECEIVE`。 | 命中规则时，Greezer 可为该广播解冻目标 uid 并放行接收者。【源码、真机】这只覆盖 c2dm 广播路径。 |
| 可选自启动 | 设置项 `autoAllowFCMWakeForPlayStoreApps` 默认开启。开启时，`Applier` 仅对扫描结果中安装来源为 `com.android.vending` 的包，将 MIUI app-op 10008 设为 `MODE_ALLOWED`；单包失败会记录日志并继续。 | app-op 10008 参与后台唤醒及 MIUI 清理器的 force-stop 决策。【源码、真机】不覆盖其他安装来源，也不清除既有 `stopped` 状态。 |
| 请求恢复 GMS | 每次 `Applier.apply()` 最后都发送定向 `GCM_RECONNECT` 广播，并查询 `content://com.google.android.gms.chimera`。 | 真机实验中，广播不能及时解冻已冻结的 GMS；到达 GMS 进程的 provider 查询可以触发解冻。查询异常只记日志。【真机】 |

`Applier.apply()` 返回值仅表示 MILLET 或 Aurogon 设置是否被改写；**即使两个设置都无须改写**，它仍会执行可选自启动设置、重连广播和 provider 查询。历史记录只在相应调用路径按返回值或操作类型写入，不能当作每一次唤醒尝试的完整日志。

### 监听、重试与停止

- `Monitor` 为 MILLET 和 `aurogon_enable` 的精确 URI 注册 `ContentObserver`；收到变化后，`ShizukuRemote` 延迟 1 秒再执行 `Applier.apply()`。PowerKeeper 可以根据自己的 `userTable` 将 MILLET 整体覆写，所以需要持续对账。【源码】
- `Monitor` 监听 uid 的 active/gone 事件；`ShizukuRemote` 延迟 5 秒重新扫描 c2dm receiver。只有扫描结果集合变化时才再次 `apply()`。它还监听 app-op 10008 的变化，仅对当前关注的 FCM 包延迟 1 秒重设可选自启动策略。
- `Monitor` 每 10 分钟安排一次 `AlarmManager` watchdog；触发后延迟 1 秒，重新扫描应用并执行 `apply()`。这既补漏也再次尝试唤醒 GMS。配置项变化时，服务更新 `Applier` 配置；服务已启动则立即 `apply()`。
- `stop()` 先停止上述监听和 watchdog，再调用 `Applier.restore()`：从 MILLET 移除 GMS，删除设置值里 action 为 c2dm 的广播例外；如果剩下的是空的已启用 `broadcastctrl` 段，则移除该段。**它不会撤销已设置的 app-op 10008，也不会保证 Aurogon 的运行时映射立即清空。** `destroy()` 也会调用 `restore()`。当前实现并未记录原值，因此恢复时也会移除服务启动前已存在的 GMS 和 c2dm 条目。

## 3. 为什么 ContentObserver 要经过 App 进程

Shizuku 的用户服务以 shell/root 身份运行，但这类 `app_process` 没有普通 App 的 AMS 进程记录。在所调查 ROM 上，直接从 shell 进程注册 Settings 的 `ContentObserver` 会因找不到调用方 PID 而被忽略。【源码、真机】

当前实现由 `ContentCompat` 代理 `ContentResolver.sContentService`，使注册、注销事务通过 App 的 `:helper` 进程中 `CompatHelper` 返回的 binder 原样转发。于是系统看到的注册方是有进程记录的 App uid/PID，而 observer 的回调 binder 仍留在 Shizuku 用户服务中。Manifest 中的 `CompatHelper` provider 使用 `${applicationId}.helper` authority 和 `INTERACT_ACROSS_USERS_FULL` 权限；`ActivityCompat` 另外处理 Shizuku 进程获取 provider 的兼容性。若转发失败，`ContentCompat` 会退回直连；在所调查 ROM 上，该回退不能保证观察者注册成功。

这条桥接也引入限制：后台 App uid 的观察通知在该 ROM 上可能延迟约 10 秒；App uid 被 Greezer 冻结时，observer 回调还可能被 binder proxy 缓存，甚至在 App 死于冻结态时持续截流。【源码、真机】当前代码没有专门的 observer 自愈流程，10 分钟 watchdog 是现有兜底。Shizuku 服务不等于 App 主进程常驻，但观察注册仍受 App uid 状态影响。

## 4. 已知限制与诊断

| 现象 | 核查点 |
| --- | --- |
| GMS 又被冻 | 检查 MILLET 是否仍含 GMS，以及 `dumpsys greezer`。PowerKeeper 的 `userTable` 变更可能覆写 MILLET；设置变化触发的观察回调也可能延迟或截流。 |
| 目标 App 仍收不到消息 | 检查它是否有当前可解析的 c2dm receiver、是否在 `aurogon_enable` 的规则中、app-op 10008 和 `stopped` 状态。当前扫描基于 `queryBroadcastReceivers`，可能漏掉已禁用的入口；`broadcastctrl` 不覆盖 `startService`、`bindService` 或 Job 路径。 |
| 界面显示自启动关闭 | 当前代码只写执法用的 app-op 10008；MIUI 界面另读 10053/LBE 状态，系统同步也可能覆盖 10008。【源码、真机】 |
| 关闭服务后仍有广播放行迹象 | 所调查 ROM 的 Aurogon 广播映射更新时不会清除旧包条目。当前 `restore()` 修改持久化设置值，但没有为旧条目写惰性规则；运行时例外可能留到重启。【源码、真机】 |

只读排查命令（在已连接设备上运行）：

```sh
adb shell settings get system MILLET_NO_RESTRICT_APP
adb shell settings get global aurogon_enable
adb shell dumpsys greezer
adb shell dumpsys content
adb shell cmd appops get PACKAGE_NAME 10008
adb shell dumpsys package PACKAGE_NAME
```

这些命令分别用于核对持久化设置、冻结记录、observer 注册、执法用自启动状态和包的 `stopped`/组件状态。设置值正确也不等于真实 FCM 消息已投递；端到端结果仍需在目标应用和真机通知链路上验证。
