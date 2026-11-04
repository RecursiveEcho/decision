# C：本地观察与桌面通知适配器

Python 3.11+，运行时仅标准库。当前实现对接 Pensieve 0.37.0 的已核实 HTTP 格式；**已用合成文字验证隔离的真实 Java/PostgreSQL 后端；尚未实测真实 Pensieve 或系统通知送达**。适配器不会自动安装/启动 Pensieve 或设置开机自启。开发阶段已在本模块忽略的独立环境安装 Pensieve，未启动服务或录屏。采集和同步是两个独立状态。

## 安装与自检

在本模块目录执行：

```sh
uv sync --python 3.12 --frozen
uv run python -m unittest discover -s tests -v
uv run python -m decision_observer status
```

也可在 Python 3.11+ 环境直接 `python -m decision_observer status`。系统自带 Python 3.9 不满足本模块声明。测试只有虚构文字、替身 API 和临时本地 HTTP 服务；不会访问真实历史或弹通知。

## 配对与启动（由本人操作）

1. 在网页登录后按共享契约 `POST /api/v1/devices` 注册，获取一次性 `deviceToken` 与 `device.id`。这是设备 token，不能填写 owner token 或模型 key。C 不保存所有者 code。
2. 在 Pensieve 页面选择专用截图 library/folder，记录数字 ID；仅在本人允许的无敏感页面开启录屏。参阅 [版本核验](PENSIEVE_AUDIT.md)。不要为获取 ID 运行全历史导出。
3. 运行 `uv run python -m decision_observer configure`，交互填写 `http://127.0.0.1:8080/api/v1` 等配置，token 隐藏输入。远端后端必须 HTTPS，Pensieve 仅允许 loopback；拒绝 HTTP 重定向；不继承系统代理。
4. 在网页选定问题、明确将设备切换到 running；然后本机明确执行：

```sh
uv run python -m decision_observer run --problem-id <问题UUID>
```

两步缺一都不能读取/上传观察。run 前的数据不会补采。`run` **不会启动 Pensieve 本身**。运行中上传当前选定问题的 OCR 文字及数字 entity/library 定位符，不上传图片、文件路径、窗口标题或其他元数据。原文最多 20000 个 UTF-16 单元（与 Java 后端一致，不切断 emoji），明确截断；OCR 未完成会在近期窗口重读。

另一终端暂停，或在运行终端 Ctrl-C：

```sh
uv run python -m decision_observer pause
uv run python -m decision_observer status
# stop 与 pause 同义
```

暂停/断线/远端 paused 清除本地同意，连接恢复也不自动重启。网络操作每次超时 2 秒，逐轮检查心跳，并在上传前重新确认远端状态；单个已发出的 HTTP 请求无法撤回，服务端必须按契约在 paused 时拒绝新数据。运行循环没有后台自启或重连自动授权。

**暂停适配器只停同步，不保证 Pensieve 底层录屏停止。** 本人必须单独停止 Pensieve 并确认录屏进程状态；CLI 始终标记底层采集为 unverified。没有实现录屏进程控制，不宣称全局停止观察。

## 队列与通知

默认本机 `~/.decision-observer/`（目录 0700，配置/数据库 0600）。可用全局 `--home` 指向其他私有目录。SQLite 保存最多 50 条已同意的待上传文字、每条稳定幂等 UUID 和最多 10000 个已接收 externalId。成功即删队列正文；SQLite 旧页/备份不承诺安全擦除。暂停后不续传，下一次普通 run 清空待发队列；明确运行 `run --problem-id <原问题UUID> --resume-queue` 才重试旧队列。不同问题不允许混传。队列满、接口错误或格式不匹配会暂停并要求再次本地启动。

单次查看最近 400 个实体以等待延迟 OCR。已处理窗口可以继续推进；未处理数据超过窗口或待 OCR 实体跌出窗口时明确暂停，不悄悄漏数据。已接收 ID 是持久化去重游标；连续相同文字以问题+contentHash 抑制重复。先批量排空已授权队列再读新数据，每批最多 50 条，未处理数据下一轮继续。尚未实现超大积压的历史分页；恢复采用新授权时段，不补采暂停期间数据。

通知独立运行，不需要开启屏幕同步：

```sh
uv run python -m decision_observer notifications
# 只检查一次
uv run python -m decision_observer notifications --once
```

每 5 秒取一页待发通知（最多 20 条），按后端游标继续分页；失败项持久化指数退避（30 秒起、最多 1 小时），不阻塞同页及后续页，macOS `osascript display notification` 接受后回执 sent，失败回执 failed。断线退出，明确重启；已有 sent 本地回执可重试而不重复弹出（操作系统调用成功但本地落盘前崩溃仍可能重复，非 exactly-once）。sent 不代表用户看到；需手动核验通知权限/专注模式。首版不支持点击通知跳转，回网页按同标题查议题。Ctrl-C 退出通知轮询；观察 pause 不关闭独立通知轮询。

## 当前验收边界

已验证：34 项合成/本机 HTTP 测试、Python wheel/sdist 构建、默认暂停状态。未验证：真实中文 OCR、总控业务实例的 device 配对、Pensieve 底层启停、真实系统通知到达及用户看到、实际资源占用。没有读取个人历史，没有实际观察或通知验收结论。

## 已准备的 Pensieve 独立环境

本次已安装 `memos==0.37.0` 和 116 个解析依赖到 `observer/.local/pensieve-venv`，未加入全局 PATH。依赖锁为 `pensieve-requirements.lock`；环境磁盘约 1.3 GiB，非运行内存测量。已验证分发元数据与 CLI help 可执行，未验证服务/OCR。

在本模块目录重建：

```sh
uv venv --python 3.12 .local/pensieve-venv
uv pip install --python .local/pensieve-venv/bin/python -r pensieve-requirements.lock
```

**下面是本人明确启用后的前台运行步骤，本次未执行。** 三个独立终端使用同一环境：先 `.local/pensieve-venv/bin/memos serve`，确认服务与专用 library/folder 后 `.local/pensieve-venv/bin/memos watch`，最后本人选择无敏感页面、明确授权录屏后 `.local/pensieve-venv/bin/memos record`。record 会请求 macOS 屏幕录制权限并立即录屏；不要在未准备好时执行。首次 OCR/embedding 可能下载模型，尚未验证。首次普通 CLI 导入也可能创建 `~/.memos/config.yaml`，应先核对已有配置，别覆盖旧实例。前台进程用各终端 Ctrl-C 停止并检查实际状态；不要用 `enable`/整组 `start` 作为安装步骤。

适配器的 `run` 和网页设备 running 仍须另行明确操作；停止适配器不能代替停止上述 record。前台启停步骤依据官方源码，真实启停效果仍待用户授权后的短时试验。

## 隔离后端联调（无录屏）

```sh
uv run python scripts/backend_smoke.py --jar /path/to/decision-backend-0.1.0.jar
```

需要 Docker、JDK 17 和 A 已构建的 jar。脚本创建独立一次性 PostgreSQL、随机 loopback Java 端口、随机测试凭据；worker 关闭、mock 关闭、不注入模型/Wiki key。不使用总控业务库或 8080。结束恢复测试设备 paused 并删除自己创建的临时服务。

已验证暂停拒绝、设备越权拒绝、心跳上传、去重、hash 校验、locator 回查、暂停竞争后恢复、20 条长中文分成 3 批、21 条合成 Delivery 分页/失败退避/跨设备 ack 拒绝。Delivery 由临时数据库直接注入测试夹具，系统通知函数用替身；不证明真实模型生成议题或通知到达。日期/结果见 `handoffs/C.md`。

定位编码经 E 确认：`resourceId=pensieve:library:<数字ID>`，`frameId=pensieve:entity:<数字ID>`，不含文件路径。HTTP 批次同时受 50 条和实际 JSON 编码 900000 字节限制，兼容后端 1 MiB 上限。明确返回 rejected 的条目会更换请求幂等键后等待本人显式重启；网络结果未知保留原 key，避免重复写入。


“稍后提醒”兼容：对同一 delivery，后端重新置为 pending 且 updatedAt 改变时，适配器视为新通知轮次。新轮次会再次显示并使用独立 ack 幂等 key；同一轮的网络/ack 重试不会重复调用通知接口。升级旧本地通知库时，缺少轮次的 pending 通知会重新进入一次显示流程，避免吞掉提醒。该行为已通过真实后端＋合成通知替身验证，不等于 macOS 实际送达。
