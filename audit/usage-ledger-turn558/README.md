# MP02 第一片：被动 Token 观察与私有账本

## 范围
原生 OpenAI Chat JSON/SSE响应中已有的usage。复用原SSE解析器与256KiB非流式观察缓冲，不改响应字节、不强制include_usage、不另发探测、不改变429/503回退或已发送请求不重放的边界。
UI聚合页新增用量账本；MCP gw_debug_snapshot顶层usage在网关停止时也可读取同一私有账本；runtime.usage是当前实例的同源快照。
本次不实施金额估算或预算强制执行。旧价格字段没有币种、缓存输入单价等口径，记录当时价格而不推测币种；cost=null、budgetEnforced=false。

## 口径
一条持久记录对应一个已终结逻辑Chat请求，内部区分上游尝试。成功、上游失败、本地拒绝、取消分别计数；没有进入Chat处理路径的认证/HTTP framing拒绝不计入此账本。
usage重复事件按最新累计值覆盖，不相加。缓存是prompt_tokens子集，reasoning是completion_tokens子集。缺项、无效数值、截断和超过观察容量都不能当零费用。
断流即使观察到usage也标incomplete；只将完整reported尝试纳入已报告Token小计，其他已发送尝试明确计unknown。未发送尝试单列。
每个请求保留最多32个尝试详情，超出仅保留省略数；每个账本保留最多512条请求和两份各2MiB文件。统计仅代表当前保留窗口，不是全部历史或供应商账单。

## 存储和保密
filesDir/usage-v1/usage.jsonl及usage.jsonl.1，只在应用私有目录。写入串行化、文件权限owner-only、每条append后fsync，按容量原子旋转。进程内通过UsageLedgerStore共享实例；不承诺多进程独立写入。
不存请求/响应正文、异常消息、URL或原始凭据；credentialId是provider域隔离的SHA256截取128bit指纹，只用于归因，不是认证材料。价格保留当时快照，避免把未来配置误认当时价格。
读坏行保留其余有效历史并报partial，目录不可读等报error，写失败计unpersisted；重复未落盘记录不能回报持久化成功。应用不能因账本写失败扩大请求重试。
无手工清空、导出或上传操作；UI与MCP读取不创建日志文件。运行测试全部使用临时目录和本机回环。

## 验证入口
python3 -B audit/usage-ledger-turn558/run_checks.py
python3 -B audit/app-foundation-phase9-turn514/test_app_contract.py
python3 -B audit/float-state-phase8-turn512/test_render_contract.py
python3 -B audit/usage-ledger-turn558/run_negative.py

UsageAudit.kt含27项专用用例，纳入foundation原51项；含逐字节透传、SSE跨片/重复usage、截断、429切换、未知用量、大响应、重启、坏行、磁盘失败及有界保留。
首轮77/78：本地定长错误响应客户端先收到，服务端finally尚未追加。测试改为等待inflight归零的明确终态后检查，保留before/after证据，不改变业务计数或放宽断言。

## 来源与未验
借鉴Magpie审查的“逻辑请求/尝试分层、未知不当零”设计，Kotlin实现为本项目新代码；无新增外部依赖，无Go源码复制。
本轮Android设备UI、长期持久性和供应商实际账单对照需另验。进程被杀前尚未终结的请求可能未落盘，故不能将本窗口账本用作金融硬限额依据。
