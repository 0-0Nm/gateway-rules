# Phase 1 — 安全边界修复候选（尚未线上构建/安装）

## 本批范围
- MCP 固定回环监听；网关默认回环，局域网是显式确认选项且仍需令牌。
- 控制面、数据面使用独立的每安装实例随机256位令牌；存于应用私有SharedPreferences（本批不是硬件密钥保险箱），不写日志/status/diag。
- 所有接口先做peer/Host/Origin/Bearer检查再读body；拒绝浏览器Origin（包括null）、重复头、TE、超长/负长度、截断body、非精确路径；不再通配CORS。
- MCP业务失败正确设置isError；控制令牌不能访问数据面，数据令牌不能管理MCP。
- 新安装MCP默认关闭；持久化开关保留；设置状态在Context attach后初始化；服务端口恢复读取持久配置，MCP不再写死网关端口。
- 内部健康检查携带数据令牌；不因单纯打开页面自动启动本来没有运行的网关；MCP连接JSON包含管理令牌，复制动作显式、剪贴板标记敏感。
- Server停止时关闭已接受客户端与工作池；并发满载拒绝连接而不是在accept线程运行阻塞业务。仍未完成上游取消传播和完整生命周期整改。
- 三条携带上游密钥的HTTP路径禁止自动重定向；未知重定向需明确配置而非转发凭据。
- 移除已跟踪tools/push.py及本地辅助脚本的硬编码凭据；改从环境取发布授权。旧GitHub token尚需在GitHub撤销轮换。
- GitHub Actions增加密钥扫描和51项安全回归；依赖版本及SHA256固定；一次原子提交，不逐文件产生中间坏版本。

## 已验证
1. 本机真实服务器Socket回归51/51 PASS，compileExit=0，testExit=0。
2. 与CI相同的 --fetch-deps 入口使用固定依赖缓存重跑51/51 PASS。
3. 原审查28项仅补必填构造参数、不改断言：6 PASS / 22 FAIL（此前3/25）。
   新转绿：截断body拒绝、不完整header不处理、MCP业务错误标志。
4. 结构检查通过；已跟踪源码密钥扫描为0。另对发布清单全部文件复扫。

## 不夸大范围
- 未在线上执行Android Gradle/Compose编译：本机没有Android SDK/Gradle环境，线上提交需要已轮换发布凭据。
- 仍有22项已知失败：写盘假成功/事务/限流热更新/下游断线重试/规则绑定/探测假阳性等，留待后续批次。全软件仍NO_GO。
- 该补丁未安装。当前手机旧版本的监听和认证行为未改变。
- 令牌升级属于有意的不兼容变更：升级后必须重新复制MCP配置到可信客户端，并把网关访问令牌用作客户端API Key；它们不是上游API Key。
- 局域网HTTP仍无传输加密，仅应在可信网络使用；不应直接暴露到互联网。
- MCP能力列表仍未完全覆盖网络配置管理；安全凭据引导由显式设置页操作。不能借未认证MCP公开获取引导令牌。

## 发布阻塞及处理
本地 /root/toolbox/workspace/.gh_token 与已公开源码里的凭据相同，检查仅输出布尔结果，未展示内容、未使用它提交。
请先撤销旧GitHub凭据，将新凭据通过私有环境变量 GITHUB_TOKEN/GH_TOKEN 或本机受管凭据入口提供，勿贴到对话/仓库。
发布需对应仓库Contents写入权限；修改工作流还需工作流权限。身份是否有权限由真实GitHub响应判断。

准备好的提交命令（私有运行环境已注入轮换凭据后）：
```
python3 tools/publish_atomic.py --manifest audit/security-phase1/publish-manifest.json --publish
```
工具会校验全部文件SHA256、远端分支未移动，一次创建commit并非force更新main；push触发build-apk。
未提供有效凭据时明确拒绝，不把旧版本重跑算成本次构建。

## 测试复跑
```
python3 audit/security-phase1/run_security.py
python3 audit/security-phase1/regression/run_tests.py
```
安全组应exit0；全审查组目前应exit1。两者必须分开报告。

## 回滚与数据保护
修改前应用源码及工作流已备份为baseline-source.tar.gz，基线SHA256记录在baseline-sha256.json；不包含运行中的keys.json。
本轮未卸载、重启应用、写运行配置或调用真实模型。不导出含旧凭据删除行的git diff，发布包只含安全的新文件内容。
