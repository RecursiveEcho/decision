# Decision 桌面实施进度

开始于 2026-11-11，北京时间 2026-11-12 更新。阶段 1–4 的实现已形成可运行的本机 Preview 包，窗口专项验收尚有缺项。阶段 5 正式数据切换单独确认。

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
| 桌面窗口 | 实际 WKWebView 展示登录页，合成成员登录成功，草稿可写入，原生运行状态页面可用 |
| 正常退出 | 通过原生应用菜单退出，自有服务进程和三个监听端口全部清理，PostgreSQL 记录正常关闭 |
| 单实例与强退 | 第二实例退出且无重复服务，强退主管理进程后 guardian 清理全部自有进程组及端口 |
| 服务恢复 | 自有 Java 被 SIGKILL 后以新进程恢复，数据库退出后停止依赖服务并保留桌面管理器供诊断 |
| 冲突与连续重开 | 真实占用固定端口时不启动服务、端口配置保持一致、占用者存活。修复 TCP TIME_WAIT 误报后，精简 PATH 下立即重开通过 |

原生链路入口为 `python3 desktop/native-smoke.py --runtime .local/desktop/runtime`，加 `--backend-tests` 可运行原有后端测试。测试证据保存在忽略的 `.local/desktop/`，包含 `release-smoke.log`、`runtime-audit.log`、`app-audit.log` 和 `lifecycle-results.log`。

## 验收缺项和使用边界

- 原生文件选择器已打开，但自动化未完成文件选择确认。WKWebView 附件上传/IndexedDB 暂存、下载、剪贴板、外链、关窗后 Dock 重开及草稿保留仍需专项实测。
- 现有网页把登录和文字草稿保存在 sessionStorage。Cmd-Q 后重新登录会开始新的本机会话，当前实现沿用这一行为。
- 没有执行整机休眠/唤醒、断网恢复和干净 Apple Silicon Mac 验收。
- 本轮模型请求使用显式测试替身。知识 smoke 以 SQL 合成已确认条目，再走真实导出和镜像。真实模型聊天、模型提议及人工确认知识需要用户自己的 Key，由设置页保存后重开验收。
- 预览包采用本机临时签名。更新可执行文件后，macOS 可能再次询问原有 Preview 钥匙串凭据的访问授权。本轮用户已在系统提示中完成授权。
- 跨机器分发的 Developer ID、公证、完整分发审查和用户实际验收待后续环境验证。

工程自检使用全新临时库及 Preview 合成账号。正式配置、数据库、Docker 卷和原有知识镜像未参与测试。
