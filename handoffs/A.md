# A 后端回执

状态：A 后端实现、自检与任务分支推送已完成；E 已集成代码并报告真实产品链路通过，正在完成整体验收。开始时间：2026-11-04 17:29:59 Asia/Shanghai；后端开发完成：2026-11-04 17:59 Asia/Shanghai。用户亲自验收尚未完成。

工作区 `decision-worktrees/backend`，分支 `feat/mvp-backend`，共同基线 `2fcea57`；独立只读审查已通过。本对话经用户明确分配为 A，仅修改 `backend/` 与 `handoffs/A.md`，不负责根目录或 E 集成。

## 当前可交付状态

- Java 17 / Spring Boot 3.5.16 / PostgreSQL / Flyway；契约全部路由、OpenAPI、Maven wrapper、非root Dockerfile、独立数据库测试脚本、真实服务 smoke 脚本已交付。
- 最新代码 `b36532d`（含 `d94bc84`、`1a3d9ed`、`a0224be`、`8377431`），已推送并被E集成到 `integration/mvp-20261104`；本文之后的提交仅整理回执。
- 必要验证：`backend/test.sh` 在独立一次性 PostgreSQL 上 **26项全部通过**；`mvnw -DskipTests package` 成功。测试报告在本地 `backend/target/surefire-reports`、`target/test-run.log`，不上传运行日志。
- A亲自执行的真实外部证据：后端→DeepSeek真实回答含5条逐字来源引用并回查通过；Wiki.js 2.5.317真实创建/同页更新/全文读回通过。只使用合成资料。
- E于17:58的协调状态报告：增强smoke已通过“两篇相关文章→真实主题摘要→同一Wiki页面更新→本人确认决定→追加复盘”，网页/后端已切Docker；仍在做容器复验与带业务数据备份恢复。此条是E报告，A未另起共享库worker复跑。
- 真实观察、桌面通知本人可见、手机通知、小程序真机、服务器部署和用户验收：**未由A验证**。采集从未由A开启；未改TGA。
- 公开仓库检查：暂存仅含本角色文件；已用根本地env实际敏感值检查所有已追踪A文件，无匹配；无key/token、真实文章/截图、DB、运行日志上传。

## 实现位置与验证覆盖

| 功能 | 实现位置（backend/下） | 状态与验证 |
|---|---|---|
| 登录、幂等、设备权限 | `Api.java`、`Auth.java`、`ApiFilter.java` | 完成；登录限流、权限隔离、重复提交/冲突、加密幂等响应、请求大小 |
| 持久化任务/修订 | `Store.java`、`Worker.java`、`db/migration/V1__decision.sql` | 完成；重启中断任务可解释失败，排队持久化，单worker/有界队列 |
| 资料、真实模型与证据 | `Domain.java`、`Model.java`、`Evidence.java`、`SafeFetch.java` | 完成；真实回答及引用回查、伪造/跨问题引用、SSRF、未配置/超时/大响应 |
| 文章主题与Wiki | `Worker.java`、`Wiki.java` | 完成；同问题主题合并/历史、观点/条件/未知、真实创建/更新/回读；HTTP/1.1与tags兼容 |
| 记忆/撤销/决定复盘 | `Domain.java` | 完成；修订冲突、删除后检索失效、撤销再激活、原理由不被复盘覆盖、模型不能自动确认 |
| 观察/议题/通知队列 | `Domain.java`、`Worker.java` | 完成；默认暂停、心跳/所属权限、批次去重、暂停拒收、无须提醒、稍后重新交付；仅合成输入验证 |
| 构建与联调 | `Dockerfile`、`test.sh`、`tools/live_smoke.py`、`openapi.json` | 完成；26项测试，JAR构建；E负责唯一业务后端与最终容器验收 |

上述功能均从17:29:59开始，至17:59完成本轮开发；阶段事实见下面保留的历史记录。

## 配置和仍适用的限制

- E维护根配置：`APP_OWNER_ACCESS_CODE`至少12字符，`APP_TOKEN_SECRET`至少32；DATABASE/DEEPSEEK变量见模块README。`WIKI_LOCALE=en`，`WIKI_PUBLIC_URL`供浏览器链接、`WIKI_BASE_URL`供服务端访问；真实模式默认，`APP_MOCK_MODE=false`。
- 运行见 `backend/README.md`。真实smoke：`python3 backend/tools/live_smoke.py /path/to/ignored/.env http://127.0.0.1:8080`。只创建合成记录并读取指定env，不打印敏感值。**E独占共享数据库worker，A不再启动同库服务。**
- 列表当前在内存排序/签名游标分页；适用个人MVP，观察规模增长后应改数据库键集分页。后端仅单实例，未实现多副本租约。
- 模型上下文有界，引用存在/原文一致不证明每项推断成立。主题只覆盖本次提供的有限来源。删除为当前检索撤销，原决定/历史/备份保留，外部清理异步状态明确。
- 未新增共享接口字段；观察定位与C已收敛frameId/resourceId不透明ID；所有公共配置由E统一集成。

以下是阶段历史，不覆盖上面的当前状态。

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

## G2 实际 Wiki 更新读回与构建修正（2026-11-04 17:55 Asia/Shanghai）

- `1a3d9ed` 已推送。最新补丁进一步修正实际 Wiki.js 2.5.317 更新兼容：GraphQL schema 中 tags 虽可选，实际 update 省略时报 `Cannot read properties of undefined (reading 'map')`；实测提供 `tags:[]` 后 succeeded=true。HTTP/1.1修复与此修复均纳入模拟HTTP回归。
- A 对真实独立 Wiki 实例用合成工程页面完成创建、同页两次更新与全文回读比对，页面ID保持2；无个人信息。这是 adapter 实际证据，不等于整条来源→真实模型主题→Wiki闭环已通过，后者由E统一服务运行增强smoke。
- `live_smoke.py` 已加强：验证真实模型摘要的观点/条件/未知/证据；再次投入相关文章，断言同Topic/同Wiki页面、revision增加、新来源引文有效，并独立调用实际Wiki API回读全文；最后明确确认决定并追加复盘。
- E随后证实首次 go-offline 404秒后成功，构建并非卡死；最终保留已成功的 go-offline 层缓存路径，不用新cache mount遮蔽现有依赖，package保留可见日志。固定E已拉取核验的两份基础镜像digest。A不重复启动Docker构建，后续镜像由E验证。
