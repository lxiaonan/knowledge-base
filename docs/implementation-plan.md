# 企业知识库：第一版实施约定

## 范围
- JDK 21、Spring Boot 3.5.7、Spring AI 1.1.0。
- Vue 3、TypeScript、Vite，中文桌面工作台及移动端适配。
- 会话登录、管理员/普通成员、知识库成员权限；权限在查询和文件下载前校验。
- PDF（逐页）、DOCX、Markdown、TXT 上传；异步解析、重试、状态展示、删除及来源预览。
- 文档按段落切分，保留来源；使用配置的 Embedding 模型及 pgvector 进行真实语义检索。
- 问答以 SSE 返回，保留会话；明确无依据的回答；支持引用来源。
- 本地正式配置使用 Docker PostgreSQL + pgvector、Ollama BGE-M3（1024 维）与本地文件夹存储。
- 对话服务使用 `https://ai-api.m2time.com` / `gpt-6.1-sol`，与本机 Embedding 服务分别配置地址和密钥。
- 对话密钥缺失不阻止本地向量索引；不生成模拟回答，支持独立「语义检索」测试。
- 显式 `demo` 配置仍保留 H2、文本匹配/原文摘录，作为可选演示模式；不会自动降级。

## 接口约定
- `/api/auth/csrf` GET 获取 CSRF token；`/api/auth/login` POST 登录；`/api/auth/logout` POST 退出；`/api/auth/me` GET 当前用户。
- `/api/knowledge-bases` GET/POST；`/{id}/members` GET/POST/DELETE；只有所有者和管理员可管理成员。
- `/api/knowledge-bases/{id}/documents` GET/POST（multipart file）；`/api/documents/{id}` DELETE；`/{id}/retry` POST；`/{id}/file` GET。
- `/api/documents/{id}/preview` GET：校验知识库权限后读取原始文件，返回 `{id,name,format,pages:[{page?,content}]}`；Markdown 渲染排版，TXT/DOCX 展示全文文字，PDF 按页展示文字。不可解析的文件返回 422；不依赖分段状态，不拼接重叠片段。
- `/api/knowledge-bases/{id}/conversations` GET/POST；`/api/conversations/{id}/messages` GET；`/{id}/chat` POST（SSE）。
- `/api/sources/{chunkId}` GET；必须经过所属知识库权限校验。
- `/api/system` GET 返回运行模式与支持格式，不泄露密钥。
- `/api/admin/data/overview` GET 返回数据库基本信息、8 张允许查询的业务表及数量；`/api/admin/data/tables/{table}` GET 支持关键词、知识库、状态及分页；`/api/admin/data/tables/{table}/rows/{key}` GET 返回详情和实际向量数值。所有接口仅管理员可用。
- 数据查询仅使用后端固定的表、字段和排序，筛选值绑定参数；页码 1–1000000、每页 1–100、关键词最多 200 字符。无效参数 400，未授权 403，未开放表/不存在的记录 404。密码哈希和设置值不出现在列表或详情；配置表仅展示名称及隐藏状态。成员授权使用复合主键。
- `/api/settings/chat` GET/PUT、`/api/settings/chat/key` DELETE、`/api/settings/chat/test` POST：仅管理员可使用，写请求验证 CSRF。读取只返回地址、模型和密钥是否已配置；空密钥保留现有值，清除需走独立接口。保存立即生效。测试使用输入的草稿配置，仅发送短测试消息，不自动保存。
- 对话配置以 AES-256-GCM 加密后保存在现有设置表，本机加密文件位于 `backend/data/config/settings.key`；界面配置优先于环境变量。备份需同时包含数据库和加密文件。
- `/api/knowledge-bases/{id}/search` POST `{question}`：CSRF 与知识库鉴权后调用本地 Embedding 模型和 pgvector；返回来源列表，不调用对话模型。
- 所有写请求携带会话 Cookie 与 `X-CSRF-TOKEN`；密码由 BCrypt 存储。
- SSE 事件：`sources`（引用列表）、`token`（文本增量）、`done`（持久化消息）、`error`（可读失败原因）。
- 问答请求为 `{question,useContext?:boolean}`，缺省 false；开关只影响当前问答接口，独立 search 接口保持原状。后端鉴权后读取本对话最多 16 条近期消息，挑选最近 3 轮完整问答，每条上限 2000 字符，移除历史引用编号，不接受客户端提供的历史。
- 开启且存在完整历史时，对话模型输出 `{query}`，通过 JSON、非空和 3000 字符上限校验后用于本地向量检索。失败通过 SSE error 明确返回，不隐式降级。历史只用于理解指代，答案必须由本次引用支持。
- 新增 SSE `status:{message}` 和 `context:{enabled,used,query,turns}`，用于展示阶段和本次补全问题；此元数据只在本次流中展示，不写入原消息表。当前开关为页面会话选项，刷新后默认关闭；对话历史只来自服务端当前对话。SSE 期限 180 秒，Nginx 读取期限 210 秒。
- 输入错误 400、未登录 401、无权限 403、资源不存在 404、冲突 409；错误返回统一 `{message}`。

## 数据与配置
- Flyway 管理用户、知识库、成员、文档、片段、会话及消息表。
- 文件与向量归属文档；删除文档级联清理片段；消息保留回答时的引用快照。
- 后台任务以文档状态为事实来源，进程重启后恢复处理中的文档。
- PostgreSQL 的向量维度由 `EMBEDDING_DIMENSIONS` 指定，必须与模型一致；更换模型需要重建索引。
- 配置变量和部署步骤记录在 README 与 `.env.example`。

## 验收
- 正常：上传中文制度 → 处理完成 → 提问 → 回答引用该制度 → 打开原文。
- 基础：不提供模型密钥也能以明确标识的演示模式运行完整流程。
- 异常：越权读写/下载被拒绝；空文档、非法文件、模型失败有明确提示；CSRF 验证生效。
- 后端测试覆盖权限边界、上传/检索/引用/删除，以及无依据处理。
- 前端通过类型检查与生产构建，并实际检查登录、上传和问答页面。

## 当前环境
- 空目录，无 `.trellis/`、`.codegraph/` 或已有项目规范。
- 已发现可使用的本地 JDK 21；不会更改系统默认 Java。
- Docker 引擎已恢复，本地 BGE-M3 已安装；真实向量验证见验证记录，密钥配置流程见 `docs/local-live.md`。
