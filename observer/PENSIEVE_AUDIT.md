# Pensieve 只读核验 — 2026-11-04

固定源码 commit `3e55c66edc871c0960b5dcc2315b62da558d17d8`；对应 `pyproject.toml` 版本 0.37.0；PyPI JSON 当日亦为 0.37.0。源码/发行元数据可获取，不代表已安装及兼容性验证。项目 Apache-2.0；本模块通过 HTTP 接口适配，没有复制第三方实现。

| 内容 | 核验依据与结论 |
|---|---|
| 发布版本 | [PyPI memos](https://pypi.org/project/memos/0.37.0/)；Python >=3.10，含 torch、OCR、macOS PyObjC/Quartz 等较重依赖；本次未安装/执行 |
| 许可 | [固定 LICENSE](https://github.com/arkohut/pensieve/blob/3e55c66edc871c0960b5dcc2315b62da558d17d8/LICENSE) |
| 截图与配置 | [default_config.yaml](https://github.com/arkohut/pensieve/blob/3e55c66edc871c0960b5dcc2315b62da558d17d8/memos/default_config.yaml)：base_dir ~/.memos、screenshots_dir screenshots、record_interval 4 秒；README 写 5 秒，以实例实际配置为准；未找到自动销毁保证 |
| macOS OCR | [ocr/main.py](https://github.com/arkohut/pensieve/blob/3e55c66edc871c0960b5dcc2315b62da558d17d8/memos/plugins/ocr/main.py)：Darwin 默认 ocrmac，其他情况 RapidOCR；metadata_entries 的 ocr_result 是 JSON 列表，rec_txt 为文字。中文质量和权限尚待真实验收 |
| 读取 | [server.py](https://github.com/arkohut/pensieve/blob/3e55c66edc871c0960b5dcc2315b62da558d17d8/memos/server.py)：GET /api/libraries/{libraryId}/folders/{folderId}/entities，limit <=400、offset、order_by；返回 Entity 数组。适配器使用 file_created_at:desc，不抓图片 |
| 实体格式 | [schemas.py](https://github.com/arkohut/pensieve/blob/3e55c66edc871c0960b5dcc2315b62da558d17d8/memos/schemas.py)：file_created_at、metadata_entries 等；无时区日期按配置的 IANA 时区转换 |
| 启动风险 | [commands.py](https://github.com/arkohut/pensieve/blob/3e55c66edc871c0960b5dcc2315b62da558d17d8/memos/commands.py)：memos enable 会配置 macOS launchd 且启动 record/serve/watch/healthcheck；memos start 会运行服务；本次均未执行。不要将安装等同于允许持续录屏 |

本机探测：PATH 中无 memos；8839 无监听；Python 3.12.13 可用。只检查命令和端口，不读取 ~/.memos 历史。可能存在其他目录安装，未做全盘扫描，因此不能宣称机器上绝对未安装。

安全与兼容性限制：当前只控制同步；Pensieve 原图独立保留。源码 OCR 插件可能记录识别片段到自身日志，不能把其原始日志上传公开仓库。实际实例启动前应让本人检查应用排除、存储目录、录屏权限和日志。没有修改任何 Pensieve 配置，也没有增加自启。
