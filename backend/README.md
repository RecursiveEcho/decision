# Decision 后端

Java 17、Spring Boot 3.5.16、PostgreSQL 16+、Flyway；运行接口见 [openapi.json](openapi.json)，遵循 `docs/mvp/CONTRACT.md` v1。

## 启动

在本模块目录执行 `./mvnw -DskipTests package`，通过环境变量注入配置，再执行 `java -jar target/decision-backend-0.1.0.jar`。本模块不自动读取根 `.env`，由 E 启动器/Compose 注入；不要把真实 key 放在命令参数或仓库中。

必需项：`DATABASE_URL`（JDBC PostgreSQL URL）、`DATABASE_USER`、`DATABASE_PASSWORD`、`APP_OWNER_ACCESS_CODE`（至少 12 字符）、`APP_TOKEN_SECRET`（至少 32 字符）。可选 `APP_PORT` 默认 8080。

外部服务：`DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`（默认 https://api.deepseek.com，不重复写 /chat/completions）、`DEEPSEEK_MODEL`（默认 deepseek-flash）；`WIKI_BASE_URL`（服务端请求地址）、`WIKI_PUBLIC_URL`（浏览器页面地址，未配时回退 base）、`WIKI_ACCESS_TOKEN`、`WIKI_LOCALE`（默认 zh，E 新实例使用 en）。Wiki 2.x GraphQL 接口按官方 v2.5.315 schema 核对，部署实例由 E 锁定。此 adapter 不使用 WIKI_SITE_ID。

`APP_MOCK_MODE=true` 才启用测试替身，默认 false；明确返回 `mode:mock`，mock 分析返回无需提醒，不虚构证据。`APP_WORKER_ENABLED=false` 仅供测试/维护。真实模式未配模型时保存用户消息、任务失败为 MODEL_NOT_CONFIGURED；资料与主题仍保存，Wiki 未配标 pending_configuration。

所有 POST（含登录、心跳、ack）都需 `Idempotency-Key` UUID。Bearer 登录有效 24 小时，设备 token 有效 30 天；设备注册默认 paused，只有本人 owner 控制接口可设置 desiredState。观察上传还需本设备运行心跳在 90 秒内；客户端本地同意和底层采集启停由 C 负责。

## 自检

`./test.sh` 使用独立且一次性的 Docker PostgreSQL 测试库，结束只删除本脚本创建的容器；不读取根 `.env`、不触碰业务库。输出 `target/test-run.log` 和 `target/surefire-reports`（忽略提交）。也可设置 `TEST_DATABASE_URL` 后执行 `./mvnw test`，数据库必须专用且命名为 `decision_backend_test`。

`Dockerfile` 用于 E Compose 构建；后端容器以非 root 身份运行。启动自动迁移；Flyway 失败则不接流量。

## 实现与边界

- `Api/Auth/ApiFilter`：登录限流、HMAC token、设备权限、请求体 1 MiB 上限、加密幂等响应。变更事务以单所有者 PostgreSQL advisory lock 串行化，避免重复任务、版本覆盖与暂停竞态。
- `Store/Domain`：单库按 kind 区分实体；records JSONB 与 record_history 保留历史；jobs、observation_keys、discussion_keys、model_calls 等独立表承载并发约束和用量。删除是当前检索撤销，备份/历史不声称物理擦除。
- `Worker`：一条有界持久化 worker，200 个待处理/运行任务上限；启动将中断任务标 failed/PROCESS_RESTARTED，排队任务继续。新来源分析按问题合并 dirty 状态，允许无议题；失败不自动无限重试模型。
- `Model/Evidence`：最多 8 段来源（每段 6000 字）、20 条活跃记忆和近期用户消息；来源与系统指令分离。引用校验 source 所属问题、实际原文和提供给模型的片段。模型无副作用工具，不能创建决定。无效引用删除并提示；并非证明回答每项断言成立。
- `SafeFetch`：仅标准端口 HTTP(S)，每跳和实际连接 DNS 验证拒绝私网/本地地址；重定向、体积和超时有上限。读取失败 job 明确失败，用户可粘贴正文。暂不处理浏览器登录网页或视频。
- `Wiki`：按 topic UUID 固定页面路径，先查后建/更新，再回读全文比对；不将 HTTP 200 等同于同步成功。同问题资料合并至一个主题；模型提炼观点、适用条件、不确定性及核验引用，主题修订保留历史。摘要失败明确显示未完成并保留已保存来源。来源/记忆撤销时主题失效并排队覆盖 Wiki 内容。
- 记忆修正/删除让派生记忆、主题和议题失效。原决定和复盘保留历史依据；模型检索不读取已失效内容。外部同步失败时保留 failed，不能声称 Wiki 已清理。
- 列表当前在内存中按稳定时间+ID排序和签名游标分页，适用于个人 MVP；长时间观察量大时需改数据库键集分页。仅运行一个后端实例；重启恢复暂不支持多副本租约。
- 仅桌面 Delivery 排队和 ack；`sent` 只证明通知 API 接受。真实屏幕采集、通知可见、手机与真机验收由对应角色/用户提供证据。
- `/health` 只报告应用可用，不返回密钥；异常与上游失败不返回原始响应或敏感正文。数据库直连避免桌面系统 SOCKS 代理误接管 JDBC，不改变系统网络配置。
