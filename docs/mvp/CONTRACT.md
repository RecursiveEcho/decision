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
| POST `/conversations` | `{content}`（非空，最多12000字符），owner token、Idempotency-Key | 202 `{problem:Problem,job:Job}`；原子创建对话、首条user消息和chat Job；同key同payload返回同problem/job，不增加消息；初始goal为空串，constraints/unknowns为空，title安全截取首句兜底 |
| POST `/jobs/{id}/retry` | `{}`，owner token、Idempotency-Key | 202 chat Job；仅failed chat可重试，复用原userMessageId，不重复用户消息；每个失败Job最多派生一个retry Job，重复请求返回同一个。已派生retry失败后客户端针对新failed Job重试；非failed/nonchat返回409 JOB_NOT_RETRYABLE，新派生时原消息已失效/删除返回409 CONTEXT_CHANGED；已有派生只返回历史Job，不重新生成 |
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


## 2026-11-04 对话优先入口（新用户目标）

- 网页新用户、空列表和“新对话”默认直接输入困扰。标题/目标/限制/未知不是发送前置条件；旧`POST /problems`继续保持原请求校验兼容旧客户端。
- `/conversations`不调用模型后再建档：先在同一事务保存Problem+user Message+chat Job，立即202；Problem仍是旧实体，其中goal允许在新入口为空。不得把整段吐槽擅自当作已确认目标或本人记忆。
- 首个chat Job与普通chat走同一模型调用；首答内部可选`conversationTitle`仅用于自动显示名称，空白/非字符串/过长等异常不能使正文失败。仅首个新对话Job（包括其显式失败重试）可更新自动标题；标题更新不bump上下文版本，不覆盖随后本人手改的标题。旧对话不被重命名。
- 聊天提示：先给简短、贴合困扰的帮助或能做的小步骤；确需澄清时至多一个关键问题，允许用户先说不清；不要求填标题/目标/限制表或先上传资料。AI能代写、归纳、比较、拆解的，先给可用草稿/框架/初步判断，不把列清单、写提纲等可代劳工作再布置给用户；缺关键内容只问能推进的一项事实，不编造缺失的真实材料。代拟事实只能来自用户或当前来源明确给定内容，未知省略或原位待确认；建议和推断在出现处标明，不能靠末尾不确定性抵消正文断言。普通疲惫且无明确任务时，2至3个短句、最多一个易答问题，不要求三行/清单/分类整理。可以给明确标为一般建议的推理，不能编造来源/用户目标/最新外部事实，不把推断写成user_reported。无来源允许evidence=[]。
- 本轮整理在对话正文自然呈现；不自动写回goal/constraints/unknowns或本人记忆，不因此新增事实判定机制。可选信息与既有工具折叠保留，不能阻挡开聊。
- 前端需区分未确定HTTP结果、已创建且Job在途、Job失败。首次发送稳定保存同一content/key快照，跨点击重试同payload同key；已拿到202后复用同problem和Job，不重POST创建。刷新可恢复已知Job轮询；退出清除本轮暂存。模型失败用“重试回答”调用新retry接口，正常重试不复制用户气泡；上下文已变更时明确保留原话供用户调整，不能让旧事实复活。
- 202外层结构在首句返回后即用于选择问题/展示首条用户话；Job完成后刷新problems+messages获取自动标题和回答。所有写入仍只由本人点击发送/重试或既有显式操作触发，不自动确认决定/发通知/开采集。


## 2026-11-04 图片、文档与思考等级（本轮固定契约）

用户明确要求图片输入、文件上传和思考等级。下列为新增可选字段，旧纯文字请求继续兼容；是否部署以PROJECT_STATE为准。

- `POST /conversations`、`POST /problems/{id}/messages`：`{content,attachments?:[{name,dataUrl}],thinking?:"off"|"low"|"high"|"max"}`。content允许空，文字或至少一个附件必需；文字仍最多12000字符。已有sourceIds逻辑不变。
- 附件最多4个，原件每个最多8 MiB、合计16 MiB；请求体上限24 MiB。客户端/服务端均限制，服务端不能只依赖Nginx。其他后端请求仍1 MiB上限。
- 图片wire格式PNG/JPEG；客户端接受WebP时先规范化为PNG/JPEG。实际图片内容校验，边长最多4096、总像素最多12M。文档支持PDF/DOCX/XLSX/TXT/MD/CSV，实际格式与名称共同核实，不执行宏、公式、外部实体或链接。
- 选择/粘贴/拖入仅在本地预览，显式发送才上传。原件PG bytea独立保存并随消息/Job同事务提交，不添加独立临时上传流程。幂等hash包含原件、名称、顺序及thinking，重放不复制消息/附件。
- `Message.attachments?` 返回元信息数组：`{id,name,mimeType,size,kind:"image"|"document",width?,height?,status:"pending"|"processing"|"ready"|"partial"|"failed",error:null|{code,message,retryable},coverage:{pages?,processedPages?,truncated:boolean,notice?:string}}`。旧消息可缺省。`Message.thinking`为四枚举之一，旧消息默认off；助手消息可回查该次档位。
- `GET /attachments/{id}`：owner认证，返回原文件二进制及正确Content-Type、nosniff、Cache-Control:no-store；文件使用安全Content-Disposition，不允许device读取，不把token放URL。前端认证fetch后Blob URL展示/下载，退出回收。
- 消息GET动态附加最新解析状态；pending/processing的`coverage.truncated=false`只代表尚未处理，不是已完整读完。解析失败使当前chat Job失败，保留原件/消息；retry复用附件ID和档位。坏格式、加密、超安全限等error.retryable=false，进程超时等true，不无限自动重试。
- 文档解析在Worker与数据库锁外执行，采用可销毁的受限子进程、256 MiB heap、20s墙钟与临时文件清理。PDF最多前20页文字、其中至多3页需视觉的扫描页；每文档最多32000字符。PDF混合文字与扫描页按页处理。DOCX正文/表格、XLSX工作表/单元格可读，不承诺完整图表或嵌入图理解。截断用partial+coverage.notice同时告知模型和用户，不静默冒充全文。
- 附件作为不可信对话输入，图片使用真实`image_url`内容块；文件文字附名称/页码或工作表定位。当前附件优先且不得静默丢弃，历史限本问题同revision最近12消息并有大小预算；省略旧附件须有说明。模型完整payload控制在40 MiB内。附件不会自行转换成来源/知识主题或用户事实，不能伪造文本Source.quote。
- UI档位：快速off、轻度low、深度high、最高max，默认off。off=`thinking.disabled`；其余=`thinking.enabled`+`reasoning_effort`对应值。最大输出/超时分别为4000/60s、8000/120s、16000/180s、32000/240s。保留实际用量/推理长度，不保存或回传完整reasoning_content。前端可等待360s，仍running时继续查询而非重发。
- 草稿与待发送原件使用登录会话localId隔离的IndexedDB；完整不可变快照及幂等key持久化成功才POST，存储失败不发。未知HTTP结果重用原bytes/顺序/档位/key；已获202复用原Job查询。退出清理会话附件与Blob URL，晚到响应不得恢复到新登录态。

## 2026-11-04 自动记忆与明确决定（后续用户授权，覆盖旧禁止自动记录条款）

本节只覆盖旧文中聊天不得写记忆/决定及手填确认前置的限制；不改变采集默认关闭、禁止自动通知、不得替用户作决定等边界。

- 借鉴 Mem0 v1.0.0 的用户事实提取、检索及 ADD/UPDATE/DELETE/NONE 更新方法，使用现有 Java/PostgreSQL 与当前聊天模型调用实现，不代表集成 Mem0 SDK。新原话中明确的稳定个人事实/偏好可自动记为 global，临时条件限定 problem。旧记忆默认 problem，不批量升级全局。
- 模型内部可选 `memoryActions:[{action:"ADD"|"UPDATE"|"DELETE"|"NONE",content?,scope?:"global"|"problem",targetId?,expectedRevision?,sourceQuote}]`，最多5项。服务端绑定当前 userMessageId；sourceQuote 必须为当前用户原始文本连续片段。只记本人实际陈述，排除假设、转述、第三人、引用、附件及助手生成的个人事实，kind 保持 user_reported。
- UPDATE/DELETE 只可针对当前检索提供的自动记忆 ID 与 revision，不自动覆盖旧手填记录。以消息/action去重，保存墓碑与来源顺序；旧 Job/重试不能复活已忘记内容，只有后续用户重新明确陈述才能成为新版本。最终写入与回答保存同事务，自动记忆更新不 bump 全局对话 revision 或导致当前助手回答失效。
- `Memory` 增加 `scope,origin:"chat",sourceMessageId,sourceQuote`，保留 id/content/status/revision/problemId。`GET /memories` 无 problemId 返回全部；指定 problemId 返回本问题与 global。`PATCH/DELETE /memories/{id}` 继续允许本人纠正/忘记，须防在途旧 Job 复活。
- 检索 active 本问题与 global：中文二元组、英文词做本地相关性排序，再考虑近期，相关项最多12，加少量稳定全局候选总计不超过16。是词法检索，不宣称向量语义召回。
- 助手 `Message.memoryChanges?=[{action:"ADD"|"UPDATE"|"DELETE",memoryId,content,scope,revision}]` 仅包含实际写入成功项；无效候选不阻塞正常回复，不显示成功提示。前端只需小型可展开状态与出处；记忆页用于查看、纠正、忘记，不要求事先填写。
- 模型内部可选 `decisionCandidate:{choice,quote,rationale?,expectedOutcome?,supersedesId?}`。quote 必须是当前用户原话连续片段，choice 是 quote 连续片段；只记录明确选择/承诺，不记录疑问、犹豫、假设、第三人或助手建议。未明确给出的理由和预期留空。
- 自动 Decision 新增 `origin:"chat",userMessageId,quote,status:"active"|"superseded",supersedesId?`。唯一 userMessageId，并发/重试去重；仅可替代同问题旧自动决定，不改旧手填决定。旧 Job 不得覆盖更新的选择。
- 助手 `Message.decisionRecord?` 返回实际持久化的 Decision。前端可简短显示“已记录”，不增加必须手填的决定表。自动保存只整理用户已表达的事实和选择，不代做选择。
