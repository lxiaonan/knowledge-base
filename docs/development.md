> 前后端与部署文件统一位于 `main`，分别存放在 `backend/` 和 `frontend/`。此页介绍本地开发，完整容器部署请看 [部署指南](deployment.md)。

# 知序 · 企业知识库

基于 **JDK 21 + Spring Boot 3.5.7 + Spring AI 1.1.0 + Vue 3 / TypeScript** 的企业文档检索与问答应用。

当前本地方案：**Ollama BGE-M3 生成向量 → Docker PostgreSQL 17 / pgvector 检索 → `ai-api.m2time.com` / `gpt-6.1-sol` 生成回答**。原始文件保存在本地文件夹，MinIO 适配保留供后续切换，当前不部署 MinIO。

| 功能 | 实现 |
| --- | --- |
| 登录与权限 | Session + BCrypt + CSRF，知识库成员权限 |
| 文档管理 | PDF、DOCX、Markdown、TXT 上传、异步处理、失败重试、删除 |
| 在线浏览 | Markdown 排版，其他格式全文文字预览，PDF 保留页码 |
| 原始文件 | `backend/data/files/`，下载前校验权限 |
| 文档向量 | 本机 Ollama `bge-m3`，1024 维 |
| 真实检索 | pgvector 余弦相似度与 HNSW 索引，限定知识库范围 |
| 问答 | Spring AI 调用指定对话 API，SSE 输出，附可点击引用 |
| 聊天排版 | Markdown 标题与列表、可滚动表格、代码高亮与复制、KaTeX 数学公式、正文引用点击查看来源 |
| 独立检索测试 | 「语义检索」页面，无需对话 API 密钥 |
| 历史 | 私有聊天记录、回答时的引用快照 |
| 数据查询 | 管理员可视化浏览 8 张业务表，搜索、知识库/状态筛选、分页、记录详情与向量分布 |

**没有配置对话密钥时，文档上传、向量索引和语义检索仍可运行；系统明确提示不能生成回答，不会自动返回演示答案。**

## 本地启动

需要 JDK 21、Maven 3.9+、Node.js 22.12+、Python 3、Docker Desktop Linux 引擎、Ollama。

首次准备：

```sh
python scripts/local_setup.py
docker compose up -d postgres
ollama pull bge-m3
python scripts/dev.py frontend-install
```

`local_setup.py` 仅在 `.env` 不存在时创建配置，生成数据库和初始管理员密码，不打印密码；已有 `.env` 不会被覆盖。

两个终端分别运行：

```sh
python scripts/dev.py backend-run
```

```sh
python scripts/dev.py frontend-run
```

访问 **http://127.0.0.1:5173**。前端将 `/api` 代理到后端 `8080`。

辅助脚本只为本项目选择 JDK，不更改系统默认 Java。优先读取 `KB_JAVA_HOME`，其次识别本机 `~/.jdks/oracle_open_jdk-21`，否则使用 `JAVA_HOME`。按 Ctrl+C 停止对应进程。

使用该脚本启动后端时，会自动加载根目录 `.env`；直接在 IDE 或 Maven 中启动，则需自行设置环境变量。

## 配置对话服务

管理员登录后，在左侧点击 **「设置」→「对话模型」**，填写 API 地址、模型和密钥。默认已提供 `https://ai-api.m2time.com` 与 `gpt-6.1-sol`。点击「测试连接」可发送一条独立测试消息，点击「保存设置」后立即生效，无需重启。

密钥不会回显；留空保留已配置的密钥，需要移除时点击「清除密钥」。仅管理员可以读取或修改设置，普通成员无权调用设置接口。对话配置使用 AES-256-GCM 加密存入数据库，本机加密文件为 `backend/data/config/settings.key`，备份数据库时需要一起备份此文件。

界面保存的配置优先于环境变量。未在界面保存时，也可以用根目录 `.env` 提供初始配置：

```dotenv
AI_BASE_URL=https://ai-api.m2time.com
AI_CHAT_MODEL=gpt-6.1-sol
AI_API_KEY=填写自己的密钥
```

通过环境变量修改初始配置时需重启后端；已有界面配置时请直接在设置中修改。密钥只在后端读取，`.env` 已被 Git 忽略。当前适配是 `/v1/chat/completions` 的流式协议；根地址不带 `/v1`。真实账号授权、供应商模型可用性与调用效果，需要填写密钥后再验证。

Embedding 使用独立配置，绝不把文档发给对话服务生成向量：

```dotenv
EMBEDDING_BASE_URL=http://127.0.0.1:11434
EMBEDDING_API_KEY=ollama
AI_EMBEDDING_MODEL=bge-m3
EMBEDDING_DIMENSIONS=1024
EMBEDDING_SEND_DIMENSIONS=false
RETRIEVAL_MIN_SCORE=0.55
```

`ollama` 是兼容客户端所需的占位值，本机 Ollama 不要求真实 API 密钥。模型、服务地址、向量维度会写入配置指纹；索引已有资料时更换这些配置会阻止启动，需要专门重建向量，不能混用不同模型生成的向量。

详细部署、模型开源信息、旧资料迁移与配置说明见 **[本地真实 RAG 指南](local-live.md)**。

## 数据库与文件

PostgreSQL：`127.0.0.1:55432`，数据库 `knowledge_base`，账号 `knowledge`，密码见本机 `.env` 的 `DB_PASSWORD`。数据库保存于 Docker 命名卷。

当前文件存储配置：

```dotenv
STORAGE_TYPE=local
STORAGE_PATH=./data/files
```

相对路径以运行目录 `backend/` 为基准。数据库和原始文件需要一起备份。

旧演示数据迁移后保留账号和密码，包括原 `admin` / `DemoAdmin123!`。新空数据库的管理员密码使用 `.env` 中的 `BOOTSTRAP_ADMIN_PASSWORD`；正式模式不自动创建默认演示账号。

## 使用与验证

管理员登录后，点击左侧 **「数据查询」**，即可直接查看当前 PostgreSQL 中的知识库、文档、知识片段、对话、消息、用户、成员授权和系统参数。数据表支持关键词搜索、按知识库与状态筛选、每页 20/50/100 条分页及刷新。

点击一行可打开完整记录详情。知识片段详情包含原文、页码、向量维度、分布图及全部向量数值，并支持复制 JSON。查询使用固定表和固定字段的只读接口，不提供任意 SQL 或数据修改操作；密码哈希、密钥和系统参数值在后端排除，不会发送到浏览器。

数据查询属于管理员功能，可审阅跨用户、跨知识库的业务记录；普通成员无法访问。正常聊天页面的个人会话权限保持独立。

1. 上传文档，等待状态变为「可检索」。
2. 在「语义检索」输入同义改写问题，检查是否召回正确资料。
3. 点击「阅读全文」核对原文。
4. 配置对话密钥后，在「知识问答」提问并验证回答引用。

```sh
python scripts/dev.py backend-test
python scripts/dev.py frontend-check
docker compose --env-file .env.example config --quiet
```

真实本地依赖验证脚本：`scripts/verify_local.py`。它会检查本地模型返回维度、PostgreSQL 向量扩展、已生成向量数量及实际语义检索；登录密码通过 `KB_VERIFY_PASSWORD` 环境变量传入，不打印。

已完成的检查与实际验证边界见 [验证记录](verification.md)。

本轮首页、文档筛选与排序、上传反馈、聊天阅读位置及按需刷新等改进见 [知识空间体验优化](workspace-optimization.md)。

## 项目结构

```text
backend/src/main/java/com/company/knowledge/
  AuthController / SecurityConfig  账号、登录、CSRF
  KnowledgeController / Access     知识库及权限
  DocumentController / Service     上传、预览与文档任务
  DocumentParser                  解析、按来源分段
  FileStorage                     本地文件 / MinIO 适配
  AiService                       独立 Embedding 与对话服务
  RetrievalService                pgvector 语义检索
  ChatController                  检索测试、SSE、对话与引用
  DemoImport                      只读导入旧 H2 资料并重建向量
backend/src/main/resources/db/migration/  Flyway
frontend/src/components/           全文阅读器、语义检索页
frontend/src/api.ts                Session / CSRF / SSE
compose.yml                        默认只启动 PostgreSQL
.env.example                       配置示例，不含真实密钥
```

## 业务边界

管理员可访问全部知识库；所有者和管理员管理成员；知识库成员可以查看、上传、删除资料并提问。个人聊天历史仅本人可见，并继续检查知识库权限。

上传限制为 20 MB/文件、200 份/知识库、PDF 最多 500 页、解析文字最多 100 万字符。扫描件需要先进行 OCR。PDF/DOCX 在线预览为文字内容，原始图片与版面需下载原文。

文档按约 900 字符分段，约 100 字符重叠；后台任务状态持久化，重启可恢复；全部向量成功后以事务发布索引。删除文档清理检索片段，历史引用快照保留。

问答输入区提供「结合上下文」开关，默认关闭。打开后仅读取当前用户、当前对话最近 3 轮已完成的问答，用对话模型把追问补全为独立问题，再调用本地 Embedding 和 pgvector 检索；生成答案时历史仅用于理解，不作为事实证据。界面可展开查看本次补全的检索问题。此步骤会增加一次模型调用，未完成的问答不参与后续上下文；新对话不继承其他对话内容。

上下文改写失败时会明确提示，可以重试或关闭开关改用完整问题，不会悄悄退回独立检索。关闭开关或首轮无历史时无需改写。「语义检索」页面依旧是独立检索，不携带聊天历史。

可以为一本经济学书创建单独知识库，上传文字型 PDF/DOCX/MD/TXT，处理就绪后按内容提问并核对引用。当前单文件 20 MB、PDF 最多 500 页；更大的书可按章拆分。没有 EPUB 解析、扫描件 OCR 或复杂公式/图表理解。片段检索适合查找局部答案，不等于一次完整阅读全书；全书总结、跨章推导仍可能遗漏内容。

语义检索门槛需要用实际问题集校准；尚未加入关键词混合检索、Reranker、OCR、资料版本管理、SSO、审计、密码重置及登录限流。

应用当前支持单实例。公网部署需配置 HTTPS 及组织账号体系。可选容器应用配置使用 `--profile app`，详情见本地真实 RAG 指南。

## 保留的演示模式

需要离线演示原文匹配时，可显式设置 `SPRING_PROFILES_ACTIVE=demo`。它使用 H2、本地文本匹配和原文摘录，页面明确标识。默认本地 `.env` 使用 `live`，不会因真实模型或数据库失败自动降级到演示模式。
