# 请求生命周期第三批（Turn 495）

## 状态与范围
本批提供源码补丁和可复核 JVM 回环测试，**不是发布 APK**。最终数据以 verification-report.json 为准。
只改变四个生产文件及 CI 工作流：GatewayServer.kt、SocketIo.kt、HttpUtil.kt、GateLog.kt、.github/workflows/build.yml。
新增文件均在 audit/lifecycle-phase3 下；原安全、配置和原审查断言不修改。
本地 Git HEAD 仍为 b732e3c878f214771be0e33ba899e0603f98adc4，不代表当前工作树进度；禁止 reset --hard / clean。

## 修复语义
- model/stream/session 从顶层 JSON 读取；虚拟模型使用所绑定的启用规则。
- 未实现执行算子显式拒绝，不静默改成 first。
- 请求独占上游连接、发送标记与响应提交标记；在可能部分写入前标记已提交。
- 连接建立失败可换候选；已经发送且结果不明不自动重试。显式 HTTP429/503 在 first 策略下允许换候选；400/401/403/500/502/重定向不自动重试。
- 固定长度 POST 禁止 HttpURLConnection 缓冲重放；每次尝试独立许可并在 finally 释放。
- RST/下游写失败/停服取消不记为上游故障，不重试第二家。
- 完整响应后才记成功、恢复熔断及会话绑定。已提交响应出错只终止连接，不追加第二份 HTTP/JSON 错误。
- SSE 校验完整的 [DONE] 事件、流内 error、分块与 Content-Length 截断；按256KiB事件/行设置内存上限。
- HttpUtil 读体超时/截断带 error 返回，不再伪装空正文成功；正文上限8MiB。
- chat 每次一条终态，明确 success/cancelled/local_reject/upstream_failure；调用日志串行写入。
- 上游错误正文不写入调用日志，保留状态与异常类别。

## 验证命令（从项目根目录运行）
```sh
python3 audit/lifecycle-phase3/run_lifecycle.py --fetch-deps --output build/lifecycle-audit
python3 audit/security-phase1/run_security.py --fetch-deps --output build/security-audit
python3 audit/config-phase2/run_config.py --fetch-deps --output build/config-audit
AUDIT_KOTLIN_LIB="$HOME/.cache/gateway-security-deps" python3 audit/lifecycle-phase3/regression/run_tests.py
python3 audit/lifecycle-phase3/verify_scope.py
```
前三套本机最终样本为43/43、51/51、48/48；生命周期同一构建额外3轮独立回环各43/43，不算新增129个用例。
生命周期编译使用哈希锁定的Kotlin 2.0.21依赖、language-version=1.9、api-version=1.9；不等价于Android Kotlin1.9.24整包构建。
原审查28项：施工前13通过15失败，施工后18通过10失败；原命令仍退出1、NO_GO，禁止改为0。
verify_scope.py 的退出0仅表示**本批限定范围证据一致**；其报告仍带 overallVerdict=NO_GO。
原始调用JSONL/系统日志：ci-equivalent/evidence-logs/；重复样本日志：repeat-1-logs/ 至 repeat-3-logs/。

## 5个原反例转绿
- downstream_disconnect_must_not_retry_other_provider
- truncated_upstream_stream_must_not_report_success
- response_body_timeout_is_not_success
- virtual_model_uses_bound_rule_id
- nested_model_field_must_not_override_root_model

## 未修复的10项原审查失败
- provider_constructor_and_parser_tier_agree
- model_modalities_survive_runtime_parser
- explicit_free_price_precedes_paid_price
- canary_percent_changes_candidate_routing
- rule_explain_matches_actual_chain
- short_error_window_does_not_erase_long_window
- ab_split_handles_minimum_int_hash
- null_tool_calls_do_not_prove_tool_support
- done_only_sse_does_not_prove_stream_generation
- models_response_escapes_model_ids
下一批建议从模型能力探测、模型列表序列化与规则执行一致性进入；仍需独立备份与回归，不扩展到真实付费上游。

## 已知限制 / needs_human_review
1. TCP FIN 可能是合法半关闭，不能一律当取消；平滑关闭且未触发写失败时可能继续等待上游。真实RST、写失败、停服已覆盖。
2. HttpURLConnection disconnect 的即时性取决于平台；测试读超时300ms，生产90s为空闲读超时，不是总请求deadline。尚无Android网络栈取消时延验收。
3. 要求OpenAI兼容SSE完整[DONE]；仅EOF结束或不发[DONE]的提供商会报告不完整，需另立明确适配策略。
4. 限定429/503换候选减少不确定POST重放，但不能证明第三方错误响应绝无计费。
5. 未测手机安装、真实配置迁移、UI、真实网络切换、长期压力和保数据升级。
6. 未推送、未触发新远端构建、未产生APK、未装机、未修改真实网关配置。
7. 会话日志RSA签发工具入口缺失，审计日志完整门不能宣称PASS；与源码专项测试门分开。

## 备份与回滚
施工前当前工作树备份（不是旧Git HEAD）：audit/lifecycle-phase3/baseline-source.tar.gz，65文件、362533字节；逐项SHA256校验。
指纹：baseline-sha256.json。当前改动差异：lifecycle-phase3.patch。
备份只保留在工作目录，不把旧工具、凭据、Git历史或旧配置放入公开交付包。
回滚前先备份当前改动；只对上述5个文件从该tar包恢复，并比较baseline-sha256.json，禁止重置整个工作树。
未执行实际回滚，不能把备份校验称为恢复演练。

verify_scope.py 的历史基线校验须在原工作目录执行；源码包不包含施工前备份，不代表可单独恢复历史。
