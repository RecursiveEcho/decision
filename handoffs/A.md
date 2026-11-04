# A 后端回执

状态：开发中。开始时间：2026-11-04 17:29:59 Asia/Shanghai。完成时间：未完成。

已核验独立工作区 `decision-worktrees/backend`、`feat/mvp-backend`、共同基线 `2fcea57`；独立只读审查已通过。本对话经用户明确分配为 A，不负责根目录或 E 集成。

实现基线：Java 17、Spring Boot 3.5.16（官方 Maven 仓库与系统要求已核实）、PostgreSQL/Flyway，单体持久化 worker；不新增消息队列或外部向量库。

正在实现 `backend/`，先交登录、问题、资料、消息、Job。验证、可联调提交尚未产生。

外部证据：DeepSeek 未配置/未验证；Wiki 实例版本/token 待 E 提供；真实观察、桌面通知、小程序真机、远程部署均未在 A 验证。不触发屏幕采集。

## G1 可联调切片（2026-11-04 17:43 Asia/Shanghai）

- 已实现：契约全部 HTTP 路由、持久化 worker、登录/设备权限、事务幂等、问题/资料/消息/引用、记忆纠正撤销、观察入库、议题及 Delivery、决定与追加复盘。`backend/openapi.json` 为机器可读接口；`backend/Dockerfile` 可供 E 构建。
- 验证：独立一次性 PostgreSQL 16 测试库；18项全部通过（含 Wiki public URL 和 memories 列表）。`backend/target/test-run.log`、surefire reports 为本地证据，未上传运行日志。
- 配置：根既有 APP/DATABASE/DEEPSEEK/WIKI 配置；新增 `WIKI_LOCALE`（E实例 en）、`WIKI_PUBLIC_URL`（浏览器可达地址，默认回退 WIKI_BASE_URL）、`APP_MOCK_MODE=false`、`APP_WORKER_ENABLED=true`。观察 hash 采用 text UTF-8 SHA-256 hex；sourceLocator 只保留不透明 frameId/resourceId，任意路径不会保存。
- Model：真实模式默认；尚未由 A 完成真实调用，E 已报告官方 DeepSeek 请求通过，须继续后端来源链验证。
- Wiki：按官方 v2.5.315 GraphQL schema 实现；E 已报告实例2.5.317启动，A尚待实际更新/读取。内部 WIKI_BASE_URL 与外部 WIKI_PUBLIC_URL 已分离。
- PostgreSQL 首次测试发现本机 Java 系统 SOCKS 属性影响 loopback，已用 JDBC 直连 socket factory 修复并在真实隔离库验证，不修改系统代理。
- 已知范围：主题目前为明确标注的来源摘录，未实现跨文章智能总结；列表在内存排序分页，单用户规模适用；后端只支持单实例 worker。真实采集/通知可见/小程序真机不在本批测试证明范围。
- 未完成：真实模型+Wiki链路、Docker构建及总控集成联调；本人尚未验收。不改 TGA、不启用采集。

### G1 提交和真实联调进展（2026-11-04 17:46 Asia/Shanghai）

- `8377431` 已提交并推送 `origin/feat/mvp-backend`；第一次 push 遇 GitHub HTTP 500，核对远程未更新后重试成功。
- A 用独立宿主端口18081执行了真实后端→DeepSeek链路，仅合成资料：source入库成功，real模式回答包含5条实际原文引用，逐条回查通过；Wiki job失败（继续定位），决定/复盘真实脚本尚未走完。
- **已停止 A 的18081服务进程**，不再启动根.env/共享业务库worker；E独占8080/8088和共享环境联调。隔离test.sh只使用一次性专用测试库。
- 正在按E反馈补同问题主题合并、真实模型总结（观点/适用条件/不确定性/来源）。既有摘录不再当作知识摘要验收。


## G2 主题摘要与 Wiki 兼容修复（2026-11-04 17:48 Asia/Shanghai）

- 按E要求补齐同一问题下相关文章合并为一个Topic；真实模型analysis输出观点、适用条件、不确定性、证据，引用核验后更新主题并递增revision，旧revision/证据存history；并非再用摘录代替总结。
- 摘要未返回/无有效引用/模型未配时：Source仍成功保存，analysis job失败，Topic明确显示摘要生成未完成；不能标摘要成功。模型可返回无讨论，不强制通知。
- Wiki HTTP 400已定位：同一实际实例、同一GraphQL查询，Java默认HTTP/2升级返回400 HTML；固定HTTP/1.1后返回200 GraphQL data。已修正Upstream；尚待E用更新后代码执行真实更新读回。
- 新增5项验证：多文章主题合并及修订历史、模型失败保留Source、伪造摘要引用拒绝、处理中记忆上下文变化拒绝旧回答、Wiki HTTP/1.1创建/更新及读回。隔离PostgreSQL共23项通过。外部HTTP夹具属于替身，未冒称真实模型/Wiki验收。
- `backend/tools/live_smoke.py` 提供只用合成资料的真实闭环检查，读取指定忽略env，不打印key/token/模型正文。E服务ready后可运行，A不再启动共享库服务。
- C已协调使用frameId/resourceId定位，不扩展共享字段。

## G2 边界补强（2026-11-04 17:51 Asia/Shanghai）

- `a0224be` 已推送：同问题主题摘要/证据合并、Wiki HTTP/1.1及真实链路脚本。
- 继续修复发现的边界：外部响应整体60秒deadline覆盖body读取、1MiB硬上限；网页总读取时间上限；撤销的本人记忆可在明确激活时恢复新来源；已发送通知选择稍后会重新挂起，到期才能再次交付。
- 隔离PostgreSQL共26项通过（0 failures/errors/skipped）；新增真实HTTP测试夹具模拟慢响应与超大响应，并未调用真实外部服务。
