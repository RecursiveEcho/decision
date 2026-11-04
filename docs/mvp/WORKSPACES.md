# 工作目录与分支

仓库：`https://github.com/RecursiveEcho/decision.git`。初始远程为空；不要假设 main 已有提交，不要为了创建 PR 擅自写入默认分支。

以下目录相对主工作区；具体机器绝对路径留在本地开工入口。

| 角色 | 物理目录 | 分支 | 开工卡 |
|---|---|---|---|
| E 总控 | `.` | `integration/mvp-20261104` | `docs/mvp/tasks/E-总控.md` |
| A 后端 | `../decision-worktrees/backend` | `feat/mvp-backend` | `docs/mvp/tasks/A-后端.md` |
| B 网页 | `../decision-worktrees/web` | `feat/mvp-web` | `docs/mvp/tasks/B-网页.md` |
| C 观察 | `../decision-worktrees/observer` | `feat/mvp-observer` | `docs/mvp/tasks/C-观察.md` |
| D 小程序 | `../decision-worktrees/miniprogram` | `feat/mvp-miniprogram` | `docs/mvp/tasks/D-小程序.md` |

每个新对话在自己的目录里先运行 `git status --short --branch` 和 `git log -1 --oneline`，确认不是另一个角色的分支，读取当前工作区的文档。当前任务卡要求创建业务模块，所以业务目录还未存在并不意味着交接失败。

基线提交包含开发材料、不含应用代码。初始共同基线可通过 `git merge-base <功能分支> integration/mvp-20261104` 核对。开始开发后分支自然分叉，不要求持续相等。

所有角色可以按已确认范围提交和推送自己的分支，不能 force push、修改仓库权限或自动合并 PR。E 集成各角色提交后推送 integration 分支。源对话不同时在四个工作区修改业务文件。
