# Decision 产品阶段 1–2 实施契约

2026-11-13，E总控。用户已授权前两阶段完整实施、隔离测试后交付；统一记忆仅预留接口，真实数据不迁移。基线 d64c50c。本文件为本轮对旧 CONTRACT 的增量覆盖；未提及的账号/来源/知识/附件安全规则保留。

## 范围与角色

- A：backend/ 与 handoffs/A.md，独立产品后端工作区。
- B：web/ 与 handoffs/B.md，独立产品网页工作区。
- E：desktop/、docs/、scripts/、集成与验收；复用桌面工作区。
- 本轮不接真实共享中心、不启动观察、不部署TGA、不导入真实数据库。已安装Preview可能已有用户数据，不能用作合成测试环境。

## 模型设置

账号作用域，不接受客户端指定account ID。凭据只写入服务端加密存储（使用既有Auth加密和受保护的部署密钥；桌面密钥来自Keychain），永不GET返回，不存浏览器localStorage。既有部署配置只作为管理员兼容fallback，普通成员不得继承管理员模型Key。

- `GET /settings/model` → `{provider:"deepseek"|"openai",model:string,configured:boolean,providers:{deepseek:{configured:boolean},openai:{configured:boolean}}}`。
- `PUT /settings/model` 请求 `{provider,model,apiKey?:string}` → 同上。不带apiKey保留该提供商原key，非空替换；清除使用 `DELETE /settings/model/key?provider=`，返回同上。修改只影响下一轮，已开始的请求使用自己的配置快照。
- `POST /settings/model/test` 请求 `{provider,model,apiKey?:string}`，标准幂等键；实际最小推理验证，成功 `{ok:true,provider,model,verifiedAt}`，失败标准Error。不会悄悄保存输入Key，不假称列出模型等于可调用。
- `GET /settings/models?provider=` → `{items:[{id:string}],source:"provider"|"configured",notice?:string}`；优先使用该账号的提供商模型目录，失败可保留当前配置并明示。允许用户输入合法模型ID，不凭静态列表保证能力。
- 消息POST增量字段 `provider?:"deepseek"|"openai", model?:string`；均省略用账号默认，model单独覆盖当前默认provider。Job/助手消息回显实际provider/model，不回显key。
- 仅官方提供商URL；现有测试环境/旧APP_MODEL_BASE兼容由后端明确限制，不开放任意URL带着Key请求。OpenAI/DeepSeek的思考参数分别适配，不将DeepSeek参数盲传OpenAI。不支持的档位给可理解错误或明确映射。

## 会话组织与搜索

- Problem新增 `pinned:boolean`（旧默认false）、`group:string`（旧默认空，最长60字）。`PATCH /problems/{id}`接受这两字段，不改变事实上下文版本。
- `GET /conversations/search?q=&cursor=&limit=30` → `{items:[{problem:Problem,messageId:string|null,snippet:string,createdAt:string}],nextCursor:string|null}`。按当前账号搜索标题/消息正文，包括归档并由problem.status标识；不返回已删内容，片段纯文本，q最长200。标题命中messageId=null。游标稳定绑定查询。
- 原有problems列表API兼容。B逐页加载、明确“更多”，避免首次拉完全部历史；固定/分组可先对已加载条目组织，不能误称全量搜索。

## 运行状态与增量输出

- 保留Job ID作为run ID；Job新增 `problemId`、`partialContent`（默认空）、`stage`（可选）、`provider/model`（可选）。status增加 `cancelled`。终态 succeeded/failed/cancelled。
- `GET /jobs?kind=chat&status=active&cursor=&limit=100` → 现有分页形状；active仅queued/running。用于重开后发现服务端在途任务。必须按账号过滤。
- `GET /jobs/{id}/events?after=0` → `{items:[{id:number,type:"stage"|"delta"|"terminal",data:object}],nextCursor:number,job:Job}`。递增id按Job唯一；after为上次nextCursor。分页/截断时保证可继续取，job.partialContent是完整可恢复快照，客户端不得将快照与delta重复相加。
- stage data `{stage:string,label:string}`，只反映真实步骤；delta data `{text:string}`只含可展示正文；terminal data `{status:string}`。这些必须来自真实上游增量，禁止整答假打字。可用短间隔认证HTTP轮询运输持久事件，断线不取消、不重发消息。先保留轮询兼容，避免前端EventSource把token放URL。
- 上游流式JSON如果保留结构化响应，增量仅解码content字段；末尾完整校验成功后才提交引用、记忆、决定和知识操作。停止/失败的partialContent只是未完成答复，不作为事实。
- `POST /jobs/{id}/cancel`，`{}`+幂等键 → 200 Job。queued/running可取消；终态返回当前状态不伪称取消成功。必须中止/关闭上游等待，服务端终态和副作用提交在同一串行化边界竞争。保留partialContent，停止后不得迟到写入助手终答、记忆、决定、知识。
- 失败原retry API保留；cancelled不自动重试，用户可明确重新发送或继续。删除会话取消自有Job并拒绝事件回读，保留既有账号隔离。
- 如果正常完成与副作用已经提交，cancel返回succeeded，不回滚已成功记录；如果cancel先提交，后续结果不得再写入。进程重启将running标为failed/PROCESS_RESTARTED，保留可回读的partial和事件，不自动重放模型。
- 同一会话最多一个已接受在途chat；新POST若冲突返回409 `CHAT_IN_PROGRESS`，不会留下新消息/附件。不同会话允许创建Job，worker可维持有界串行处理，显示queued。

## 排队与恢复

- B按会话管理pending，运行期间允许切换、新建、编辑输入和其他草稿。
- 同会话后续输入进入可见的**本机待发送队列**，持久化文本、附件快照、模型/思考和幂等键；可以取消。前一轮成功后再POST下一条，保证上下文包含前答；失败/取消暂停队列，由用户明确继续。
- 关闭应用后未发送队列暂停，重开登录后恢复并清楚显示，不能伪称后台已发送。已发送Job从服务器恢复，不因重开复制用户消息。
- 草稿/队列/最后会话按稳定账号ID隔离，临时请求使用session epoch抵御旧回包；附件IndexedDB同样按稳定账号隔离。明确退出可清理本人设备草稿并告知；token过期不得自动删除未发送草稿。
- 登录凭据仍不落localStorage。跨退出可先安全重登后恢复本人草稿；如实现记住登录只用Keychain，不能为方便降级成localStorage token。

## 统一设置与桌面桥接

- B统一设置：通用（发送键、恢复工作）、外观（现有主题+字号/密度）、模型（真实API）、快捷键（可发现）、记忆（当前本地/中心未连接的真实状态）、关于（版本/服务状态）。不塞无效开关。
- 阶段3/4的权限工具、中心连接管理、定时提醒不在本轮冒充可用。
- E原生菜单“设置…”+Cmd-, 导航业务窗口 `/?settings=1`，B读取参数打开同一设置中心；恢复期间不清除草稿。故障时原生“运行状态”窗口保留自救入口，旧Key配置改为兼容说明/引导，不与新设置冲突。
- B可通过只在桌面注入的受限桥接或受控链接调用运行诊断，具体桥接变更由E另发；网页正常使用不依赖Tauri权限。

## 记忆预留

- A抽出MemoryClient/MemoryGateway等效接口：本地实现继续保持现在的来源与epoch校验；中心实现不启用、无网络请求、无双写、无真实迁移。
- `GET /settings/memory` → `{mode:"local",central:{connected:false,enabled:false},notice:string}`，明确本轮仅预留接口。

## 测试要求

后端原回归+新设置账号隔离、搜索、真假流式、cancel竞态/断流/重试/重启、迟到副作用、附件与同会话冲突；前端原回归+逐会话切换、队列恢复/取消、草稿账号隔离、增量重连去重、设置/搜索/快捷键/滚动。E隔离HTTP/UI/真实模型/桌面打包与生命周期验证；替身与真实结果分别记录。
