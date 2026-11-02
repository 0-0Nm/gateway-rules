# 第六批：固定的开发 debug 签名

## 用户确定的构建策略
开发迭代一直使用同一份 debug key，方便覆盖调试；最终正式版再由用户提供正式 release 签名。
“同一份”指相同私钥与证书，不是每次都叫 Android Debug。GitHub临时runner自动生成的debug.keystore会变化，不能覆盖不同证书的现有安装。

## 固定身份与存储
调试证书 SHA-256：d582b4c0e283445fd957df00818e30823011da6f15759ac1f0568617af2086ef。
- 私钥不入Git仓库、不入普通日志、不入公开构建产物。
- GitHub Actions只从该仓库Secret `GATEWAY_DEBUG_KEYSTORE_B64`恢复。
- 公仓 `tools/debug-signing-cert.sha256` 仅保存证书指纹。
- 原始调试密钥的本地副本保留在专用私有目录，目录0700、文件0600；此处不提供私钥/编码值。
- 使用标准Android debug alias与口令，仍须私下保管文件；不是生产release密钥。

## CI流程
1. 运行20项签名脚本回归。
2. 恢复Secret到 `$RUNNER_TEMP/gateway-debug.keystore`，keytool导出公证书并比对固定指纹。
3. Secret缺失、内容无效或证书不匹配时失败，不自动生成新的随机debug key。
4. 保留既有6组业务测试，通过 `-PgatewayDebugKeystore` 显式传入同一文件，执行 `gradle :app:signingReport assembleDebug`，不修改AGP/Kotlin/Compose版本。
5. 用官方apksigner验证最终APK V2签名并比对同一固定指纹；将无密钥的签名结果放入 `build/debug-signing-audit/results.json`。

## 本地与真实APK验证
```sh
python3 audit/debug-signing-phase6/test_debug_signing.py
python3 tools/debug_signing.py verify --apk <debug.apk> --sdk <android-sdk>
```
20项回归含缺失/损坏Secret、证书不匹配不覆盖旧文件、权限0600、拒绝符号链接、重复恢复保持同一身份、禁止隐式随机回退。
另做两次真实固定密钥的独立恢复，均同证书；用真实第五批APK做正反验签：随机旧证书被拒绝，固定debug签名验收副本通过，103个非签名ZIP条目内容一致。
验收副本仅用于测试门禁，不代替本次GitHub构建产物，不自动安装。
本批只改工作流/签名工具/测试/说明；Android业务源码和release签名配置保持不变。

## 当前手机过渡边界
手机目前安装06954a06，使用此前一次性debug证书15e56f7216f76ea677940b9b14483e6a633e6f40b3ade1cbd25aae58b429f2c5。
这份旧私钥没有保留在已核查的标准位置，不能由APK还原；不声称全盘绝无副本。
固定新debug key不能覆盖旧临时证书。若仍拿不到旧私钥，第一次迁移需要明确确认清空重装；本轮未获这个选择的确认，不卸载、不安装、不打断现有MCP。
以后同一包名且签名配置不变，满足Android版本/系统策略等条件时可保数据覆盖；不是承诺所有ROM/版本回退均无条件允许。
最终release切换用户自己的签名也不能直接覆盖不同签名的debug版，应另行迁移或区分应用ID。

## 备份
baseline-source.tar.gz是66350374当前工作树备份，69文件、382725字节，SHA256已逐项检查。
不reset --hard、不git clean；回滚先另存当前改动，再按该批精确清单恢复。
GitHub Secret与源码是两个操作域：回滚代码不代表已删除Secret；不得擅自删除唯一调试密钥。

## 首次云端反例及修正
运行41423569056的20项之前版本18项测试、密钥恢复、六组业务测试及整包编译都通过，但最终APK证书与固定指纹不一致，门禁真实拒绝。没有放宽指纹或把旧证书当正确。
修正：不再依赖AGP默认HOME位置；恢复和Gradle debug签名配置显式使用相同RUNNER_TEMP文件，新增2项路径接线回归，输出签名报告。正式release配置不变。需以后续新运行实测结果为准。
