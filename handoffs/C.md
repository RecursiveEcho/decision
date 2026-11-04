# C 观察回执

交接编号：DECISION-MVP-20261104。角色由用户本轮明确为 C。
状态：适配器已实现；隔离真实后端+合成数据联调通过，真实采集/系统通知仍未验证。开始：2026-11-04 17:28 +08:00。
工作区：decision-worktrees/observer；分支 feat/mvp-observer；只修改 observer/ 与本回执。

| 功能 | 状态/实现 | 验证与遗留 |
|---|---|---|
| Pensieve 版本/API 核验 | observer/PENSIEVE_AUDIT.md | 源码 commit 3e55c66…、PyPI 0.37.0、Apache-2.0；已安装到 observer/.local/pensieve-venv，CLI help 通过；8839 无服务，未录屏 |
| 显式授权/暂停 | observer/decision_observer/core.py、__main__.py | 双重授权、远端暂停、断线清除同意、本地暂停竞争；底层录屏状态永远单独标记 unverified |
| OCR 转换与上传 | core.py | 中文合成数据、UTC/本地时区、20k 字符上限、安全数字 locator；未读取真实历史 |
| 去重与有限队列 | core.py SQLite | externalId 与连续相同内容去重、50 条上限、持久化幂等 key、显式恢复旧队列、问题隔离；已处理窗口可持续推进；未处理积压/迟到 OCR 超过 400 条时暂停；大积压分页仍未实现 |
| 桌面通知 | core.py notify/deliver、CLI notifications | 替身系统接口、sent/failed、回执重试；真实 macOS 通知未发，点击跳转未实现 |
| 运行包 | observer/pyproject.toml、uv.lock、README.md | Python 3.12.13，零运行时外部依赖；wheel/sdist 构建通过；status 默认 paused |

自检命令（observer/ 内）：`uv run --python 3.12 python -m unittest discover -s tests -v`：34 项通过；其中 3 项使用真实 loopback HTTP transport + 合成 API，不算真实业务后端/Pensieve 联调。`uv build` 通过；status 本机 CLI 通过。实际采集与资源占用未测。

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
