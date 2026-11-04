# 共享接口约定 v1

所有路径以 `/api/v1` 开头。E 为唯一修改者；A 负责实现并补齐机器可读 OpenAPI，B/C/D 不自行改路由或字段。本文是待实现约定，不代表 API 已存在。

## 通用约定

- JSON camelCase，ID 为 UUID 字符串，时间为 UTC RFC3339；客户端按用户时区显示。列表统一 `{"items":[],"nextCursor":null}`，`limit` 默认 30、上限 100，时间升序/倒序由下表指定，游标是不可解释字符串。
- 成功直接返回实体或列表；错误为 `{"error":{"code":"...","message":"可读原因","retryable":false},"requestId":"..."}`。HTTP 400/401/403/404/409/429/502/503 对应参数/身份/权限/不存在/冲突/限流/上游失败/未配置。
- 除 `/health` 和登录外，使用 `Authorization: Bearer <token>`。只有一个真实所有者，不做注册系统。登录 code 在服务端环境配置，前端由本人输入，禁止构建时内置。
- 所有创建 POST 请求都带 `Idempotency-Key` UUID。相同账号、路径和 key 重试不重复写入/推理，payload 不同返回 409。PATCH/DELETE 按操作语义幂等；模型失败可明确重新创建 job。
- 长任务统一 202 + Job；客户端轮询 `/jobs/{id}`，起始 2 秒、失败退避，完成后停止。后端使用持久化任务记录+有界 worker，不要求外部队列。进程重启必须让中断任务变成可解释的待重试/失败，不永远停在 running。
- 测试替身只在显式 mock 模式；响应 `mode: mock` 且页面有提示。真实交付环境默认不启用 mock。

## 共享实体

| 类型 | 字段（未标可选均必需） |
|---|---|
| Problem | `id,title,goal,constraints:string[],unknowns:string[],createdAt,updatedAt` |
| Source | `id,problemId,kind(article/observation/user_statement),title,text,originUrl:null|string,author:null|string,capturedAt,createdAt,status(ready/partial/failed),extractionMethod(user_input/http/ocr/accessibility),locator:object`；locator 仅存安全定位标识，不返回本地私密绝对路径 |
| Evidence | `sourceId,quote,relation(supports/contradicts/context)`；quote 必须是对应 source.text 的实际片段；个人记录与作者观点不会因引用变成客观事实 |
| Message | `id,problemId,role(user/assistant),content,evidence:Evidence[],uncertainties:string[],createdAt,mode(real/mock)`；用户消息的 evidence/uncertainties 可为空 |
| Job | `id,kind(chat/source_ingest/topic_sync/analysis),status(queued/running/succeeded/failed),result:null|object,error:null|Error,createdAt,updatedAt,mode(real/mock)`；结果按下表定义 |
| Memory | `id,problemId,content,kind(user_reported/observed/inferred),evidence:Evidence[],status(active/retracted),revision,createdAt,updatedAt`；推断不能静默改成 user_reported |
| Topic | `id,problemId,title,markdown,evidence:Evidence[],revision,wikiPageId:null|string,wikiUrl:null|string,syncStatus(pending_configuration/pending/synced/failed),updatedAt` |
| Discussion | `id,problemId,title,reason,evidence:Evidence[],status(unread/read/snoozed/dismissed),snoozeUntil:null|datetime,createdAt,updatedAt` |
| Decision | `id,problemId,choice,rationale,expectedOutcome,evidence:Evidence[],confirmedAt,reviewAt:null|datetime,revision,reviews:Review[]`；只由本人明确提交创建 |
| Review | `id,outcome,reflection,evidence:Evidence[],recordedAt`；追加记录，不覆盖原决定 |
| Device | `id,name,desiredState(paused/running),reportedState(paused/running/error/offline),lastHeartbeatAt:null|datetime,lastObservationAt:null|datetime,lastError:null|string` |
| Delivery | `id,discussionId,deviceId,channel(desktop),status(pending/sent/failed/user_confirmed),error:null|string,updatedAt`；sent 只表示系统通知接口接受，不表示本人看到 |

系统提示和外部文章/观察必须分离。资料中的指令不作为系统指令执行；模型无权决定调用发送消息、删除数据等有副作用工具。真实 key 不得进入模型输入。

## HTTP 路由

请求表中列出的字段都是请求体字段；问号表示可选。未列出的变更接口不自行增加。

| 方法与路径 | 请求／查询 | 成功响应／约定 |
|---|---|---|
| GET `/health` | 无 | `{status:"ok",version}`；不暴露配置和密钥 |
| POST `/auth/login` | `{accessCode}` | `{accessToken,expiresAt}`；失败限速；账号状态固定本人 |
| GET `/status` | 无 | `{model:{configured,lastCallStatus},wiki:{configured,status},observer:{lastSeenAt},usage:{requests,inputTokens,outputTokens,costEstimate:null|number,currency:null|string}}`；不返回任何 key |
| POST `/problems` | `{title,goal,constraints?:[],unknowns?:[]}` | 201 Problem |
| GET `/problems` | `cursor,limit` | Problem 列表，updatedAt 降序 |
| PATCH `/problems/{id}` | `{title?,goal?,constraints?,unknowns?}` | Problem；旧目标保留内部历史 |
| POST `/problems/{id}/analyze` | `{}`，owner token、Idempotency-Key | 202 analysis Job；本人显式重新分析当前资料与记忆，不重复建立 Source/Message；已有 queued/running analysis 时复用；成功 result 沿用 analysis；无来源时明确 400 |
| POST `/problems/{id}/messages` | `{content,sourceIds?:[]}` | 202 Job；先保存用户消息；完成 result=`{userMessageId,assistantMessageId}` |
| GET `/problems/{id}/messages` | `cursor,limit` | Message 列表，createdAt 升序；新消息 id 不重复 |
| GET `/jobs/{id}` | 无 | Job；仅本人或所属 device 可见 |
| POST `/sources` | `{problemId,kind:"article"|"user_statement",title?,text?,url?,author?}` | 202 Job；text/url 至少一项，若有 text 优先保存本人提供的文本；result=`{sourceId,topicIds:[]}`；Wiki 未配不阻塞 source 保存 |
| GET `/sources` | `problemId,cursor,limit` | Source 列表，createdAt 降序；正文可限制长度，完整正文走详情 |
| GET `/sources/{id}` | 无 | Source 完整详情，作为引用回查入口 |
| DELETE `/sources/{id}` | 无 | 204；停止作为检索证据，标记相关记忆/主题需更新，异步清理派生内容；不声称外部备份同步删除 |
| GET `/memories` | `problemId,cursor,limit` | Memory 列表，updatedAt 降序 |
| POST `/memories` | `{problemId,content,kind:"user_reported"}` | 201 Memory；同时建立 user_statement 来源，由服务端生成来源引用 |
| PATCH `/memories/{id}` | `{content?,status?,expectedRevision}` | Memory；版本不符 409；保留历史 |
| DELETE `/memories/{id}` | 无 | 204；当前检索不可再命中，关联摘要/缓存失效；备份清理范围单独说明 |
| GET `/topics` | `problemId,cursor,limit` | Topic 列表，updatedAt 降序 |
| POST `/topics/{id}/sync` | `{}` | 202 Job，成功 result=`{topicId,wikiUrl}`；未配置返回明确 503 |
| GET `/discussions` | `problemId?,status?,cursor,limit` | Discussion 列表，createdAt 降序；不要求每份资料都生成议题 |
| PATCH `/discussions/{id}` | `{status,snoozeUntil?}` | Discussion；snoozed 必须有将来时间；dismissed 不再重复推同一依据 |
| POST `/decisions` | `{problemId,choice,rationale,expectedOutcome,evidence?:[],reviewAt?}` | 201 Decision；UI 明确“确认记录决定”，不由聊天 job 自动调用 |
| GET `/decisions` | `problemId?,cursor,limit` | Decision 列表，confirmedAt 降序 |
| POST `/decisions/{id}/reviews` | `{outcome,reflection,evidence?:[]}` | 201 Review；原决定不变 |
| POST `/devices` | `{name}`，owner token | 201 `{device:Device,deviceToken}`；token 仅返回一次，存本机；device token 不能读取全量个人资料或做管理操作 |
| GET `/devices` | owner token | Device 列表 |
| PATCH `/devices/{id}` | `{desiredState:"paused"|"running"}`，owner token | Device；启动须本人显式操作；暂停状态优先于待上传数据 |
| POST `/devices/{id}/heartbeat` | `{reportedState,lastObservationAt?,lastError?}`，本 device token | `{device:Device}`；超过 90 秒无心跳可显示 offline，不假定继续观察 |
| POST `/observations/batch` | `{deviceId,items:[{externalId,problemId,text,appName,capturedAt,extractionMethod,sourceLocator,contentHash}]}`，device token | `{accepted:[externalId],duplicates:[externalId],rejected:[{externalId,code,message}]}`；一批最多 50 条，每条 text 最多 20000 字符；device+externalId 唯一；无 screenshot 字段 |
| GET `/devices/{id}/deliveries` | 本 device token，`cursor,limit` | `{items:[{delivery:Delivery,discussion:{id,title,reason}}],nextCursor}`，createdAt 升序；只给待交付/重试消息，不开放完整 Sources |
| POST `/deliveries/{id}/ack` | `{status:"sent"|"failed",error?}`，所属 device token | Delivery；重试幂等；用户看到与否留给 UI 或用户试用确认 |

GET 列表默认只返回本人数据。problemId、deviceId、sourceId 不能仅因格式正确就信任归属。登录 token 到期后客户端回到登录，不无限重试模型请求。

## 观察启停握手（A/B/C 共用）

- device 注册后 desiredState/reportedState 都为 paused。本地适配器默认 `localConsent=false`。仅当本人执行本地 run、且服务端 desiredState=running 时才能采集；远程 running 不得自行取得本地同意。
- 本地 pause/stop 将 localConsent 设为 false，优先于远程状态。再次启用必须本人在本地 run；远程改成 running 不能越过本地暂停。
- 心跳每 10 秒发送一次，返回最新 desiredState；服务端 paused 到达后立即停止采集和上传，并将 localConsent 清除。连接失败即暂停本次采集，最多一个心跳周期内检测；恢复连接不能自动恢复 localConsent。
- `/observations/batch` 在服务端 paused 时拒绝新观察（rejected code=`DEVICE_PAUSED`），对暂停前重试也不继续云分析。C 丢弃暂停/断线期间的新观察，不补采。
- 当前 problemId、deviceId、token 放在 observer 本机忽略的配置目录，由本人选择问题。run 必须有明确 problemId；A 不根据应用名猜测问题归属。C 可提供 `run --problem-id UUID` 等参数并记录最终命令。
- 显式运行前的断线队列只保存已经允许采集的片段；一旦本人暂停则停止上传，只有再次明确运行且本人选择恢复旧队列时才续传。
- 控制 Pensieve 录屏与停止同步必须分别验证；若仅能控制同步，B/C 必须显示“同步已停，底层采集状态未确认”，不能显示“观察已停止”。

## 后端内部最小流程

1. Source 保存与引用定位先成立，再做摘要/关联；读取失败不能出现伪造的 ready 记录。URL 抓取拒绝 loopback、内网、link-local 及重定向后的私网地址，限制响应体与超时。
2. 资料、观察是“不可信内容”，以数据方式进入模型。每次只取当前问题、相关记忆、有限的来源片段和近期对话；不把全部屏幕历史反复发送。
3. 模型回答和议题必须能映射回提供给它的 source IDs；无效引用不展示成证据。推断标为推断，允许回答资料不足。
4. 新来源入库后触发去重的 analysis job；同问题有运行中的分析则合并待处理来源，不逐帧调用模型。分析允许结果“无需打扰”。
5. 议题先保存，才生成 Delivery；通知接口失败不丢议题。snoozed/dismissed 控制重发，不能只靠 prompt。
6. Topic 本地生成后再异步同步 Wiki；版本、页面ID与内容 hash 可定位，不盲目创建重复页面。Wiki 返回成功需读取实际页面验证一次。
7. API 不提供自动发送私人消息、自动求职、自动修改 TGA 的能力。

## 数据存储边界

A 至少建立 problems、sources、messages、jobs、memories（含 revision history）、topics、discussions、decisions、reviews、devices、deliveries、idempotency_keys、model_calls。可合理合并物理表，但外部语义不变。数据库迁移只在 `backend/`，不得让多个模块各建一份个人记忆。

保存 DeepSeek 返回的 usage 和 model ID，费用仅按实际可获得的计价数据估算并标记 estimate；无依据时为 null。禁止日志输出 key、登录 code、完整提示词和真实文章正文。

## 模块运行契约

- Backend：端口默认 8080，`backend/mvnw`（A 生成 Maven wrapper）；`backend/Dockerfile` 由 A 提供，E Compose 引用。
- Web：开发端口默认 5173，开发 `/api` 代理到 8080；生产 API 同源由 E 反向代理。`npm ci && npm run build` 在 web 内执行；B 提供 web/Dockerfile 或清晰的静态产物路径给 E。
- Observer：Python 3.11+ 独立环境；C 给出 `python -m decision_observer status|run|pause|stop` 实际命令，不能将服务与默认录屏自启绑定。默认只连 loopback Pensieve，不暴露其原始历史 API 到公网。
- Mini：D 使用 `API_BASE_URL` 的本地配置入口，不内置 token、owner code 或 DeepSeek key。真机连接需实际可达的后端和相应平台条件，localhost 不代表手机能访问。
- E 维护根 `.env.example` 与 Compose；模块新增必要配置写入回执，由 E 合并。
- 2026-11-04 集成补充：`WIKI_BASE_URL` 仅用于服务端请求；`WIKI_PUBLIC_URL` 用于返回给客户端的页面链接，未设置时回退 BASE。容器内地址不能暴露为浏览器链接。API JSON 字段不变。
