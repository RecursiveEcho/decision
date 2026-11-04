# C 观察回执

交接编号：DECISION-MVP-20261104。角色由用户本轮明确为 C。
状态：首版适配器已实现、自检通过，待真实依赖联调。开始：2026-11-04 17:28 +08:00。
工作区：decision-worktrees/observer；分支 feat/mvp-observer；只修改 observer/ 与本回执。

| 功能 | 状态/实现 | 验证与遗留 |
|---|---|---|
| Pensieve 版本/API 核验 | observer/PENSIEVE_AUDIT.md | 源码 commit 3e55c66…、PyPI 0.37.0、Apache-2.0；已安装到 observer/.local/pensieve-venv，CLI help 通过；8839 无服务，未录屏 |
| 显式授权/暂停 | observer/decision_observer/core.py、__main__.py | 双重授权、远端暂停、断线清除同意、本地暂停竞争；底层录屏状态永远单独标记 unverified |
| OCR 转换与上传 | core.py | 中文合成数据、UTC/本地时区、20k 字符上限、安全数字 locator；未读取真实历史 |
| 去重与有限队列 | core.py SQLite | externalId 与连续相同内容去重、50 条上限、持久化幂等 key、显式恢复旧队列、问题隔离；已处理窗口可持续推进；未处理积压/迟到 OCR 超过 400 条时暂停；大积压分页仍未实现 |
| 桌面通知 | core.py notify/deliver、CLI notifications | 替身系统接口、sent/failed、回执重试；真实 macOS 通知未发，点击跳转未实现 |
| 运行包 | observer/pyproject.toml、uv.lock、README.md | Python 3.12.13，零运行时外部依赖；wheel/sdist 构建通过；status 默认 paused |

自检命令（observer/ 内）：`uv run --python 3.12 python -m unittest discover -s tests -v`：26 项通过；其中 3 项使用真实 loopback HTTP transport + 合成 API，不算真实业务后端/Pensieve 联调。`uv build` 通过；status 本机 CLI 通过。实际采集与资源占用未测。

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
