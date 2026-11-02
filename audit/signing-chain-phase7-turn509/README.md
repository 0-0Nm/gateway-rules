# 签名全链复盘与修复（Turn509）

## 根因：不是证书坏，是CI误报
对运行41424836087/提交48a32098的真实APK取证：GitHub artifact13131448417摘要一致，ZIP CRC通过。
APK SHA256=732596f6b23133f57126182b80155d188471b63a25414c828361cdab94b3c5bf。
同一APK在官方SDK36和SDK37的apksigner中均验签exit0/V2有效，证书DER指纹均为已固定的d582b4c0e283445fd957df00818e30823011da6f15759ac1f0568617af2086ef。
SDK36的人类可读前缀是 `Signer #1 certificate SHA-256 digest:`；SDK37改为 `V2 Signer: certificate SHA-256 digest:`。
旧脚本一方面自动选最新安装SDK（CI37、本机36），另一方面只识别Signer #编号。于是CI解析得到0个指纹，却将“没有解析到”错误归类成APK_CERTIFICATE_PIN_MISMATCH。
原来本地正例只覆盖SDK36，未覆盖CI工具版本与输出；此前仅按默认签名路径修正属于过早推断。Gradle signingReport和最终实际APK现已证明显式路径方案签名正确，不再继续猜测修改Gradle或轮换密钥。
最早46847112失败包未另取APK，不对其实际证书做未证实断言；最新失败运行已用真实产物完整复现同脚本在36通过/37误拒。

## 全链核对
1. Secret：仓库专用加密Secret，恢复回执证书为d582…；不换Secret，不输出私钥。
2. 恢复key：keytool导出X.509证书DER计算SHA256，20项密钥恢复/权限/错误证书保护测试保留。
3. Gradle：显式RUNNER_TEMP私有keystore与gatewayDebugKeystore属性，debug signingReport为d582…，release Config=null。
4. 实际APK：按完整run/SHA及GitHub摘要领取，不以签名报告代替实际APK；官方36/37均验证签名有效。
5. 验证器：以 `--print-certs-pem` 返回的证书DER计算指纹，不依赖Signer描述标签；严格要求单签名、V2验证成功且只有一个证书身份。
6. 工具选择：CI显式使用build-tools36.0.0；本地默认同版本，缺失时明确失败，不悄悄使用最新版本。仍对真实SDK37输出及真实工具运行测试兼容。
7. 分类：缺证书/格式坏/多签名/真pin不匹配/签名无效有不同错误码；真pin不匹配仅返回公开的预期与实际指纹。
8. 产物：签名回执携带APK SHA256、工具JAR SHA256、工具版本与证书指纹，便于关联构建和下载。
9. 升级：固定debug身份只保证后续同身份更新的签名条件；手机当前仍旧临时证书，首次过渡另需确认，不自动卸载。
10. release：始终独立，正式版签名迁移/不同applicationId另处理；本批不引入release密钥。

## 验证
```sh
python3 audit/debug-signing-phase6/test_debug_signing.py
python3 audit/signing-chain-phase7-turn509/test_signature_parser.py
python3 tools/debug_signing.py verify --apk <APK> --sdk <SDK> --build-tools-version 36.0.0
```
- 原20项＋新增22项通过；新增项使用真实SDK36/37输出快照，覆盖旧正则误报、DER与人类可读指纹不一致、缺失/坏PEM、多签名、跨scheme相同证书、版本漂移、pin失败分类。
- 对同一真实48a32098APK用SDK36和SDK37各验证成功；对第五批不同签名APK，两工具均正确拒绝真pin不匹配。
- 实际修改并重包DEX的验收副本被官方签名验证拒绝；没有以只改正则“吃掉错误”让它通过。
- 不改Android业务源码；六组既有回归仍留在CI执行，不把签名测试替代它们。
- SDK37用于诊断的JAR从Google官方HTTPS archive按Range抽取，ZIP条目CRC已校验；没有下载完整66MB SDK并比对全档SHA1。诊断原包摘要与提取记录见sdk37-extraction.json；该JAR不提交仓库，CI仍固定36。
- 冻结输出只含公开证书，不含私钥/令牌。诊断APK、损坏验收副本、下载日志及JAR均不放公开提交清单。

## 尚待收口
本地修复不等于云端已通过，必须以本次修复完整提交的新运行结果为准。
手机不自动安装或清数据；MCP0.4后台自检需在固定debug版经明确过渡部署后实测，真实上游密钥仍未恢复。
原日志RSA签发能力仍不可用，日志快照/见证一致不冒充锚签名通过。
