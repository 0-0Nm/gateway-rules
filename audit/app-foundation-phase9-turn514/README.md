# 第九批：应用基础、共享流程与版本追溯（Turn514–515）

## 范围与方法
以09c071a5当前工作树为基线，不全仓推倒重写。一次功能批次处理：项目四段版本/构建身份、MCP适配职责拆分、UI/MCP共享模型发现与测量结果合并、编辑任务所有权/取消、配置提交反馈。
保留：applicationId、config-state-v1格式及revision、认证/限流/first路由/请求终态/四色状态机、固定debug证书和独立release签名。未操作手机安装、真实配置和付费上游。
原始备份81文件395536字节、逐项SHA256回读通过；构建/版本工具之外旧协议和业务回归断言不删改。

## 1 主.次.本.末真正进入产物
`version-policy.json` 为本项目独立策略：保留主0；从既有0.2迁移，本批功能次位升为3、用户验证轮本位0。基线项目CI run_number250，显示末位=run_number-250，因此下一首包预期0.3.0.1（最终以真实CI分配为准）。
Android内部versionCode=1000000+项目总run_number，独立于四段显示值；升次/本位并重置显示末位时不会倒退code或出现旧两位十进制拼接碰撞。code上限与纪元受到校验，不随意更改偏移。
主位由用户决定；次/本位变更与末位起点重置必须明确审查，不因计数溢出偷偷升高位。工具箱全局version.sh/version.state不读取来分配本项目，也不修改。
官方构建只允许本仓库main、固定workflow路径、checkout=GITHUB_SHA=currentmain。GitHub重跑attempt>1拒绝产出重复身份，失败后用新的workflow_dispatch，获得新的run_number；被取消的序号不回收。更换工作流计数来源或code纪元需迁移审查。
CI只使用自动提供的contents:read令牌做官方API只读核对，不携带认证跟随重定向，不把令牌写入回执。
本地未分配构建0.3.0.0/code1明确标local-unallocated，不冒称官方可覆盖包，也不消费CI计数。
Gradle将身份同时写入Manifest、BuildConfig与assets/build-identity.json；设置页和MCPstatus.build显示四段号、code、源码SHA、run与构建序号。配置revision和MCP协议/实现版本独立。
CI最后实际aapt读取Manifest并比较身份资产和预分配记录，不能只看文件名。固定debug验签仍独立执行，正式release身份不改。

## 2 职责拆分而非第二套业务
- AppSettings仅保留偏好开关；McpBridge和BackgroundDebug拆到各自文件，FQCN/原诊断工具名保留。新BuildInfo只提供不可变构建元数据。
- application/ProviderWorkflows统一UI/MCP发现、去重合并、选中模型预算、能力结果应用；不另写配置库或请求执行器。
- UI保存规则改走既有ConfigCommands.saveRule，携带expectedRevision。提供商/规则/虚拟模型保存与删除反馈区分持久化revision和运行态applied/applyError；旧Boolean API仍保留兼容。
- ProviderRepo/ConfigStore冲突、原子存储与运行发布语义不修改；MCP和UI入口不以“保存成功”冒充网关已应用。
- 本批未实施整个MainActivity/ViewModel导航重写、性能画像或所有屏幕重排；是评审后的可验证第一片，不宣称完整新架构完成。

## 3 编辑异步结果隔离
ProviderTaskGate ticket仅归当前编辑操作及providerId；新操作、返回、切换/重新打开提供商、修改URL/K/suffix/configRevision、新增/保存/删除、Activity销毁均使旧票据失效。
每次回到主线程应用前重新检查所有权。测量字段逐字段合并到当前model，不能以旧整个ModelInfo覆盖新价格、名称、模态或勾选。
发现模型仅拉目录，新发现默认未启用；已有配置保留。测试成功不自动替用户启用模型。
批量只取已启用且去重的前20个；UI默认2并发、上限4，工作循环不为上千模型创建无界任务。支持取消：停止后续循环/探测阶段并拒绝旧回调。已有HTTP在途请求受超时和平台中断能力影响，不能承诺立即中止上游或零费用。
发现端点循环在每次新请求前检查取消；能力测试的非流式/流式/工具各阶段检查。目录GET仅证明目录可达，不更新生成/工具能力证据；成本提示不声称GET或小token必定免费。

## 4 UI/MCP契约变化
UI生成测试按钮先提示成本和所选范围；提供取消入口。空选择不会自动挑第一个未启用模型发付费请求。
MCP `gw_test_models` 名称不变，但现在必须显式 `confirm=true`，只测试已选模型且最多20个，额外参数拒绝；客户端更新后须重新连接并刷新tools/list。
MCPserverInfo实现版本0.5.0用于说明本次工具契约变化；protocol2024-11-05仍保留，应用版本仍四段且另列status.build。
端点发现只支持http(s)且无userinfo/query/fragment，不返回密钥；格式/认证和配置验证仍由共享底层约束。

## 5 验证入口与已知证据层次
```sh
python3 tools/test_build_identity.py
python3 audit/app-foundation-phase9-turn514/test_app_contract.py
python3 audit/app-foundation-phase9-turn514/run_foundation.py --fetch-deps --output build/app-foundation-audit
python3 audit/app-foundation-phase9-turn514/verify_scope.py
```
新增：版本32项（身份与Manifest/资产比较其中有模拟aapt输出的边界单测）、共享流程41项（真实本机HTTP/MCP+隔离配置+任务所有权）、17项源码接线检查。41项另重复3轮，不算新增123项。
保留旧七组：安全51、配置48、生命周期43、语义77、原审查28、MCP57、四色80。保留窗口源码13及固定debug签名20+22。测试适配器BuildConfigFixture不是实际APK元数据证明；最终必须做真实Android编译与Manifest/资产验收。
既有请求/签名测试不因入口重构而删改，生产传输、配置格式与视觉状态文件基线哈希独立比较。
本地长命令曾超时，后续先查活动子进程与结果文件再续跑，保留超时证据；已刷新结果才视为源码绑定通过。
新版本、UI任务取消、费用确认、设置构建信息还需实际设备覆盖升级后验证；后台单测不能替代界面生命周期或真实供应商测试。

## 6 回滚和部署
固定debug证书不变；同证书且Android策略允许时可保数据覆盖，本批不自动安装/卸载。
旧版仍叫0.2.0/code1，四段切换后code显著提升；回退旧code需要另外的系统降级/回退方案，不能保证pm -r即可降级，更不能用清数据作为默认修复。
源码回滚前另存现有更改，按baseline-sha256.json精确恢复并审查本批新文件，不reset --hard、不git clean。
版本计数器和未来run_number不得回收；回退源码也不复用已发布构建身份。任何后续改workflow路径/版本纪元都需审查。
本批没有修改AB两库，也没有恢复旧API密钥。日志RSA锚缺签发能力仍未过，不混同业务/构建/签名门。
