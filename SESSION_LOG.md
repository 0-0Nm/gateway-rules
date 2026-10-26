# gateway-rules 会话简报

## Turn 57-65 (Android 侧落地启动)
### 环境实测
- mobide 构建箱：用户已拉活，list_dir/list_tree/read_file/find_files 全通
- 空项目骨架：pkg=com.aqi.ccb, minSdk28/target34, MainActivity.java, **无 build.gradle**
  -> javac/aapt2 直编路线，compile_java 明确拒绝 .kt/.kts/.aar/implementation 依赖
  -> 结论：该架子编不了 Kotlin，需改路线
- 骨架内容全读过：MainActivity 是标准 HelloWorld，com/yfapp/mobide 是空占位目录，
  libs/assets 只有 .gitkeep，build/gen 空 -> 无工具链命根子，可整体替换

### 改走 GitHub 云端构建（用户提议 + 授权）
- GitHub 通道探测：ghfast.top 可用(1016ms/3507B/s)，gh-proxy.com 可用，ghproxy.net 超时
- Token 实测：账号 0-0Nm，过期 2027-01-24，permissions admin/maintain/push/triage/pull
- 已建私有仓：0-0Nm/gateway-rules (default branch main)，未动既有 vdisplay-doc
- 骨架 8 文件推送成功 commit 86caba9d：
  .github/workflows/build.yml  (setup-java17 + setup-android + gradle assembleDebug + upload-artifact)
  settings.gradle.kts / build.gradle.kts / gradle.properties
  app/build.gradle.kts (AGP 8.5.2 + Kotlin 1.9.24, compileSdk34)
  app/src/main/AndroidManifest.xml (INTERNET/FGS/WAKE_LOCK/POST_NOTIFICATIONS)
  app/src/main/java/com/aqi/ccb/MainActivity.kt
  README.md
- Actions run 40270699709 已触发(queued)

### 策略说明
先验最小骨架能否云端出 APK，再注真实业务代码。理由：一次性推大坨代码若编不过，
排查成本数倍于分步验证。

### 待完成
- 构建结论
- 真实网关服务端代码
- 三项常驻要求落地：①逐请求调用日志 ②系统运行日志 ③自定义限流(ratePerMin/burst/minIntervalMs/jitterMs/maxInflight)
