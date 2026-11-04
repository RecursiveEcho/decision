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
