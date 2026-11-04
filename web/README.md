# 网页（B）

Node 24，Vue 3 / TypeScript / Vite。所有依赖和锁文件在本目录。

```sh
npm ci
npm test
npm run build
npm run dev
```

开发入口 http://127.0.0.1:5173 ，`/api` 代理至 http://127.0.0.1:8080 。不内置访问码或模型密钥。访问码由本人输入；token 只存当前标签页 sessionStorage，退出或 401 清除。页面刷新保留当前会话，标签页关闭后重新登录。

生产静态产物为 `web/dist/`，由 E 的同源反向代理托管：`/api/` 转至 backend:8080，其余路径回退 `index.html`。只运行 `vite preview` 不会创建生产 API 代理。无需 B 的 Dockerfile，E 可在静态镜像构建阶段运行上面的 ci/build。

默认仅连接真实 API，没有离线伪数据或静默 mock 回退。返回 mode=mock 时页面显示测试替身横幅。文章及模型输出以纯文本渲染，不执行 HTML；外部来源/Wiki 链接只允许 http/https。

所有创建请求附 UUID 幂等键；同一次网络重试复用 key。HTTP 创建失败不自动重发，GET 临时失败有界退避。Job 每 2 秒轮询，3 分钟后提示稍后刷新，失败/注销停止。当前页面全局操作锁防止重复点击。

观察界面只修改服务端同步意愿，必须经本人点击确认；本地采集仍依赖 C 的明确授权握手。暂停后不声称底层采集停止。网页不请求屏幕权限、不发送桌面通知。

API 单元测试和浏览器契约替身冒烟只验证客户端行为，不证明真实模型、Wiki、观察、通知、持久化或真机成功。实际证据与剩余联调在 `handoffs/B.md`。
