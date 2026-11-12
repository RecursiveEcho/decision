# Decision Preview for macOS

双击 `Decision Preview.app`，等待本机服务启动。首次运行会在当前 macOS 账户中建立试用数据库。

- 在菜单栏选择 **Decision Preview → 运行状态与设置**，可查看服务状态、日志、管理员访问码和模型设置。
- 在业务窗口用管理员访问码登录，或注册试用成员账号。填写自己的 DeepSeek API Key 后，按 Cmd-Q 退出并重新打开，配置才会生效。
- 关闭窗口会保留本机服务。点 Dock 图标可重新打开窗口，按 Cmd-Q 会停止服务并退出。
- 数据和独立知识镜像保存在 `~/Library/Application Support/dev.decision.desktop.preview/`，日志保存在 `~/Library/Logs/dev.decision.desktop.preview/`，凭据保存在登录钥匙串。移动或移除应用包后，试用数据仍可由同标识的应用读取。预览包更新后，系统可能再次请求访问原有试用凭据，请核对应用名称后完成系统授权。

## 构建

在 Apple Silicon Mac 上准备 Command Line Tools、Rust/Cargo、JDK 17、Node/npm 与 Python 3.9 以上。运行时归档来源和 SHA-256 在 `runtime-lock.json`。PostgreSQL 与 Postgres.app 的许可文本来自对应的 [PostgreSQL 16.15](https://github.com/postgres/postgres/blob/REL_16_15/COPYRIGHT) 和 [Postgres.app 2.9.6](https://github.com/PostgresApp/PostgresApp/blob/v2.9.6/LICENSE)。

从仓库根目录执行：

```sh
bash desktop/build.sh
python3 desktop/native-smoke.py --runtime .local/desktop/runtime
```

应用包位于 `desktop/src-tauri/target/release/bundle/macos/Decision Preview.app`。检查包签名可运行 `codesign --verify --deep --strict` 并传入应用包路径。预览包使用本机临时签名，跨机器分发需要另外准备签名及公证。

原生 smoke 用临时双库与合成资料验证服务，模型请求使用替身。加上 `--backend-tests` 会在临时原生库运行原有后端测试。实际通过的检查及待验收的 WKWebView 项目见 [桌面实施进度](../docs/desktop/PROGRESS.md)。
