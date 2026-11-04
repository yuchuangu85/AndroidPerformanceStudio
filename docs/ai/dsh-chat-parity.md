# DSH 聊天参照与对齐边界

日期：2026-11-01。范围：APS 独立 AI 聊天窗口，不是 DSH 整个平台重建。

## 参照来源

- 本机只读应用：`/Applications/DeepSeek Harness.app`，`Info.plist` 的
  `CFBundleShortVersionString` 为 **0.2.1-rc.5**。
- 只检查 `Contents/Resources/app.asar` 内的应用静态资源；未读取 DSH 用户配置、
  会话、账号、令牌或工作区内容，也不把参考应用资源复制进产品。
- 以下路径均相对 ASAR 内 `dsh/node_modules/@deepseek-ai/`：
  - `dsh-client-ui-workspace/lib/client.js:5256-5284`：菜单按 pin、rename、fork、archive、
    session-id 注册；该包 `README.md` 的 Session row actions 说明运行中归档确认。
  - `dsh-client-ui-conversation/README.md:99-111`：常驻编辑器、IME、Enter/Shift+Enter、
    互补 Ctrl/Cmd+Enter、Queue/Steer、附件和持久化 Inbox。
  - `dsh-client-ui-conversation/lib/client.js:17384`：聊天列/输入框 CSS，聊天列
    `clamp(680px, columnWidth × 0.64, 920px)`，输入框最大宽度为聊天列加 32px。
  - `dsh-client-ui-chat/README.md:67-91`：精确用量、消息动作及工作过程；`:135-156`：
    回到底部、独立滚动跟随与聊天可用宽度不超过 900px 时隐藏轮次导航轨。
  - `dsh-client-ui-theme/lib/client.js` 的注入 CSS：主题 tokens；不是从网页主 CSS
    文件推断聊天组件颜色。正文与气泡 CSS 由 chat/conversation 包提供。

这些是该版本静态行为/样式证据，不替代运行中的截图或原生交互验收。应用升级后应重新核对。

## 本轮产品契约

### 纯聊天与管理迁移

`AiConversationWindowHost.kt` 只组装会话侧栏、消息、工作过程和输入区。
Provider、Agent、Skill、Policy 与连接全部进入 `DesktopAppSettingsPage.kt` 的 AI 设置分类，
复用共享 settings store。聊天中的模型和设置入口导航到真实设置页，不在两个位置维护表单。

旧的常驻证据右栏已被用户新要求替代：引用仍是消息的结构化动作，canonical Evidence
详情按需打开；Source 只走授权候选导航，模型正文不是路径权限。

### 布局与视觉 tokens

- 侧栏 260dp；默认窗口 1200×800，最小 900×720；不足 1000dp 默认折叠侧栏。
- 聊天列以可用宽度的 64% 为目标，限制在 680–920dp 并按实际小窗口空间收缩；
  输入框最大为聊天列加 32dp。用户消息右对齐并限制宽度。
- 正文 14sp/24sp；用户气泡 14sp/22sp、内边距 10dp/16dp；空白标题 26sp/32sp/500。
- 圆角 8/16/20/28dp；发送按钮 34dp 圆形。
- 浅色：底 `#FFFFFF`、文字 `#0F1115`、发送 `#4176E6`、气泡 `#EDF3FE`。
- 深色：底 `#151517`、文字 `#F9FAFB`、输入/气泡 `#2C2C2E`、发送 `#7AAAFF`。
- tokens 仅在聊天局部生效，其他 Profiler 继续使用 ViewerTheme。

### 真实会话和输入

`ChatHistory.kt`、`ApplicationAiService.kt` 和 `ConversationRepository.kt` 提供真实会话列表、
逐 turn 问题/结构化结果、置顶、重命名、归档、恢复和新建操作，不用静态示例冒充历史。
新问题与真实 runId/generation 一起存储，问题/标题先有界脱敏；旧记录缺少字段时不编造。
恢复/导航/元数据操作不发起模型请求。

多轮背景由 `MainAgentOrchestrator` 从同 Analysis Session/context 的成功记录组装，最多
4 个 turn，每段问题/回答最多 2048 字符，独立序列化为不可信背景；批准前冻结并计入预算。
它不增加性能 Evidence/Candidate allowlist，也不继承前一轮的发送权限。

没有打开性能报告时，输入区允许普通对话：使用明确的 `CHAT/CONVERSATION` 空上下文和
独立 `aps.chat` Agent，仍先预检、再显式批准。请求不伪造性能 Evidence，不包含 Source，
不运行 Skill、子 Agent、脚本或 Artifact 发布；回复只放在 `summary`，`findings=[]`。
恢复普通对话及当前仍可用的报告对话仅接受完全匹配的 canonical session/hash；证据完全匹配时，
恢复报告对话不再要求重复手工绑定，不发起 Provider 请求。源码会话即使匹配也保持发送关闭，
需显式重新授权源码。证据或源码授权缺失时，输入区分别显示直接恢复操作及“新建普通对话”；
后者保留草稿但创建独立的 `CHAT/CONVERSATION` 空上下文，不沿用报告证据、源码或授权。
缺失报告绑定的历史分析不能自动降级成普通聊天，也不从另一报告补齐同名 ID。
CPU 页分析入口与全局入口共用 canonical 请求构造，保持 session、prompt/payload 版本一致；
旧随机 session 历史不自动迁移或绕过 hash 校验。原有报告分析仍要求真实非空证据和独立源码授权。

Enter、Shift+Enter、IME、空输入上箭头、两次独立 Escape、新建/搜索快捷键、忙碌排队、
问题编辑/移除、复制反馈和回到最新消息是本轮实现目标。每轮仍需 APS 实际 immutable payload
预检和显式确认；DSH 的普通 Send 不能作为绕过 APS 授权的理由。

## 明确保留的差距

- **不是完整 DSH 复制**：不同 UI 框架、字体栅格、原生窗口和状态模型。tokens 相同不等于
  像素一致；没有相同内容的运行时参考截图/测量时，不宣称像素对齐。
- **附件与引用编辑器**：尚无 DSH 的图片/文件上传、拖放、粘贴附件、文件夹路径桥接、
  原子引用/slash chips、上传进度/重试。APS 上下文菜单只是已授权业务证据，不冒充文件上传。
- **队列与 Steer**：当前是本地有界、按会话隔离的待处理问题，不是持久化 Inbox、
  跨客户端恢复或运行中 steering；没有 DSH 的 Queue/Steer 设置及 Ctrl/Cmd+Enter 互补模式。
- **内容呈现**：结构化 Findings 与安全正文不是完整 DSH Markdown/图片灯箱/任意文件或
  HTTP 导航；原生 Markdown 显示子集已覆盖段落、六级标题、嵌套有序/无序/任务列表、引用、
  管道表格、分隔线、嵌套加粗/斜体/删除线、行内代码和代码围栏。HTTP(S) 链接显示标签样式但
  不执行导航；HTML、图片和不安全链接保持字面文本，不加载远程资源。代码块提供语言、复制、
  自动换行和常见代码片段的轻量着色，不冒充 Shiki 语法全集。数学公式、脚注及完整 CommonMark
  兼容仍未实现。消息动作常显，尚未复现 DSH
  历史消息 hover/focus 显隐。只读 HTML Artifact 打开不等同 Markdown 导出或 DSH Artifact 插件。
- **运行细节**：现有宿主审计时间线不等同 DSH 的四档 Work details、思考流分组、
  独立折叠时机、额度/压缩恢复等全部状态。
- **计量和导航**：无真实 Provider 完整用量时不显示虚构 Token、TTFT、速度、缓存命中率或
  ContextMeter；当前未实现 DSH 轮次导航轨和用户拖宽聊天列。
- **会话范围**：APS 性能分析要求绑定不可变性能证据；普通聊天不开放通用 Workspace 工具。
  DSH 的任意 Workspace 操作、通用文件工具、
  终端、浏览器、Goal/Plan、Schedule、插件市场和远程客户端不是本轮权限范围。
- **历史显示**：新分支只保存本分支真实 turn；父会话只作为有界授权背景，不伪造分支前文。
  历史 canonical 证据/源码绑定缺失时明确 unavailable，不回填别的报告。

## 验证口径

### 2026-11-03 Markdown 显示

- 参照同一只读 0.2.1-rc.5 安装包中的 `dsh-web-frontend/dist/assets/index-BNyAYEM7.css`
  Markdown typography（29596–34035）和代码块（57429–59856），以及
  `dsh-client-ui-chat/lib/client.js:6252-6311` 的 Markdown 消息容器；不复制第三方实现。
- 正文保持 14sp/24sp，段落间距 16dp；h1/h2/h3 为 21/30、19/28、18/26，前距 32dp，
  其余标题保持正文尺寸。引用为 2dp 左线和 14dp 内缩，列表项间距 6dp。
- 摘要、Finding explanation/recommendation 共用惰性原生 renderer。表格仅在自身视口横向滚动，
  代码块有界于聊天列，默认横向滚动，可切换换行；复制使用未经着色改写的源代码。
- 块嵌套限制 12 层，行内解析设深度和工作量限制；超限显示原文，不吞掉内容或扩张业务权限。
- Compose 480/900/1280 明暗截图、解析和交互测试不等同同内容 Harness 像素对齐、原生键鼠或
  真实 Provider 验收。

组件/宿主 tests、存储 reopen 与损坏记录 tests、编译/静态检查、语言/主题/宽窄截图、
原生键鼠、真实 Provider 和签名/安装包分别记录。最终执行结果由任务报告及实际构建日志提供；
本文不预先写入通过数量，不把未运行测试、假 Provider 或截图当作完整 parity/Release 证据。

### 2026-10-31 本地验证快照

- 从 `desktop-viewer` 串行运行 `:ai-platform:checkAll :ai-core:test :desktop-app:test
  :desktop-app:createDistributable` 通过；补充截图测试后再次运行 presentation test/KtLint 通过。
- 最终 XML：AI Platform 294 项（293 通过、1 live opt-in 跳过）、ai-core 46 项通过、
  desktop-app 283 项（275 通过、8 条平台/live 条件跳过），零失败。
- 20 张 Compose 截图覆盖纯组件/真实宿主的空态，以及 EN 深色/中文浅色消息和冻结审批；
  已目视检查宽/窄消息布局与发送按钮右对齐。非同内容 DSH pixel-diff 或原生跨平台验收。
- `ai-core:detekt` 仍有 7 条诊断，全部在本轮未改的 `OpenAiResponsesClient.kt` 及其测试；
  本轮聊天 contract/payload/history 相关文件无诊断。不能把这一结果称为整体静态检查通过。
- 应用镜像构建通过且包内包含 Chat/管理组件；未替换已安装应用、未发真实 Provider 请求、
  未签名/公证、未提交或推送。日志、XML 和截图归档于 `.omx/verification/dsh-chat-2026-10-31/`。
