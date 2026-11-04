# Decision — 个人 AI 决策助理

仅本人使用。目标是把对话、本人提供的资料和经授权的电脑观察，变成有来源、可纠正、可复盘的决策讨论。

**网页已在本机更新：直接聊天、图片/文档附件、四档思考、自动记忆与明确决定记录。真实图文、跨对话记忆、纠正/忘记和重启恢复已验证，详见 [本轮验收](docs/mvp/CONVERSATIONAL_ASSISTANT.md)。**

本机打开 http://127.0.0.1:8088 ，访问码在忽略的 `.local/login.txt`。重启用 `python3 scripts/manage.py up`，停止用 `python3 scripts/manage.py stop`（数据保留）。另一台机器首次使用请按 [运行说明](deploy/README.md) 配置和构建。

录屏保持关闭；小程序尚无真机验收，手机通知和远程服务器尚未部署。

## 开发入口

1. [当日 MVP 与并行流程](docs/mvp/PLAN.md)：范围、负责人与集成顺序。
2. [共享接口约定 v1](docs/mvp/CONTRACT.md)：四个开发对话的共同起点。
3. [开工任务卡](docs/mvp/tasks/README.md)：A 后端、B 网页、C 观察、D 小程序、E 集成。
4. [用户试用清单](docs/mvp/USER_TEST.md)：工程师完成自检后，由本人测试。
5. [交接状态](PROJECT_STATE.md)：实际进度与外部依赖。
6. [本地运行与备份](deploy/README.md)：环境配置、独立基础服务与运行命令。

执行基线：Java 17 / Spring Boot 3.5.x / PostgreSQL；Vue 3 + Vite + TypeScript；原生微信小程序；Python 本地观察适配器。DeepSeek 已完成真实调用；Wiki.js 2.5.317 已完成实际创建、更新与回读；Pensieve 0.37.0 已安装核验，真实 OCR/录屏待本人明确开启后验证。模块依赖及镜像版本已锁定。

不在公开仓库保存真实个人目标、聊天、文章全文、截图、运行数据库、账号或密钥。测试使用虚构样例，真实资料在运行时由本人提供。

## 官方资料

- [Spring Boot 3.5 环境要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Vue 创建项目](https://vuejs.org/guide/quick-start.html)
- [DeepSeek API](https://api-docs.deepseek.com/)
- [Pensieve](https://github.com/arkohut/pensieve)
- [Wiki.js](https://github.com/requarks/wiki) / [当前 API 文档](https://docs.js.wiki/dev/api)
- [微信订阅消息](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/subscribe-message-overview.html)

以上资料已于 2026-11-04 查询。Wiki.js 不同主版本接口不同，开发前按实际版本核实，不沿用旧 GraphQL 示例假装接入成功。
