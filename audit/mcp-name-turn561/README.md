# MCP客户端配置名称

设置→MCP服务→“MCP 命名”打开对话框，输入后保存，再点击“复制配置 JSON”。默认gatewayrules。
名称是mcpServers下的客户端键，不是MCP serverInfo名称；不改端口、不重启服务、不更换令牌。保存后重新复制，已导入的外部客户端不会自动改名。
采用保守兼容子集：[A-Za-z][A-Za-z0-9]{0,63}。只允许英文字母与数字，首字符为字母。不含空白、中文、下划线、短横线等；输入不被偷偷清洗。不同客户端仍可能有其他限制，不保证所有产品均可导入。
草稿和对话框状态支持rememberSaveable；取消不保存，恢复默认只改变草稿。偏好提交成功后才更新UI；失败显示原名称，导出使用当前已确认状态。
导出仍使用实际端口、http连接类型、Authorization Bearer令牌与敏感剪贴板标记。JSON由JSONObject序列化，不通过字符串插值拼接结构；测试只用虚构令牌，不读真实剪贴板。

验证：
AUDIT_DEP_CACHE=audit/lifecycle-phase3/pinned-deps python3 -B audit/mcp-name-turn561/run_name.py --output audit/mcp-name-turn561/positive
AUDIT_DEP_CACHE=audit/lifecycle-phase3/pinned-deps python3 -B audit/mcp-name-turn561/run_name.py --negative-control --output audit/mcp-name-turn561/negative

21项JVM行为测试与10项源接线检查；负对照故意忽略用户输入的名称，应编译成功且自定义名称测试失败。CI另外运行Android/Compose整包构建，不将源契约当实机UI验收。
费用迭代price-estimate-turn560的未验证改动不纳入此次发布；仅4个目标文件和本目录测试/说明进入本批提交。
