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

## Turn 66 (上轮欠账补齐 + 云端构建打通)
### 欠账补齐
- SESSION_LOG 已落盘(turn57-65 内容)
- 首轮构建结论已取：run 40270699709 = failure

### 构建失败根因(已取证)
```
Warning: Failed to find package 'tools'
Error: /usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager failed with exit code 1
```
android-actions/setup-android@v3 试图安装已被 Android SDK 官方移除的 `tools` 包。
runner 本身已预装 SDK(/usr/local/lib/android/sdk)，该步骤纯属多余。
修复：删除 setup-android 步骤；setup-gradle 固定 gradle-version 8.7
(AGP 8.5.2 要求 Gradle 8.7+)。

### 构建打通
commit 0247e31 -> run 40270922136 = **success** (云端可出 APK)

### 真实代码(7 个 Kotlin 文件)
gateway/Contracts.kt    6551B  Model/Provider/RuleSpec/OpCall/RequestCtx + JSON 反序列化
                                ruleSpecVersion=1 硬校验; apiKeyRef 只存指针
gateway/GateLog.kt      6279B  双轨日志: calls/YYYY-MM-DD.jsonl + system/gateway.log
                                密钥强制脱敏(SENSITIVE 集合); 大小轮转; 写失败不阻塞
gateway/RateLimit.kt    5478B  四层闸: 令牌桶(ratePerMin+burst) / 最小间隔 / 抖动 / 并发上限
                                peek() 只读不消耗(供 explain); acquire/release 配对
gateway/SafeExpr.kt     7204B  受限表达式: 白名单文法 + 递归下降; 无函数调用/属性/下标
                                MAX_LEN 512 / MAX_TOKENS 200; 解析失败一律 false(安全默认拒绝)
gateway/RuleEngine.kt  11706B  19 个 select 纯算子 + RuntimeState(rr/prefix/errorWindow)
                                explain() 用 dryRun 副本, 不推进轮转状态
gateway/GatewayServer.kt 14842B ServerSocket + 8 线程池 + HttpURLConnection
                                /v1/models /v1/chat/completions /v1/_gw/status /health
                                SSE 字节透传; 429 -> breaker OPEN 10min; 限流准入+归还
MainActivity.kt         9032B  状态面板: 端口/启停/自检/日志tail; 纯代码 UI 无 layout 依赖
                                自检覆盖 规则选路 + SafeExpr 注入拦截 + 限流第二次拦截

### 已知取舍(诚实标注)
- execute 目前只实现 first 语义(顺序尝试)；race 真并发需 coroutines+Deferred，
  当前版本为保证稳定先串行。Python 侧已验证 race 语义，Kotlin 侧后续补。
- 无 UI 导入 provider 功能，DemoConfig 内置 mock provider。
- 预置 mock 上游 http://127.0.0.1:9099/v1 用于自检。

### 提交
14bb5e0  5 个核心文件
d92b4dd  GatewayServer + MainActivity

## Turn 67 (装机验证第一轮 + 修正)
### 云端构建全部成功
- 40270922136 (0247e31 骨架)                success -> APK 1,210,190B
- 40271148411 (14bb5e05 5个核心文件)         success -> APK 1,262,897B
- 40271283472 (d92b4dd +GatewayServer+UI)   success -> APK 1,283,270B

### 装机
- artifact 12629001336 下载解压 -> app-debug.apk 3.7M
- 复制 /storage/emulated/0/Download/gateway-rules-debug.apk
- np_apk_install: com.aqi.ccb / GatewayRules / 3,818,187B / fresh install 成功

### 发现两个真实问题(已修)
1 **前台被抢占无法点击**: RikkaHub 始终前台, np_apk_launch 三连 recovery 仍 mismatch
   -> 本质是设计缺陷: 网关作为常驻服务不应依赖手动点击启动
   -> 修: MainActivity.onCreate 末尾 postDelayed 600ms 自动 startGateway()
2 **总耗时计时起点错误**: requestEnd 用 t0Of(reqId, at)=单次尝试起点,
   不是请求起点 -> 修: handleChat 开头记 reqStart, requestEnd 用 reqStart
   同时删除无用的 t0Of 函数

### 提交
2126759 feat: auto-start gateway on launch; fix total-latency timing

### 待验证
- 自动启动后端口 38080 是否真的监听
- /health /v1/models /v1/_gw/status 是否可访问
- 日志文件是否落盘 (/data/data/com.aqi.ccb/files/logs/)
