# Decision 桌面实施进度

开始于 2026-11-11，北京时间 2026-11-12 更新。阶段 1–4 的实现已形成可运行的本机 Preview 包，主要 WKWebView 专项已完成；环境相关缺项见下方。阶段 5 正式数据切换单独确认。

## 交付和实现

- 分支 `codex/decision-desktop`，基线 `da7fcdf`。独立只读子 Agent 已审查需求、授权和主要实现。
- 交付 `Decision Preview.app`，应用标识 `dev.decision.desktop.preview`。原生 PostgreSQL 16.15、Temurin Java 17.0.21+9、Node 22.23.4、Wiki.js 2.5.314 和 CPython 3.12.16 打包的独立同步程序随包提供。归档来源及 SHA-256 在 `desktop/runtime-lock.json`。
- `desktop/src-tauri/` 管理窗口、Keychain、固定端口、服务身份挑战、guardian 进程组、有限重启和退出。Java 同源页面与 API guard 位于 `desktop/java/` 的独立 adapter，复用原后端 JAR 和 Vue 构建。
- `desktop/prepare-runtime.py` 装配运行时，`audit-runtime.py` 审计原生文件，`collect-notices.py` 收集目标平台 Rust 依赖许可证及元数据。Wiki 上游 Linux 原生构建产物使用其 JS 回退，未打包需要系统 Python framework 的可选 PostgreSQL PL/Python 扩展。
- `scripts/knowledge_sync.py` 和 `wiki_setup.py` 保留源码 CLI 默认入口，新增显式路径/客户端注入及桌面身份验证。桌面入口不读取项目 `.env`。
- 数据位于用户 Application Support 的 Preview 命名空间，日志位于用户 Logs 的同名目录，凭据保存到登录钥匙串。同步目录为 Preview 数据目录中的 `Knowledge/`。

## 已通过

| 检查 | 实际结果 |
| --- | --- |
| 现有业务回归 | 后端 140 项在全新原生 PostgreSQL 临时库通过，前端 90 项及 Vue 构建通过，后端 JAR 打包通过 |
| 同步回归 | 18 项通过，包含拒绝伪造服务身份并阻止发送访问码的新增用例 |
| 固定运行时的原生真实 HTTP | 临时双库/非超级用户角色/禁止跨库、Flyway、静态 Vue、API 鉴权、Host/Origin/fetch-site 检查、HMAC 身份、双账号隔离全部通过 |
| 附件与知识 | 原生子 Java 解析 TXT、附件字节回读、真实 Wiki 初始化/取消访客权限/Java 写入/独立 GraphQL 回读、独立同步可执行文件的导出镜像通过 |
| 重定位 | 原生 smoke 在复制到含空格的全新运行目录后通过，测试进程组均退出 |
| 独立应用包 | Tauri release 构建及本机临时签名通过，包复制到源码目录外的含空格路径后可启动 |
| 原生依赖 | 装配运行时 244 个、打包资源内 292 个原生文件均含 arm64 且嵌套签名有效，资源链接及外部绝对依赖检查通过。包内因资源拷贝展开部分链接，文件计数不同 |
| 桌面窗口 | 实际 WKWebView 登录、两账号切换隔离、文字复制粘贴、文件选择、文件粘贴、图片预览、IndexedDB 暂存及 Cmd-R 恢复通过 |
| 正常退出 | 通过原生应用菜单退出，自有服务进程和三个监听端口全部清理，PostgreSQL 记录正常关闭 |
| 单实例与强退 | 第二实例退出且无重复服务，强退主管理进程后 guardian 清理全部自有进程组及端口 |
| 服务恢复 | 自有 Java 被 SIGKILL 后以新进程恢复，数据库退出后停止依赖服务并保留桌面管理器供诊断 |
| 冲突与连续重开 | 真实占用固定端口时不启动服务、端口配置保持一致、占用者存活。修复 TCP TIME_WAIT 误报后，精简 PATH 下立即重开通过 |

原生链路入口为 `python3 desktop/native-smoke.py --runtime .local/desktop/runtime`，加 `--backend-tests` 可运行原有后端测试。测试证据保存在忽略的 `.local/desktop/`，包含 `release-smoke.log`、`runtime-audit.log`、`app-audit.log` 和 `lifecycle-results.log`。

## 验收缺项和使用边界

- 文件选择、粘贴、上传、IndexedDB、下载、剪贴板、外链及关窗重开现已完成，详见本轮记录。跨窗口拖放由用户手动完成手势，助手观察附件列表接收成功。macOS 重开处理通过实际 Reopen 事件验证，未单独录制 Dock 点击动作。
- 现有网页把登录和文字草稿保存在 sessionStorage。Cmd-Q 后重新登录会开始新的本机会话，当前实现沿用这一行为。
- 尚未执行整机休眠/唤醒和干净 Apple Silicon Mac 验收。网络恢复已通过隔离本地上游的真实 HTTP 503 与连接断开验证，宿主网络配置未切换。
- 本轮模型请求使用显式测试替身。知识 smoke 以 SQL 合成已确认条目，再走真实导出和镜像。真实模型聊天、模型提议及人工确认知识需要用户自己的 Key，由设置页保存后重开验收。
- 预览包采用本机临时签名。更新可执行文件后，macOS 可能再次询问原有 Preview 钥匙串凭据的访问授权。本轮用户已在系统提示中完成授权。
- 跨机器分发的 Developer ID、公证、完整分发审查和用户实际验收待后续环境验证。

工程自检使用全新临时库及 Preview 合成账号。正式配置、数据库、Docker 卷和原有知识镜像未参与测试。


## 2026-11-12 第二轮本机验收

用户要求继续完成能做的验收并直接修复。独立只读审查再次通过。

### 修复

- 关闭业务窗口的 Tauri 原生拖放拦截，让 Vue 现有 HTML 文件拖放处理器接收事件；业务窗口权限边界保持原有范围。
- 修复短暂健康检查失败后，核心已恢复但状态仍停留在“正在检查”的问题。旧包暂停自有 Java 五秒后稳定复现；修复包在相同暂停/恢复条件下显示“可用”。
- 增加“显示 → 重新载入页面”及 Cmd-R，并记录不含 URL、参数或凭据的关闭/重开/重新载入事件。启动页额外显示模型配置状态，避免把核心可用误读为模型已经配置。

### 实际证据

- 正常文件选择器确认 TXT，访达文件复制/粘贴导入 PNG；两者在 Cmd-R 后仍在草稿中。刷新后暂存图片可从 IndexedDB 回读并预览。
- 正常“发送”入口提交两份合成附件，后台实际解析完成。TXT 与原件全部 57 字节一致；PNG 经现有前端 canvas 转换后压缩字节不同，但 512×512 的 sRGB 预乘 RGBA 所有通道与原图完全一致。模型未配置的提示保留原话及附件，真实模型请求增加 0。
- 草稿中的 TXT 和服务器记录中的 TXT 均通过正常附件按钮下载；重复下载生成独立文件，原文件保留，内容逐字节一致。
- 正常复制、替换、粘贴回读得到原文。账号 A 的历史、资料和草稿切到账号 B 后为空白，B 的资料/主题/知识均为 0。
- 资料的“原始链接”在系统默认 Tabbit 打开公共示例页，业务窗口保留在自己的 loopback 地址；本次外链标签页已关闭。
- 业务窗口关闭日志明确记录 `visible=false`，随后系统 Reopen 事件恢复相同进程和会话，文字草稿与图片仍在。Cua 的后续窗口观察本身会触发系统重开，因此记录事件顺序来核对关闭动作。
- 用户完成跨窗口拖放后，附件列表收到 PDF。该个人文件随后从本地测试草稿移除，未提交到服务端；服务器附件清单仍只有合成文件。该动作证明拖放接收，上传和字节验证使用上述合成 TXT/PNG。
- 新增 `desktop/network-smoke.py`，通过独立临时双库和回环 HTTP 替身制造 503 及连接断开。两种错误均保留原话/附件、停止自动重放，恢复后明确重试只产生一条助手消息。此项验证实际 Java HTTP 传输与恢复，响应内容为合成数据。
- 运行时资源再次审计通过；更新后的签名可执行文件与原资源清单逐字节匹配，独立应用包深层签名校验通过。最终启动页已目视确认 Wiki、数据库、核心、知识镜像均可用，模型单独显示未配置；正常业务登录入口通过。

本轮证据在忽略的 `.local/desktop/acceptance-round2/`，主要包括 `baseline.txt`、`network-final.log`、`ui-upload-result.json`、`runtime-audit.log`、`final-build.log`。窗口事件位于 Preview 用户日志目录的 `desktop.log`。
