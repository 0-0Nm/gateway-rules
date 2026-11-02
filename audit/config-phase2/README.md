# Phase 2 — 版本化配置事务与实际限流热更新

## 范围与基线
本批基于 af1265442b0cbea2577bc84fb71487b927687c1d。只改变源码和隔离测试，不安装、不修改设备运行配置、不调用真实模型。

## 共同写入链路
UI、MCP和仓库命令统一经过 ConfigStore.transact：读取当前版本→校验expectedRevision→在独立副本合并修改→整份配置校验→同目录临时文件写入及fsync→原子rename→有序发布运行快照。
providers/rules/virtualModels/keys存入应用私有的config-state-v1.json，避免跨文件半提交。
进程内同步锁及文件锁保护事务；发布回调在事务序列内执行。持久化与运行时发布不是一个跨进程分布式事务，因此返回明确区分persisted/applied/applyError；运行时无法应用时不冒充全部成功，也不重复写盘。
断电级目录元数据fsync/Android文件系统恢复测试尚未执行，不宣称任意断电环境强持久性保证。

## 兼容与迁移
- 只有读配置时，不写迁移文件。
- 首次成功写操作导入旧providers.json/rules.json/keys.json/virtual_models.json，然后原子生成新state文件；旧文件保留不删除。
- state文件存在后只读state，不再从旧散文件回填。gw_reload_config读当前state版本。
- 合法空集合保持空，不自动注入mock或默认规则；MCP可以在尚未有提供商/规则时独立启动用于配置。
- 损坏或不合法旧配置明确报错，不覆盖原文件。未实现execute算子、不存在的虚拟成员/规则等会阻止迁移，需要先纠正配置。
- 删除仍被引用的provider/rule会拒绝，必须先显式修改或删除引用方。
- 新建提供商名称派生ID撞已有对象时拒绝，更新需用真实ID。
- UI提供商、规则和虚拟模型草稿有版本；过期草稿拒绝保存。规则编辑保留原有参数/execute args/override，不再以固定默认参数重建，不再保存规则时暗中重写提供商限流。
- 新增rate policy的softRateSec/softRateMaxSec往返保留；清空API key同时移除引用；自定义key ref保留；元数据扩展字段不因普通编辑丢失。

## 实际限流而非面板数值
- RateLimiter.updatePolicies更新实际策略，保留令牌余额、inflight和429冷却，不在配置变更后恢复满桶。
- 供应商禁用/移除后拒绝新许可，旧在途请求仍能归还槽位；重新添加相同ID不清空已有冷却。
- 旧请求成功不能清掉之后产生的429冷却。
- peek与acquire的冷却判断一致。
- 规则级override可收紧提供商总限制，不得放宽总上限。
- 允许许可的jitter实际执行；拒绝全部本地许可返回429/local rate limit，不再伪装上游502失败。
- 原400/422隐式改参数二次请求停用，防止一次许可发两次请求及擅自删除tools。后续请求生命周期批次再做显式、可配置、受限流控制的重试。
- 请求读取一个providers/rules/vmodels/keys版本；热更新以单一快照发布；诊断提供configRevision，MCP提供persistedRevision/runtimeRevision。

## 返回契约与可观测性
共享写命令返回ok/persisted/revision/changed/applied/applyError；失败返回code/error。
无服务运行时保存可成功但applied=false。list_providers返回所有模型可编辑字段与版本，排除API key及引用；virtual model回读包括完整配置和解析状态。
MCP必填参数先校验：漏enabled不再默认为true；不识别patch字段拒绝；非法数值/枚举/引用拒绝；批量模型含未知ID时原子拒绝全部。

## 验证
- 配置事务、真实McpBridge、运行时与限流48/48 PASS。
- 第一批安全回归51/51 PASS。
- 使用CI同款 --fetch-deps 入口复跑配置48/48 PASS。
- 原始审查28项（断言不变，补构造令牌）：13 PASS / 15 FAIL，整体仍NO_GO。
新转绿包括：RatePolicy默认值、软冷却peek、真实限流热加载、写失败传播、删空无mock、soft rate往返、空模态往返。
未修包括：下游取消导致上游重试、流截断假成功、虚拟ruleId调度、JSON字段提取、灰度/解释不一致、模型探测假阳性、零价识别等，按后续批次处理。
本机测试为Kotlin/JVM和最小Android适配器，不等于设备迁移验收。线上构建将额外编译Android真实UI/Service代码。

## CI
新增Configuration transactions and limiter regression步骤，和安全回归一起阻止编译前有已覆盖回归。一次原子提交，避免逐文件产生中间不完整构建。

## 下一批
请求生命周期：结构化解析、下游取消、响应提交后禁止重试、上游读取错误不得伪装成功、请求唯一终态日志。
