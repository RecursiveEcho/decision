# 本地运行与后续服务器部署

本轮只运行本项目的 `decision-mvp` Compose project。没有改动其他项目容器或 TGA。

## 初次配置

在项目根目录：

```sh
python3 scripts/manage.py init
# 本人编辑忽略的 .env，填 DEEPSEEK_API_KEY。不要提交或发到聊天。
python3 scripts/manage.py check
python3 scripts/manage.py infra
python3 scripts/wiki_setup.py
```

init 保留已有配置，为缺失的本地访问码、签名秘钥和数据库密码生成随机值。配置文件权限为 0600。应用访问码在 `.local/login.txt`；Wiki 登录资料在 `.local/wiki-login.txt`，均不上传。

Wiki 初始化只支持当前项目 loopback 地址，使用实际 Wiki.js 2.5.317 API，关闭遥测和匿名页面读取，创建仅有页面操作权限的应用 token。新 Wiki 只有 en locale，页面正文可以中文；不要在尚未安装中文 locale 时改成 zh。

## 业务代码集成后

```sh
python3 scripts/manage.py build
python3 scripts/manage.py up
python3 scripts/manage.py status
```

网页 `http://127.0.0.1:8088`；后端 `http://127.0.0.1:8080/api/v1`；Wiki `http://127.0.0.1:8090`；开发用业务数据库 `127.0.0.1:55432`。这些都只绑定本机，不表示手机或公网可访问。

build 必须等 backend/Dockerfile 与 web/package-lock.json 已集成，不能拿空目录当已构建。模型未配置时状态明确显示未配置，不静默启用 mock。

停止服务：`python3 scripts/manage.py stop`。它保留数据卷，不删除数据。观察器由单独的本地命令管理；以上命令不启动录屏或 Pensieve。

## 备份与恢复核验

```sh
python3 scripts/manage.py backup
python3 scripts/manage.py verify-backup .local/backups/实际时间目录
```

备份包括 decision/wiki 两个 PostgreSQL custom dump 与 Wiki 数据卷文件；不包含 `.env`。备份含私人数据，只保存在本机忽略目录。需另行安全保存 `.env`，避免恢复后身份/密钥丢失。

verify-backup 在两个数据库服务中各创建随机临时数据库，将 dump 实际恢复并查询表数量后移除临时库；同时检查文件归档可读，不覆写正在使用的数据库。当前不提供一键覆盖线上数据的恢复命令。

## 服务器选择

先记录本地实际内存、CPU、数据增长，再只读核验候选服务器。与 TGA 共机须有足够余量、独立数据卷/账号/端口、备份及故障隔离。真实部署还需要 HTTPS、域名和小程序平台接入条件；本机成功不等于已上线。

镜像已固定 digest，开发模块依赖由各模块锁文件固定。后端构建镜像由 A 管理，初次构建时仍需网络下载依赖。

## 只试桌面提醒，不开观察

本机工程验收已准备一个暂停状态的专用测试设备及私有配置。需要试持续提醒时，在项目根目录前台运行：

```sh
uv run --project observer --python 3.12 python -m decision_observer --home .local/notification-test notifications
```

Ctrl-C 停止。此命令只轮询通知，不访问 Pensieve 或屏幕；本轮交付未自动启动它。`manage.py stop` 管理 Compose，不能替代在通知终端 Ctrl-C。系统接受通知不代表通知权限/专注模式允许本人看到。

另一台电脑或换设备时，先在网页注册设备保存一次性凭据，再按 `observer/README.md` 的 `configure --notifications-only` 配置，不需要安装或开启录屏。
## 讨论后入库与 Obsidian 本机镜像

在已有对话点击“整理入库”，审阅 AI 的新增/合并/修订建议，再确认保存。准备提案不会写正式知识；旧资料与主题也不会自动升级。继续聊天后可重新整理；目标已更新的提案需重新对照。知识中的领域、出处、相关知识和旧版本均可查看。

`python3 scripts/manage.py up` 同时启动本项目专用的本机知识同步进程；不是开机自启，不开启录屏或通知。默认目录为 `~/Documents/Decision Knowledge`，在 Obsidian 中以“打开文件夹为仓库”打开，阅读 `知识总览.md`，再打开原生关系图谱浏览领域/知识/出处连接。已有私人 Obsidian 仓库不受影响。可在本机 `.env` 的 `OBSIDIAN_VAULT_PATH` 指定专用真实目录，勿指向含有同名个人文件的目录或符号链接。

- `python3 scripts/manage.py knowledge-status`：本机进程、最近同步时间、知识/领域数及错误；仅入库成功不代表文件已同步。
- `python3 scripts/manage.py knowledge-sync`：立即执行一次同步；正常后台约10秒增量更新，失败退避重试，不调用模型。
- `python3 scripts/manage.py knowledge-stop`：只停止同步，保留镜像与项目服务；`python3 scripts/knowledge_sync.py start` 再启动。
- `python3 scripts/manage.py stop`：停止本机同步及项目服务，保留全部数据。

镜像使用稳定ID目录和可读标题文件名，让原生图谱直接显示标题和领域名；改标题时重建全部受管链接，分类变化不改变知识身份。只处理本程序清单管理的文件，原子替换并记录中断恢复清单。若发现手动修改、文件冲突或符号链接则暂停覆盖；先把本机手写内容移到专用目录之外，再重试。不要通过删除清单来强行覆盖。Obsidian 配置、用户添加的其他文件、原附件不被同步；本机仅保留引用的必要片段，不复制全部聊天和个人记忆。数据库备份包含正式知识、提案与历史；镜像可重建，修改前保留的私人文件需另行备份。

自动分类和关系链接是整理建议；有界检索不代表完整全库冲突检测。对话文字、助手陈述与正式资料分别保留身份，助手内容不因此成为外部事实。附件原件首版不直接纳入整理或导出，提案中说明覆盖范围。
