# C 观察回执

<!-- C-HANDOFF-BEGIN C-OBS-20261104-1932 -->
## C 观察端接续交接（先读本节）

交接编号：`C-OBS-20261104-1932`，所属项目交接 `DECISION-MVP-20261104`。整理时间：2026-11-04 19:32 Asia/Shanghai。
状态：已接手（2026-11-04，独立只读核验通过，源聊天已正式交权，用户再次明确继续协作）。
源聊天：`01a1a5e1-5db8-7dc2-86f0-4f7262f6c8c7`。
接手聊天：`01a1a656-7103-7822-8a53-a359c7cb3cf2`。
修改权：由接手聊天维护 C；源聊天已交权，之后只观察、不再写入。

### 角色、目录和授权

- 本角色是 **C 观察端**，不是 E。实际工作区为主仓库旁的 `../decision-worktrees/observer`，分支 `feat/mvp-observer`；所有工程命令必须指定此 workdir。即使聊天归属 decision 主项目，也不得在主目录切分支或按 E 身份修改。
- 仅可修改 `observer/` 与 `handoffs/C.md`。已有开发、自检、提交和正常推送本任务分支授权；用户明确要求继续配合总控，有问题直接修复推送。公共接口、其他角色目录交 E 协调，不 force push、不自动合并 PR、不改 TGA、不部署到其服务器。
- 总控联系聊天：`01a1a5b5-b163-7803-9667-8e1d17bef002`；用户已明确授权通知总控和协作，本次也明确授权新建接续 C 聊天。若总控已迁移，先通过该聊天/最新项目资料确认接手者，不能凭同名聊天猜测。
- **用户反复明确：录屏仍然不要开。** 不启动 Pensieve serve/watch/record/enable/start、不读取历史/私聊、不调用真实观察 run、不设置自启。也不要为了“再验证”重复发系统通知或开启持续通知轮询。需要真实观察时，等待用户之后明确改变该限制。
- 上下文接近上限时提前写文档并新开对话，不主动以压缩代替交接；系统自动压缩不可保证关闭。旧新聊天不能同时改文件。

### 已完成与当前 Git

- C 功能 HEAD 与远端均为 `7df83265e681574b244ab7f5fe359087f0e2d1b5`；开始整理前工作区干净。本次只有交接文档编辑，后续文档提交以 Git 为准。
- 已实现默认暂停/双重同意、设备心跳、Pensieve OCR 文字适配、有限持久队列/内容去重、900k 字节批次、未知结果原 key 重试/明确拒绝新 key、UTF-16 安全截断、通知分页/退避/稍后提醒轮次、准确暂停状态。
- `configure --notifications-only` 只收 backendUrl/deviceId/deviceToken；notifications 不构造或访问 Pensieve；run 缺完整观察配置时，在清队列/心跳/同意之前拒绝。
- 重要提交：`ed11bae` 后端联调；`3c774b3` snooze/UTF-16/状态；`e7cf870` E 通知报告；`7df8326` 仅通知配置。
- `7df8326` 由总控 `ab6013d` 集成。此前已实时确认最终交付 `9b47542` 在 origin；本次总控继续处理用户反馈，远端 integration 查询为 `e07b18c`，本地当时为 `be11fc3` 且存在 E 自己的未提交文档。**总控仍在前进，这些是时间点快照，不可当作当前最终版本或要求回退。** C 修复包含关系已确认，新增 E 变更/推送由 E 负责。

### 证据分层

- C 自检：38 项 unittest + wheel/sdist 构建通过。
- 隔离真实后端：15 项 Java/PostgreSQL HTTP 合成联调通过；无外部 key、worker 关闭，通知函数为替身。覆盖暂停/权限/去重/hash/locator/批次/emoji/通知分页退避/snooze；临时服务清理、设备最终 paused。
- E 报告并写入其回执：真实模型生成合成议题，默认 osascript 接受 1 条通知，本地 sent、后端 pending 1→0。E 还用无 Pensieve 字段的最小配置运行 notifications --once 成功，并重跑38项集成测试。**这是 E 的执行证据，不是 C 重发，也不证明用户看到。**
- Pensieve 0.37.0 已独立安装在 `observer/.local/pensieve-venv`，116包兼容检查/CLI help通过（约1.3GiB）；未运行采集。真实屏幕/OCR质量、录屏启停/长期资源、用户看到通知尚未验收。
- 密钥、设备 token、个人资料、数据库和原始日志均不在仓库；不读取/打印已有秘密来做交接。

### 关键资料与已知限制

- 本角色：`observer/README.md`（运行入口）、`observer/PENSIEVE_AUDIT.md`、`observer/decision_observer/core.py`、`__main__.py`、`observer/tests/`、`observer/scripts/backend_smoke.py`，以及本回执历史证据。
- **C worktree 的 PROJECT_STATE/PLAN/CONTRACT/AGENTS 仍可能是开工旧版**，不要按其中“应用未实现”重做项目。先读本地规则和C任务卡，再只读主工作区最新 `PROJECT_STATE.md`、`AGENTS.md`、`docs/mvp/PLAN.md`、`docs/mvp/CONTRACT.md`、`handoffs/E.md`、`docs/mvp/USER_TEST.md`；若存在 `docs/mvp/ACCEPTANCE_ROUND_2.md`，先判断是否为 E 未提交材料，不擅自保存或改写。不要自动把整个 integration 合回 C 分支来“解决旧文档”。
- 仅同步可安全定位的文字；sourceLocator=`resourceId:pensieve:library:<id>` / `frameId:pensieve:entity:<id>`；不上传截图或私密路径。
- 未处理/迟到OCR超出400条近期窗口会明确暂停；超大历史分页未做。通知点击跳转未做；sent不等于用户看到；停止同步不等于停止 Pensieve 底层采集。不要自主扩展这些功能。
- 本聊天没有尚在执行的测试/安装/推送/临时服务需接管。总控运行中的产品容器属于 E，不要停止或重启。当前无已确认、未修复的 C 阻断问题。

### 本次接续记录

- 已核对实际 C 目录、分支及集成包含关系；交接登记提交 `2171e88` 已由源聊天推送，业务代码仍为 `7df8326`。
- 已向当前登记的 E 聊天发送接续地址并核对第二轮验收 C 待办；E 正准备迁移，接手地址及具体新缺口待其反馈。当前资料未列 C 阻断问题。
- 本轮仅维护交接状态，无业务变更，未重跑既有测试；不启动采集、读取历史或发送系统通知。无具体 C 问题则待命。

### 接手后的第一步与验收

1. 先只读核验真实 workdir/分支、此交接编号、C提交和总控最新资料，按项目要求做独立只读子Agent审查（审查者不修改不递归）；向源聊天返回差异及第一步，不修改业务文件。
2. 源聊天完成接手登记并发出继续指令后，将本节标为已接手；通知 E 由新 C 聊天继续协作，核对当前是否有具体 C 缺陷/验收反馈。
3. 有新C问题则诊断、最小修复、相关验证、更新本回执、提交推送C分支并告知E；没有问题就准确报告可配合状态，不重跑无变化测试、不制造提交、不重发通知、不启用采集。
4. 验证按变化选用：在 observer/ 执行 `uv run --python 3.12 python -m unittest discover -s tests -v`；必要时 `uv build`。跨后端问题可用 `scripts/backend_smoke.py --jar <A当前已构建jar>`，仅独立临时PG/随机loopback端口/合成数据，不能改 E 业务实例。不要把这些合成验证标为真实观察验收。
<!-- C-HANDOFF-END C-OBS-20261104-1932 -->

交接编号：DECISION-MVP-20261104。角色由用户本轮明确为 C。
状态：适配器已集成；隔离真实后端+合成数据联调通过。E 已报告真实系统通知接口接受及后端回执成功，用户看到未确认；真实采集仍未启用。开始：2026-11-04 17:28 +08:00。
工作区：decision-worktrees/observer；分支 feat/mvp-observer；只修改 observer/ 与本回执。

| 功能 | 状态/实现 | 验证与遗留 |
|---|---|---|
| Pensieve 版本/API 核验 | observer/PENSIEVE_AUDIT.md | 源码 commit 3e55c66…、PyPI 0.37.0、Apache-2.0；已安装到 observer/.local/pensieve-venv，CLI help 通过；8839 无服务，未录屏 |
| 显式授权/暂停 | observer/decision_observer/core.py、__main__.py | 双重授权、远端暂停、断线清除同意、本地暂停竞争；底层录屏状态永远单独标记 unverified |
| OCR 转换与上传 | core.py | 中文合成数据、UTC/本地时区、20k 字符上限、安全数字 locator；未读取真实历史 |
| 去重与有限队列 | core.py SQLite | externalId 与连续相同内容去重、50 条上限、持久化幂等 key、显式恢复旧队列、问题隔离；已处理窗口可持续推进；未处理积压/迟到 OCR 超过 400 条时暂停；大积压分页仍未实现 |
| 桌面通知 | core.py notify/deliver、CLI notifications | 替身系统接口、sent/failed、回执重试；C 未发真实通知；E 后续报告接口/ack成功（见接续摘要），点击跳转未实现 |
| 运行包 | observer/pyproject.toml、uv.lock、README.md | Python 3.12.13，零运行时外部依赖；wheel/sdist 构建通过；status 默认 paused |

自检命令（observer/ 内）：`uv run --python 3.12 python -m unittest discover -s tests -v`：38 项通过；其中 3 项使用真实 loopback HTTP transport + 合成 API，不算真实业务后端/Pensieve 联调。`uv build` 通过；status 本机 CLI 通过。实际采集与资源占用未测。

运行与配对详见 observer/README.md：owner 经 A/B 注册设备，C 仅存 deviceToken；configure → 网页设备 running → 本机 run --problem-id UUID；pause/stop 仅撤销同步同意。notifications 单独轮询，无截图读取。配置位于本机私有目录，真实 token 不在仓库中。

给 E/A/B 的集成信息：
- 使用既定 v1 字段，无共享契约修改。backendUrl 包含 /api/v1；需要 scoped device token、libraryId/folderId、pensieveTimezone。
- 必须确保后端 paused 时拒绝 observations/batch，支持 device+externalId 去重与幂等重试；前端不能把“同步暂停”写成“底层采集已停止”。
- C 不执行设备注册/远端 running 操作，保留用户在产品里的显式启动。
- 真实 Pensieve、后端配对与用户启动许可未齐；已独立安装锁定依赖（116 包，约 1.3 GiB）；未启动 serve/watch/record，未启用录屏、自启、历史扫描。通知送达也仍未验收。
- DeepSeek、Wiki、小程序真机均非 C 验证范围，本回执不声称完成。

代码复查发现的持续运行误停、重复内容、满队列恢复问题已修复，新增回归测试通过。

可集成代码提交：`0425b4f` 已推送 origin/feat/mvp-observer；首次推送 GitHub 返回 500，核实远端仍旧提交后重试成功。后续依赖准备/空 OCR 修复见当前分支后续提交；提交前检查只包含上述允许目录、无密钥/真实资料/运行数据库。用户验收未进行。

总控提供的集成地址：API http://127.0.0.1:8080/api/v1，网页 http://127.0.0.1:8088。E 可按 README 配对设备；未收到真实 device/token 前 C 不做真实上传。依赖安装与 CLI help 是当前实测证据，不等于 Pensieve 服务或真实 OCR 已通过。

2026-11-04 17:42 +08:00 推送检查：代码主提交 0425b4f 已在远端；补充提交 c61d55c 已本地提交，连续多次 push 收到 GitHub remote Internal Server Error（含一次命令级代理路径），远端检查仍为 0425b4f。若无后续成功记录，总控可先集成该首版，本地分支包含最新空 OCR 修复与已安装依赖记录；无 force push。

后续恢复：等待后重试成功，`cd4401e`（包含 `c61d55c`）已推送到 origin/feat/mvp-observer，上述 500 阻塞已解除。适配器最新 30 项测试/构建通过，Pensieve 独立环境 116 包 `uv pip check` 通过；仍未启动采集或验证真实通知。


## 后端联调增量 — 2026-11-04 17:48 +08:00

用户明确授权通知总控并继续联调，录屏不要开；已将首版提交及兼容缺口发给 E，并收到 E 最终方案。独立只读复查完成。

- 经 E 确认，locator 改为已有 `resourceId=pensieve:library:<id>` 与 `frameId=pensieve:entity:<id>`，不改公共契约、不含路径。
- 批次按实际 JSON 编码限 900000 字节且最多 50 条；明确拒绝后换幂等 key，网络未知结果保留原 key。
- 通知按 20 条分页，失败项持久化指数退避；避免首条 failed 长期阻塞后续议题。
- 自检 30 项通过；新增 `observer/scripts/backend_smoke.py`，使用 A 已构建 jar、独立临时 PostgreSQL 16、随机 loopback API 端口、worker=false/mock=false、无外部 key。
- 真实 Java/PostgreSQL HTTP 验证：paused拒绝、设备越权拒绝、心跳/观察接收、externalId去重、locator回查、hash拒绝、暂停竞争后显式恢复、20条长中文拆3批、21条合成Delivery分页及failed退避、跨设备ack拒绝。全部通过。
- 通知夹具直接写入**临时测试库**，notifier为测试替身；不证明真实模型产出议题或macOS通知送达。测试最终将设备paused并清理临时Java进程/PG容器；没有动E业务库、8080、其他项目或TGA。
- 测试时 A 分支 HEAD 8377431；实际 jar SHA256 `64a194b0ac7cbde1a935a3b1a181c0f4839d9b53d88af77d6e9db842062c5cf2`。后端源码仅只读，未改 A 文件。
- 后续待 E 集成此次 C 提交；真实 Pensieve/OCR、系统通知送达仍待单独授权与验收。录屏持续关闭，没有启动采集器。


## 集成兼容增量 — 2026-11-04 17:53 +08:00

用户继续授权直接修复推送、录屏仍不开；已与 E 同步最新集成状态，并完成独立只读审查。发现后端新增 snooze 重排逻辑与本地 sent 去重冲突，先用失败测试复现；同时复现 emoji 按 Python 字符数截断超过 Java UTF-16 长度限制。

- 新增持久化通知轮次：pending updatedAt 变化代表同一 delivery 的新轮次；重置本地 sent/失败退避，ack key 包含轮次。同轮未知结果重试继续去重，不吞掉稍后提醒。
- text/appName 按 UTF-16 单元安全截断，再计算 contentHash；不截断代理对。
- CLI status 仅在运行锁仍被持有且存在暂停请求时显示 pausing；进程已停则准确显示 paused。
- 34 项单元/loopback HTTP/CLI 子进程测试通过；15 项真实 Java/PostgreSQL 合成联调通过，包括 emoji 长度及 snooze 到期重新通知、同轮不重复。测试 notifier 仍为替身。
- 使用 A 最新 1a3d9ed 工作区已有 jar，SHA256 `c3a0777cb8acfd8cf587bc08b893b75a849080749a5b2dadfb9773248bb4edaf`；独立临时库/随机loopback端口、worker关闭、无外部key。测试后设备paused，临时Java/PG已清理。未改A/E文件，未启动Pensieve、录屏或真实系统通知。
- 提交后通知 E 集成最新 C；真实采集和系统通知可见性仍未验收。


## E 报告的真实通知验证 — 记录于 2026-11-04 18:56 +08:00

证据来源为 E 总控消息（源聊天 01a1a5b5-b163-7803-9667-8e1d17bef002），以下不是 C 在本轮重新执行的测试：

- E 使用已集成的 C `3c774b3`，在本机以独立合成测试设备验证；设备 desiredState/reportedState 均为 paused。
- 据 E 报告，真实模型分析产生 1 条合成 discussion/delivery；`Runner.deliver()` 使用默认真实 `osascript`，系统接口接受，本地 notifications 记为 sent，后端待投递数量从 1 变为 0。
- 可据此记录为“E 报告：真实系统通知接口接受 + ack 链路成功”；**不能记录为用户已看到、用户验收通过或真实屏幕观察通过**。
- E 明确未调用 Pensieve.read/run 或任何录屏。本轮 C 仅维护回执，没有重复发送通知、读取屏幕历史或启用采集。

此前各阶段“真实通知未验证”保留为当时状态；此报告更新通知接口/回执证据，用户可见性仍未确认，录屏继续关闭。


## 仅通知配置入口修复 — 2026-11-04

E 在交付检查中发现仅通知模式仍要求 Pensieve 配置。此前 E 真实测试曾在忽略配置中用 library/folder 占位 ID 1；这不算用户仅通知配置已完成。

- 新增 `configure --notifications-only`：仅填写 backendUrl/deviceId/deviceToken，隐藏 token，0600 保存；不保留旧观察参数，不改设备状态。
- `notifications` 只校验设备连接配置；Runner 延迟构造 Pensieve，通知路径完全不构造/访问它。
- `run` 先完整校验观察配置与 problemId，缺少字段时在清队列、心跳或取得本地同意前失败，提示先完整 configure。
- 独立只读审查通过。38 项测试通过（新增仅通知交互、最小配置模拟投递、不构造Pensieve、缺配置run保留队列且无HTTP）；构建通过。没有启动持续通知、真实系统通知或录屏。
