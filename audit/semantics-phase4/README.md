# 第四批：模型探测、价格与规则一致性（Turn 497–498）

## 当前结论
已修复第三批结束时剩余10个原审查失败；原28项断言字节未改，最终全量28/28通过。
新增语义77项、安全51项、配置48项、生命周期43项均有保存结果。语义同一编译产物另重复3轮各77/77，不算新增231个用例。各组样本有交叉，不能据此推算覆盖率。
**源码限定范围门通过不代表可发布APK。** 没有本批Android整包、远端提交、装机、真实配置修改。

## 本批修改
- 结构化非流式/流式/工具探测：真实文本生成、完整SSE DONE、所请求get_weather函数及合法city参数才构成阳性；null、字段名字符串、空数组、角色块、DONE-only不构成阳性。返回false是“未证明”，不是永久不支持。
- /v1/models 使用JSONObject/JSONArray编码，保留引号、反斜杠、Unicode与控制字符的合法JSON转义。
- Provider构造/解析/UI缺省tier统一为paid，显式free原值保留；标签不是实际账单。
- runtime保留输入/输出模态，显式空数组不回填TEXT。
- 价格：内部null未知、0明确零价，两项齐全才比较；持久化priceVersion=1。旧无标记双零保持未知，旧正价保留原计算行为；新UI/API保存才升级，不扫描改写真实配置。
- 修价格UI：0显示0，微小价不舍入为0，点号编辑保留原文本，非法符号拒绝并提示而非删除负号变正数。
- cheapest_first明确零价排在正价前，未知价置后并保序。
- 原有first-sort-wins策略继续保留；explain与applySelect共用执行链，明确给出skipped步骤；随机算子的解释标记sample，不承诺下一请求相同。
- 灰度：首候选为灰度池，其他为稳定池；按percent与稳定哈希选择一池，不跨池回退；0/100边界明确，缺身份的中间比例走稳定池。
- A/B用floorMod避免Int.MIN_VALUE溢出；轮转推进原子化；短错误窗查询不删除长窗数据（仍最多保留500条/提供商）。
- ModelPicker内嵌LazyColumn加320dp高度上限；这是消除已定位无限高度条件的源码修正，不是真机布局验收。
- CI加入语义77项及原审查28项，连同前三批门禁在assembleDebug前执行。

## 运行命令
项目根目录执行：
```sh
python3 audit/security-phase1/run_security.py --fetch-deps --output build/security-audit
python3 audit/config-phase2/run_config.py --fetch-deps --output build/config-audit
python3 audit/lifecycle-phase3/run_lifecycle.py --fetch-deps --output build/lifecycle-audit
python3 audit/semantics-phase4/run_semantics.py --fetch-deps --output build/semantics-audit
AUDIT_KOTLIN_LIB="$HOME/.cache/gateway-security-deps" python3 audit/semantics-phase4/regression/run_tests.py --output build/original-audit
python3 audit/semantics-phase4/verify_scope.py
```
最后一条核验脚本须在保留本批历史证据的原工作目录执行；源码包不包含施工前备份或历史环境，不应把缺失基线绕过为PASS。
Kotlin2.0.21编译器配language/API1.9用于语义、生命周期和原审查，不等价于Android项目Kotlin1.9.24+Compose1.5.14构建。

## Android构建与发布
本机实际有SDK34，缓存Gradle9.4.1，但缺项目指定Gradle8.7/AGP8.5.2/Kotlin1.9.24/Compose1.5.14依赖。
一次离线诊断assembleDebug退出1，报com.android.application:8.5.2无法解析。未升级项目插件“绕过”，未下载大型工具链，未产出APK。
GitHub只读刷新记录在remote-preflight.json；远端main在查询时仍为fa72727693f055260563118b8271a45427680ddf。
remote-source-diff.json按远端Git blob比较第三、四批合并内容，不能只发布第四批遗漏第三批。
当前发布进程GH_TOKEN/GITHUB_TOKEN未注入。不要将PAT贴入聊天、源码或交付目录；需经私有运行环境提供后再重新确认远端父提交。
publish-manifest.json是新清单；tools/publish_atomic.py默认只验证，只有显式--publish才写远端，且校验父提交、不强推、不自动重试不明写操作。
```sh
python3 tools/publish_atomic.py --manifest audit/semantics-phase4/publish-manifest.json
# 私有凭据注入、重新核对父提交后才可运行：
python3 tools/publish_atomic.py --manifest audit/semantics-phase4/publish-manifest.json --message 'fix: request lifecycle and model routing semantics' --publish
```

## 边界与下一步
1. 仍需真实Android整包编译、Compose界面、首次启动、配置迁移、保数据升级、网络切换、长期运行和取消时延测试。
2. 上一批FIN半关闭限制、生产90秒空闲读超时、SSE要求DONE等边界继续有效。
3. 本批价格标记语义与灰度分池改变旧行为；部署前核对真实配置，禁止静默将未知价认作免费或跨灰度池调用。
4. 本轮只查询公开GitHub元数据，未真实认证发布凭据、未请求真实付费上游。
5. 本机没有可调用的keystore_decrypt；日志快照/见证可核对，RSA锚未重签，完整日志门不能宣称PASS。

## 备份与回滚
当前工作树施工前备份：audit/semantics-phase4/baseline-source.tar.gz，365715字节，67文件SHA256逐项核验。
该基线已经包含第三批未发布改动，本地Git HEAD落后，禁止reset --hard或git clean。
本批差异见semantics-phase4.patch；回滚需先备份当前文件，再按baseline-sha256.json仅恢复清单内15项。未执行实际恢复演练。
源码与证据包不含私有凭据、密钥库、应用真实配置、Git历史或旧源码备份。
