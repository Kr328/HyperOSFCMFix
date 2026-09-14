# SimpleFCMFix for HyperOS (CN)

本应用为中国大陆 HyperOS 修复 FCM 意外断开问题。\(需要 [Shizuku](https://github.com/RikkaApps/Shizuku)\)

参考自 [dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix)

### 实现原理

向 Settings.System."MILLET_NO_RESTRICT_APP" 中注入 "com.google.android.gms"。

### 特性

1. 小，大约 100 KiB。
2. 无需短轮询，基于 Logcat 获取事件快速注入 GMS，同时保留 10min 一次的兜底检测和 FCM 连接检测。
3. 无需保活，后台逻辑均在 Shizuku 运行，与 App 本体分离。

### 构建

1. 使用 Android Studio 打开项目
2. 点击 "Build" → "Generate Signed APK"
