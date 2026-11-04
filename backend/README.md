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
- `Model/Evidence`：最多 8 段来源（每段 6000 字）、最多 16 条相关/全局活跃记忆和最近 12 条用户/助手对话（单条 6000 字、合计 24000 字）；对话作为不可信历史帮助理解连续追问，不能成为引用来源。来源与系统指令分离。引用校验 source 所属问题、实际原文和提供给模型的片段。模型无副作用工具；显式本人决定候选经服务端原句与顺序校验后才存档。无效引用删除并提示；并非证明回答每项断言成立。
- `SafeFetch`：仅标准端口 HTTP(S)，每跳和实际连接 DNS 验证拒绝私网/本地地址；重定向、体积和超时有上限。读取失败 job 明确失败，用户可粘贴正文。暂不处理浏览器登录网页或视频。
- `Wiki`：按 topic UUID 固定页面路径，先查后建/更新，再回读全文比对；不将 HTTP 200 等同于同步成功。同问题资料合并至一个主题；模型提炼观点、适用条件、不确定性及核验引用，主题修订保留历史。摘要失败明确显示未完成并保留已保存来源。来源/记忆撤销时主题失效并排队覆盖 Wiki 内容。
- 记忆修正/删除让派生记忆、主题和议题失效。原决定和复盘保留历史依据；模型检索不读取已失效内容。外部同步失败时保留 failed，不能声称 Wiki 已清理。
- Flyway V3 给消息内部记录上下文版本，不改变公共 JSON。目标或记忆改变、来源删除后，只把同版本对话送入模型，旧消息仍供页面回看；修正前排队的聊天明确失败 `CONTEXT_CHANGED`，由本人重发。迁移前历史版本未知，保守地不重新送入模型。当前版本号全局共享，因此其他问题的修正也可能截断本问题的对话上下文；这是个人 MVP 的保守限制。
- 列表当前在内存中按稳定时间+ID排序和签名游标分页，适用于个人 MVP；长时间观察量大时需改数据库键集分页。仅运行一个后端实例；重启恢复暂不支持多副本租约。
- 仅桌面 Delivery 排队和 ack；`sent` 只证明通知 API 接受。真实屏幕采集、通知可见、手机与真机验收由对应角色/用户提供证据。
- `/health` 只报告应用可用，不返回密钥；异常与上游失败不返回原始响应或敏感正文。数据库直连避免桌面系统 SOCKS 代理误接管 JDBC，不改变系统网络配置。


## 一句话开启对话与失败恢复

`POST /api/v1/conversations` 只需 `{content}`（非空、最多12000字符），owner Bearer 与 Idempotency-Key，立即返回 `202 {problem,job}`。同一事务保存问题、完整首条用户消息和chat Job，队列满等同步失败全部回滚；同key同内容重放不重复创建。新入口的goal为空串、constraints/unknowns为空，不把困扰自动认定为目标或本人记忆。旧 `/problems` 仍要求非空title/goal。

初始名称截取首句最多40个Unicode码点；首答同一次模型调用可返回可选 `conversationTitle`（最多80字符）。缺失、类型不对、空白、过长或控制字符时忽略名称，不使正文失败。内部V4 `auto_title`默认false，旧数据不回填；仅新首句及其失败重试可命名。人工PATCH title关闭标记，自动命名不bump版本；人工修改仍保留原有上下文失效保护，可能使在途任务返回CONTEXT_CHANGED。

`POST /api/v1/jobs/{id}/retry` body严格为`{}`，同样需要owner及幂等键。仅失败chat可重试；复用原userMessageId，不重复用户气泡、不重置旧Job。每个失败Job通过内部retryOfJobId和唯一索引最多派生一个子任务；不同key、并发或大小写UUID路径都复用它，即使它已失败。再次尝试须针对新的失败Job。没有子任务时，原消息已删除或上下文已变更返回409 CONTEXT_CHANGED；已有子任务仅返回，不启动新工作，worker执行时仍校验上下文。非failed/nonchat返回409 JOB_NOT_RETRYABLE。

模型提示改为先给贴合困扰的轻量帮助，默认2–4短句，最多一个必要追问，不要求先填表或上传资料；无来源允许一般建议及空引用。不自动写目标、约束或未知；当前原话中的稳定本人事实与明确决定可按下述约束自动存档。接口/事务由隔离测试验证，实际首答质量必须另做真实模型与UI验收。

## 显式重新分析与模型诊断

`POST /api/v1/problems/{id}/analyze`，body `{}`，owner Bearer + `Idempotency-Key`，返回202 analysis Job。已有queued/running分析时复用任务；否则创建新任务并读取当前来源/记忆，不复制Source或用户Message，旧失败Job保留。缺少来源返回400/NO_SOURCES。此接口由E统一补入共享契约，用于网页“重新分析资料”。

模型请求显式 `thinking:{type:"disabled"}`，保留4000 token上限并提供合法JSON示例与输出规模限制；无自动循环重试。分别返回MODEL_TRUNCATED、MODEL_EMPTY、MODEL_INVALID_JSON、MODEL_SCHEMA、MODEL_INCOMPLETE，均可由本人明确重试。Flyway V2只新增problem/operation、结束原因、内容类型/长度、reasoning长度及usage等诊断元信息，不保存模型原文、reasoning内容、完整prompt或key；失败请求实际usage不再丢失。旧记录没有这些元信息，不能倒推历史失败原因。

重试单个已知合成问题并真实回读Wiki：`python3 tools/live_smoke.py /path/to/ignored/.env http://127.0.0.1:8088 --reanalyze PROBLEM_UUID`。不会另起服务，不重复创建来源/消息/主题。

官方依据：[JSON Output](https://api-docs.deepseek.com/guides/json_mode/)、[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)。


## 对话附件与思考档位

聊天创建/发送接受 `attachments:[{name,dataUrl}]` 和 `thinking:off|low|high|max`（默认off）；允许只有附件。每次最多4个、每个最多8MiB、总16MiB；聊天HTTP体24MiB，其余接口1MiB。PNG/JPEG检查真实格式、边长4096/1200万像素上限；文档支持PDF、DOCX、XLSX和UTF-8 TXT/MD/CSV。文件字节仅存在独立PG bytea表，消息/Job只含ID和元信息；幂等保存与原话/Job同事务，队列满整体回滚。

解析在worker锁外的子Java进程，heap256MiB、直接内存32MiB、20秒超时强制结束、清环境、私有临时目录清理；这是资源隔离，不是OS文件系统/网络沙箱。依赖PDFBox3.0.8和POI6.0.0；禁止宏、嵌入对象、外部关系/实体，ZIP限2048项、单项8MiB、展开总32MiB及膨胀比，电子表格只读取公式缓存、不执行公式。子进程不接收模型密钥、文件名不作命令参数。

单文档文本最多32000字符；PDF最多前20页文字、低文字/扫描页最多3页图像；DOCX正文/表格、XLSX最多20工作表和5000行。未读插图/页眉等、截断、扫描页范围写入coverage并以partial明确提示，不把文件保存成功当全文已读。加密/损坏文件可回读原件但chat失败，现有重试复用附件和思考档位；上下文失效/服务重启会结束pending/processing状态。owner认证GET `/attachments/{id}` 返回原件，device403，no-store/nosniff及安全下载名，禁止token进URL。

模型收到真正的user `image_url`块，不把图像base64藏进文本JSON。附件均是不可信对话材料，不伪造Source引用；同问题同上下文最近12条消息的附件可供追问，当前附件优先且不能静默省略。历史超预算明确提示，文本总128000字符、图片最多16块、图像编码36MiB，总payload上限40MiB。

| thinking | API thinking | reasoning_effort | max_tokens | 总超时 |
|---|---|---|---:|---:|
| off | disabled | 不传 | 4000 | 60秒 |
| low | enabled | low | 8000 | 120秒 |
| high | enabled | high | 16000 | 180秒 |
| max | enabled | max | 32000 | 240秒 |

思考响应体上限2MiB（off仍1MiB）；仅保存reasoning长度，不存/返回推理原文。档位和附件重试原样复用，超时/输出预算失败明确保留原话。

官方依据：[DeepSeek图像](https://api-docs.deepseek.com/guides/vision/)、[思考档位](https://api-docs.deepseek.com/guides/thinking_mode/)、[PDFBox](https://pdfbox.apache.org/download.cgi)、[POI](https://poi.apache.org/download.cgi)。合成HTTP夹具只能验证请求格式，真实图片/文档理解与各档位体验由E统一记录，不能当作已实测。

## 对话自动记忆与决定

借鉴 [Mem0 v1.0.0 方法](docs/mem0-attribution.md)，在现有 Java/PostgreSQL 内实现候选提取、词法检索与 ADD/UPDATE/DELETE/NONE 协调；不引入 SDK、向量服务或额外模型调用。模型同次 chat 响应可提供 memoryActions（最多5项）与 decisionCandidate，analysis 不执行这些写入。

自动记忆绑定当前 userMessageId 与完整原句，只接受本人陈述；引述、第三人、附件、假设、合成描述不作为本人事实。stable global 跨问题检索，临时事实仅 problem；列表带 scope/origin/sourceMessageId/sourceQuote。相关度为中文二元组和英文词，最多12条相关/本问题与4条稳定全局补充，不是向量检索。UPDATE/DELETE 仅作用于本次检索的自动记忆，要求 expectedRevision 和明确纠正/遗忘意图，可用完整旧事实或明确且唯一的主题对象绑定；所有可访问活动记忆参与歧义检查，模糊代词/泛谓词不猜测。主题由明确选购/关于等句法识别并在更新后保留，保守规则可能漏记复杂口语。

V6 内部来源序号、幂等账本与墓碑防止旧任务复活已忘记内容；手工修改的内容改为 origin=manual，并移除旧自动引语，保留纠正后的 user_statement 依据。自动写入不 bump 全局上下文；更新/遗忘增加独立 memory epoch，使在途旧快照失效并过滤旧对话。当前纠正轮 user/assistant 保存新 epoch，因此紧接着的追问仍可承接；新显式陈述可以重新学习。历史与备份保留，不表示物理删除。

决定仅接受当前原话明确选择/承诺，完整保留否定与选择对象，不把建议、考虑、提问、能力或他人话语当决定。理由/预期未在原话明确给出则为空。V7 约束每条用户消息最多一个自动决定，删除后旧重试也不重建；更改决定须明确表达改变并指向同问题当前自动决定，旧条标 superseded，手工决定不被自动覆盖。只有实际保存后助手消息才带 memoryChanges / decisionRecord；不以模型口头“已记录”代替成功证据。

以上机制由隔离数据库与 HTTP 替身测试覆盖，真实模型是否正确提取候选、页面显示及跨轮体验由 E 统一部署后验收，不因单元测试通过而宣称实际完成。

## 记忆概览（展示派生结果）

owner `GET /memories/overview?problemId=` 只读返回当前指纹与 empty/missing/pending/ready/failed、items及coverage。无problemId取全部active user_reported，有problemId取global和本问题；推断、观察、撤回记录仅在原列表查看。默认global优先，每次最多100条/32000字符完整记忆输入，不截半句；coverage披露实际输入范围，最多5条、每条80字符的归并摘要不承诺覆盖所有记录。每项绑定原记忆ID/revision及scope/problemId，不能混合不同作用域，原话与管理操作仍走原接口。

`POST /memories/overview {fingerprint,problemId?,retry?:true}` 需幂等键，统一202仅返回任务元信息。指纹过期返回409 OVERVIEW_CHANGED；空集不建任务，已有在途/成功/失败复用，失败同指纹不自动重试。仅显式retry且错误可重试时重新生成；新幂等键的并发请求也复用在途任务。旧幂等响应不含摘要，且请求在重放前仍校验当前指纹。

独立memory_overview Job在现有Job结果保存缓存，无新表/环境项；只读投影不写回事实、不改聊天检索。指纹包括全部eligible记忆内容/版本/作用域及模型/模式/摘要版本，不仅是输入预算内的部分。生成提交前和GET均校验当前指纹，纠正/遗忘后旧摘要不可返回，GET jobs仅返回指纹元信息不返回items。模型调用在事务锁外，off/2500输出token/30秒总超时，沿用调用用量记录；排队概览优先级低于正常任务，已有模型请求不抢占。模型摘要语义仍需真实验收，引用与版本校验不能证明任意概括都准确。

## 讨论后确认知识

独立 `knowledge_entry` / `knowledge_proposal` 保存于现有 records，V8 仅扩展 kind 约束与提案在途唯一索引，不回填旧 Topic，不自动升级旧资料。旧来源分析与 Wiki 同步不会覆盖正式知识。

本人点击 `POST /problems/{id}/knowledge-proposals {}` 后生成 `knowledge_proposal` Job，模型在锁外提出 create / merge / revise / skip。近期至多24条当前 context_revision / memory_epoch 对话文字、至多8条正式资料，以及跨对话相关性排序的至多12条已确认知识受各自字符预算约束；coverage 披露总数、实际条数、过滤/截断和不读取原始附件的范围。对照不等于完整冲突检测。引用区分 user/assistant 消息与正式 source，引用文字必须逐字来自真实提供内容；助手发言不是事实证明，附件不能冒充 source。

提案显示完整拟保存正文、理由、分类、目标版本和出处；继续讨论后可以重新整理。`accept {expectedTargetRevision}` 只接受服务端持久提案，事务内核对完整上下文指纹、目标 revision 和出处，保存旧版本、写知识并接受提案。不同幂等键再次确认也不会新建或重复修订；skip/已略过提案不能接受。对话、来源、记忆上下文或已有知识改变时返回 `409 KNOWLEDGE_CHANGED`，原提案保留。接受不 bump 全局聊天 revision，不使当前对话文字突然失效。

`GET /knowledge-entries`、详情与 `/history` 提供正式知识及历史；`GET /knowledge-proposals` 可按 problemId 筛选；`GET /knowledge-export` 在一致快照只导出正式知识、旧版本、领域与实际引用片段，供 E 的本机 Obsidian helper 增量处理。未确认提案、全部聊天、个人记忆、原件和密钥不导出。出处已不可用时保留确认时引用并明确标注；聊天检索不使用这类无效出处条目。正常聊天只读检索最多6条、每条至多3000字符知识及当前有效待审提案，不会自动确认入库。

没有新增环境变量或云服务；模型使用现有 DeepSeek 配置，单次 off / 6000输出token / 60秒，记录实际调用元信息。HTTP替身与隔离PG测试不代表真实模型、Obsidian文件同步或UI已验收；由 E 统一集成和验证。
