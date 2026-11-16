# 本地真实 RAG：PostgreSQL + Ollama BGE-M3

## 当前架构

```text
文档 → Java 解析与切分 → 本机 Ollama / bge-m3 → 1024 维向量 → Docker PostgreSQL / pgvector
问题 → 同一个本地向量模型 → 在有权限的知识库内检索 → 引用片段
引用片段 + 问题 → https://ai-api.m2time.com → gpt-6.1-sol → SSE 回答
原始文件 → backend/data/files/ 本地文件夹
```

Embedding 与对话模型是两套独立服务。缺少对话密钥不会阻止文档建立向量索引，也不会切换成假回答。可以先用「语义检索」测试真实匹配结果。

## 本地向量模型

采用 [BAAI/bge-m3](https://huggingface.co/BAAI/bge-m3)，开源 MIT 协议，支持中英文，稠密向量为 1024 维。模型在本机 Ollama 中运行，无需向量服务 API 密钥；首次下载模型约 1.2 GB，之后离线推理。

```sh
ollama pull bge-m3
ollama list
```

Ollama 根地址默认 `http://127.0.0.1:11434`。应用使用其兼容的 `/v1/embeddings` 接口。对本地固定维度模型不发送可选 `dimensions` 字段，并检查实际返回维度，防止模型配置不一致。

另一个适合轻量中文测试的选择是 [BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)（MIT，512 维），可以通过 ONNX/FastEmbed 服务部署。本项目本次实际接入的是 BGE-M3，不会同时启动两个模型。

## PostgreSQL

Docker Desktop Linux 引擎启动后，在项目根目录执行：

```sh
python scripts/local_setup.py
docker compose up -d postgres
docker compose ps
```

PostgreSQL 17 镜像包含 pgvector，只将端口发布到 `127.0.0.1:55432`。数据库名 `knowledge_base`，账号 `knowledge`，密码保存在本机 `.env` 中。数据位于 Docker 命名卷，不需要每次重新创建。

```sh
docker compose exec -T postgres psql -U knowledge -d knowledge_base -c "SELECT extversion FROM pg_extension WHERE extname='vector';"
```

Flyway 首次启动创建结构、`vector(1024)` 列以及 HNSW 索引。每条检索 SQL 都限定知识库，并在返回结果前再次检查权限。

## 启动应用与设置密钥

`scripts/dev.py backend-run` 自动读取根目录 `.env`，环境变量优先，不会把密码或密钥打印到控制台。

```sh
python scripts/dev.py backend-run
python scripts/dev.py frontend-run
```

浏览器访问 `http://127.0.0.1:5173`。`.env` 中已设置：

```dotenv
SPRING_PROFILES_ACTIVE=live
STORAGE_TYPE=local
STORAGE_PATH=./data/files
AI_BASE_URL=https://ai-api.m2time.com
AI_CHAT_MODEL=gpt-6.1-sol
AI_API_KEY=
EMBEDDING_BASE_URL=http://127.0.0.1:11434
EMBEDDING_API_KEY=ollama
AI_EMBEDDING_MODEL=bge-m3
EMBEDDING_DIMENSIONS=1024
EMBEDDING_SEND_DIMENSIONS=false
RETRIEVAL_MIN_SCORE=0.55
```

管理员可直接在页面左侧「设置 → 对话模型」输入密钥，测试后保存。界面保存即时生效，无需重启，且优先于 `.env`。读取设置仅返回密钥是否已配置；输入框留空保留已有密钥，清除必须使用「清除密钥」按钮。

界面设置加密保存于数据库，对应本机加密文件位于 `backend/data/config/settings.key`（AES-256-GCM）。备份需要保留数据库和这个文件。应用容器已配置对应目录挂载；不要丢弃加密文件，否则无法读取已保存密钥。

未保存过界面设置时，可以把自己的密钥填入 **`AI_API_KEY=`** 并重启后端，作为初始配置。不要提交 `.env`。界面不会把已保存密钥返回浏览器，也不会放入 localStorage。

API 根地址不包含 `/v1`；当前适配使用 `/v1/chat/completions` 流式协议。模型名按用户指定保持为 `gpt-6.1-sol`。供应商的实际账号授权、模型可用性及协议响应需在密钥配置后验证，不能仅凭根网站可打开就认定调用成功。

## 旧演示资料迁移

原 H2 数据库不覆盖、不删除。首次切换前停止旧后端，把 `backend/data/demo.mv.db` 备份到 `.local/backups/`，将备份路径去掉 `.mv.db` 后填入 `IMPORT_DEMO_PATH`。文件路径建议使用 `/`。

仅在目标数据库为空时进行导入。导入以单个事务复制账号、知识库、成员、文档、片段、对话和消息；文档标记为排队，随后使用本地模型重新生成向量。成功后写入标记，重复启动不会重复导入。账号与密码哈希保留不变。

知识片段重建会沿用原有片段编号对应的 ID，使旧引用尽可能继续可用。历史演示回答依旧保留为原文摘录，不会改写为模型生成的历史。

导入完成后清空 `IMPORT_DEMO_PATH` 即可。新的空环境没有旧数据库时，会使用 `.env` 中的 `BOOTSTRAP_ADMIN_PASSWORD` 创建 `admin`。

## 检索与验证

前端「语义检索」只调用：

```text
POST /api/knowledge-bases/{id}/search
{"question":"外地出差住酒店可以报销多少钱？"}
```

接口要求登录和 CSRF token，返回资料来源、片段和余弦相似度，不调用对话 API，不把结果包装成生成答案。结果分数是相似度，不是答案正确率。

建议使用同义改写问题进行验证，如「外地住酒店能报多少钱」检索「出差住宿标准」；再用无关问题验证拒答。门槛 `RETRIEVAL_MIN_SCORE` 需要结合自己的语料校准。

当前检索使用真实向量相似度排序，尚未加入关键词混合召回和重排序模型。问答中的「结合上下文」默认关闭；打开后使用当前对话最近 3 轮完整问答补全追问，再进行真实向量检索。补全需要调用已配置的对话模型，首轮没有历史则跳过。界面展示实际检索问题，历史答案不作为当前回答的事实依据，引用只对应本次检索资料。

## 可选：将应用也运行到容器

```sh
docker compose --profile app up -d --build
```

应用容器默认访问 `host.docker.internal:11434`，要求宿主机 Ollama 允许该连接。当前交付采用 Java/前端本机运行、PostgreSQL 容器运行，无需开放 Ollama 到局域网。不要同时运行本机后端与容器后端访问同一库：当前文档任务调度只支持单实例。
