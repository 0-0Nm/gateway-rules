# 第五批：MCP后台自动调试

## 既有能力与新增范围
Turn505实测安装版的gw_get_status、gw_read_log已可用；所以不是“从来没有日志”。旧版主要不足：系统日志尾部全文件读取、缺调用链追踪、状态信息不足、服务控制和隔离自检没有MCP入口。
本批增加以下工具，不创建Activity，不模拟点击：

| 工具 | 用途 |
|---|---|
| gw_log_query | system/calls日志，精确requestId/kind/level，脱敏后contains，游标继续 |
| gw_trace_request | 根据响应X-Gateway-Request-Id关联request/attempt/result |
| gw_debug_snapshot | 配置与运行版本、连接、请求计数、各提供商限流和熔断 |
| gw_explain_route | 真实/虚拟模型选路解释，共用生产select链，不发网络、不推进状态 |
| gw_gateway_control | confirm=true启动/停止网关Service，不停止MCP；LAN配置另需allowLan |
| gw_self_test | 零外网隔离自检，临时真实GatewayServer及回环上游，不动真实配置 |
| gw_probe_request | 显式confirm的一次真实请求，走已运行本机网关，最多64 tokens、总超时1–15秒，不返回正文 |

MCP serverInfo版本0.4.0。原工具名保持兼容；gw_get_status新增gatewayRunning/runtime，gw_read_log继续返回lines，但最多200行以约束诊断负载。
新版安装并重新连接后，客户端应刷新tools/list；当前旧工具清单不会自动凭空获得新接口。

## 推荐后台流程
1. gw_debug_snapshot + gw_log_query(stream=system,level=ERROR)确定配置、运行和错误情况。
2. gw_self_test：默认隔离，无真实上游请求，9项认证/健康/模型列表/JSON/SSE/无效请求/单次尝试/配置不变检查。
3. 已有配置使用CRUD工具修订，gw_explain_route核对候选与绑定规则；此解释只到select阶段，不承诺真实限流与会话重排结果。
4. gw_gateway_control(action=start,confirm=true)。必须检查completed/gatewayRunning；仅accepted表示异步请求已发出，不是已启动。
5. 用户批准可能产生费用后，gw_probe_request(modelId=...,confirm=true,maxTokens=8,timeoutMs=5000)。一次网关请求，强制最多一个上游尝试；不自动调用全部模型。
6. 使用返回requestId调用gw_trace_request，hasMore=true时原样沿nextCursor继续。响应正文/提示词/认证头/密钥不返回。
7. 测试结束如需停服务，gw_gateway_control(action=stop,confirm=true)；MCP保持可用。

## 日志安全与限制
- 只可读日志根下system/gateway.log及指定calls日期JSONL/1–3轮转文件；无任意路径、无任意shell；拒绝符号链接。
- 每页最多16文件快照、1MiB扫描、200记录、约64KiB返回；长行会跳过并报告partial/skippedOversizeFragments，不伪装完整结果。
- 游标绑定查询与文件快照，新增尾部不会混入本轮历史页；轮转/缩短/前缀变化返回CURSOR_STALE，需重开查询。不是外部不可篡改审计，不能证明任意文件内容从未被其他程序修改。
- calls输出白名单字段；headers、body、messages和自定义字段不出接口；密钥按当前配置及本地令牌精确替换，再按常见凭据格式脱敏。
- contains在脱敏之后匹配，避免用匹配计数试探被遮蔽的密钥。
- 日志读取在配置损坏且无法取得密钥脱敏表时安全失败，不返回未经可靠脱敏的旧日志。
- 写日志不包含原始请求正文；通用token/key/password等字段遮蔽，认证头完全遮蔽，不保留密钥前后字符。

## 后台能力边界
MCP自己必须已启用并在Android前台服务中运行（通知常驻不等于Activity切前台）。系统杀进程、禁止后台服务、安装权限和UI渲染仍受Android约束，不能承诺MCP替代所有系统/界面验收。
网关启停可能受ROM后台服务策略限制；工具返回接受与观察状态分离，不静默打开Activity以规避。
真实探测固定连本机网关，禁止任意URL/外传密钥；prompt最多512字符，max_tokens是向提供商提出的限制，不代表上游必然遵守或零费用。连接关闭不能保证上游取消计费。
隔离自检与真实探测一次只允许一个；BUSY直接拒绝，不排无界任务。内置自检使用随机临时端口并释放资源；测试会留下脱敏诊断日志，但不写真实配置。
UI、Android服务限制、真实上游和长期负载另验。本批源码/CI测试成功不能声称手机旧版已具备新增接口。

## 验证
```sh
python3 audit/mcp-debug-phase5/run_debug.py --fetch-deps --output build/mcp-debug-audit
```
生产McpServer在回环HTTP上真实调McpBridge；57项包含权限拒绝、工具发现、日志脱敏、分页/旋转、路径限制、真实JSON/SSE/请求ID与限次、隔离自检、状态恢复。
4个新边界反例先复现50/54，再修到54/54：limit长整数溢出、未知token/key赋值漏遮蔽、跨stream筛选参数被忽略、慢响应头超过总期限。boundary-before/与boundary-after/记录保留。
Android Context/Service仅用JVM适配器隔离平台，Service系统调度需要Android构建/设备验收；不将适配器当真机证据。
原28项、安全51、配置48、生命周期43、语义77全部保留，不削弱原断言。

## 回滚与状态
施工前备份baseline-source.tar.gz（65文件，366031字节）和baseline-sha256.json；基线是已发布06954a06，不是落后本地Git HEAD。
不reset --hard、不git clean；恢复前另存当前文件，按本批变更清单恢复，未实际演练回滚。
当前手机已安装06954a06，旧版测试数据经用户确认清除；修正曾经停留在“未安装”的旧日志状态。
历史huanyan-a/b测试站点和密钥尚未恢复，不把演示/回环测试当成真实上游验收。
安装新构建仍需签名匹配及明确设备操作授权；本批不自动卸载正在连接的MCP，不切换当前前台。

补充3项失败关联测试：响应正文超时/截断保留requestId和已读字节；连接/响应头阶段失败显式traceAvailable=false，不编造请求ID。
