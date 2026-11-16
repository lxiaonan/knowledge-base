# Docker 一键部署与运维

## 分支与构建约定

`main` 只维护部署文件和文档；`backend` 分支包含 `backend/`，`frontend` 分支包含 `frontend/`。Docker BuildKit 从公开 GitHub 仓库读取对应分支和子目录，使用各自 Dockerfile 多阶段构建，不依赖宿主机 Java / Node.js / Ollama。

```sh
git clone --branch main https://github.com/lxiaonan/knowledge-base.git
cd knowledge-base
python scripts/deploy.py up
```

需要 Docker Compose v2.20+、Python 3.9+，以及到 GitHub、Docker Hub、Maven Central、npm 和 Ollama 模型仓库的网络连接。使用 CPU 推理，建议 8 GB 以上可用内存、至少 15 GB 磁盘空间。首次下载和构建可能需要十几分钟或更长，取决于网络与机器性能。

## 启动流程

1. 检查 Docker 和 Compose，首次生成随机数据库密码与管理员密码，写入 `.env.deploy`。已有文件不会覆盖，也不会自动更改已有管理员密码。
2. 构建 Java 与 Vue 镜像，启动 PostgreSQL / pgvector 和 Ollama。
3. 一次性 `model-init` 任务下载 BGE-M3，成功退出后才启动后端；后端启动执行 Flyway 迁移并创建 `admin`。
4. 后端健康检查通过后启动 Nginx，默认发布 `127.0.0.1:8088`。

`model-init` 的 `Exited (0)` 是成功状态；其他四个长期运行服务应为健康状态。启动脚本最多等待健康状态 30 分钟，整个构建与启动命令超时为 60 分钟。超时后先检查日志，解决网络或资源问题后重新运行启动命令；已下载的镜像层与模型会复用。

```sh
python scripts/deploy.py status
python scripts/deploy.py logs
python scripts/deploy.py password
```

`password` 只读取初始配置中的密码，不修改账号。密码不会在启动输出中打印。首次登录后在设置页配置自己的对话服务，无密钥时语义检索仍可使用。

## 配置

部署使用 `.env.deploy`，与开发环境 `.env` 独立。首次启动自动创建，也可以复制 `.env.deploy.example` 后自行填写两个强密码。管理员密码至少 12 个字符、至多 72 个 UTF-8 字节。

| 参数 | 默认值 / 用途 |
| --- | --- |
| `WEB_BIND` | `127.0.0.1`；局域网访问改为 `0.0.0.0` |
| `WEB_PORT` | `8088` |
| `DB_PASSWORD` | 自动生成；数据库初始化后不能只改此值，否则认证失败 |
| `BOOTSTRAP_ADMIN_PASSWORD` | 自动生成；只用于空数据库的首次账号创建 |
| `AI_BASE_URL` / `AI_CHAT_MODEL` / `AI_API_KEY` | 对话服务初始值；界面保存的设置优先 |
| `AI_EMBEDDING_MODEL` | `bge-m3`，同一个模型用于文档和问题 |
| `EMBEDDING_DIMENSIONS` | `1024`；已有向量数据后禁止直接修改模型或维度 |
| `RETRIEVAL_MIN_SCORE` | `0.55`，应使用自己的资料校准 |
| `COOKIE_SECURE` | `false`；通过 HTTPS 访问时设置为 `true` |
| `OLLAMA_IMAGE` | `ollama/ollama:latest`，可以改成固定版本或 digest |
| `BACKEND_BUILD_CONTEXT` | 默认 `https://github.com/lxiaonan/knowledge-base.git#backend:backend` |
| `FRONTEND_BUILD_CONTEXT` | 默认 `https://github.com/lxiaonan/knowledge-base.git#frontend:frontend` |

需要多个部署实例时，在各自环境文件设置不同的 `COMPOSE_PROJECT_NAME` 和 `WEB_PORT`，并通过 `--env-file` 指向该文件。每个实例只有一个后端。

```sh
python scripts/deploy.py up --env-file .env.custom
python scripts/deploy.py status --env-file .env.custom
```

宿主机环境变量优先于环境文件。不要把 `.env.deploy`、数据库、原始文档、模型缓存或 `settings.key` 提交到仓库。

## 不使用启动脚本

手工复制 `.env.deploy.example` 为 `.env.deploy`，设置两个不同的强密码，再执行：

```sh
docker compose --env-file .env.deploy -f compose.deploy.yml up -d --build --wait --wait-timeout 1800
```

本方式只需要 Docker，不需要 Python。

## 更新与固定版本

```sh
git pull --ff-only origin main
python scripts/deploy.py up
```

每次构建会读取前后端分支当前提交。为了可重复部署，建议将配置中的分支名替换成验证过的完整提交 SHA：

```dotenv
BACKEND_BUILD_CONTEXT=https://github.com/lxiaonan/knowledge-base.git#<backend-commit-sha>:backend
FRONTEND_BUILD_CONTEXT=https://github.com/lxiaonan/knowledge-base.git#<frontend-commit-sha>:frontend
```

配置文件中使用真实 SHA 替换占位值。更换应用版本不会自动清空数据卷；涉及数据库迁移的版本升级前应先备份。数据库迁移后，直接切回旧镜像未必兼容，应连同升级前备份恢复。

## 数据持久化与备份

默认项目 `zhixu-deploy` 使用四个命名卷：

| 卷 | 内容 |
| --- | --- |
| `zhixu-deploy_postgres-data` | PostgreSQL 业务数据与向量 |
| `zhixu-deploy_files-data` | 用户上传的原始文件 |
| `zhixu-deploy_config-data` | 对话配置的 AES-256-GCM 加密密钥 |
| `zhixu-deploy_ollama-data` | BGE-M3 模型缓存，可重新下载 |

另外应妥善备份 `.env.deploy`。数据库、原始文件和加密密钥必须配套备份；丢失密钥将无法解密已保存的对话配置。

以下命令适用于 Linux / macOS / WSL。停止应用写入，再备份数据库和文件卷；数据库容器在备份期间继续运行：

```sh
mkdir -p backups
docker compose --env-file .env.deploy -f compose.deploy.yml stop frontend backend
docker compose --env-file .env.deploy -f compose.deploy.yml exec -T postgres pg_dump -U knowledge -d knowledge_base -Fc > backups/database.dump
docker run --rm -v zhixu-deploy_files-data:/source:ro -v "$(pwd)/backups:/backup" alpine:3.22 tar czf /backup/files.tar.gz -C /source .
docker run --rm -v zhixu-deploy_config-data:/source:ro -v "$(pwd)/backups:/backup" alpine:3.22 tar czf /backup/config.tar.gz -C /source .
python scripts/deploy.py up
```

如果修改了项目名，应替换卷名前缀。备份文件包含企业资料，应保存在受控位置。

恢复到新机器时，复制同版本部署文件与 `.env.deploy`，先运行一次 `up` 初始化卷，再停止前后端，向空的文件卷恢复两个压缩包，然后导入数据库：

```sh
docker compose --env-file .env.deploy -f compose.deploy.yml stop frontend backend
docker run --rm -v zhixu-deploy_files-data:/target -v "$(pwd)/backups:/backup:ro" alpine:3.22 tar xzf /backup/files.tar.gz -C /target
docker run --rm -v zhixu-deploy_config-data:/target -v "$(pwd)/backups:/backup:ro" alpine:3.22 tar xzf /backup/config.tar.gz -C /target
docker compose --env-file .env.deploy -f compose.deploy.yml exec -T postgres pg_restore -U knowledge -d knowledge_base --clean --if-exists --no-owner < backups/database.dump
python scripts/deploy.py up
```

只对新的恢复环境执行数据库替换，并检查 `pg_restore` 返回码。更换模型会触发索引配置指纹校验，需要使用新数据库或显式重建全部向量；恢复时应保持模型配置一致。

## 停止与故障排查

`python scripts/deploy.py down` 停止服务并保留全部数据卷。**不要添加 `down -v`**，它会删除数据卷。

- 镜像、依赖或模型下载失败：检查对应仓库的网络连接，查看 `logs`，恢复网络后重新执行 `up`。
- `model-init` 非零退出：查看模型下载日志；确认模型名正确、磁盘足够。成功后应为 `Exited (0)`。
- 后端无法写文件或保存对话配置：确认使用本仓库 Dockerfile；镜像已为非 root 用户创建并授权持久化目录，不要覆盖挂载成无写权限的宿主机目录。
- 端口被占用：在 `.env.deploy` 修改 `WEB_PORT`，重新执行 `up`。
- 内存不足 / 模型被终止：给 Docker Desktop 分配更多内存。CPU 推理速度取决于机器性能。
- 问答提示未配置密钥：进入设置页填写自己的对话服务信息；API 根地址不带 `/v1`。
- 502：查看后端健康状态和日志。Nginx 的流式代理已关闭缓冲；如前面还套反向代理，也需关闭 SSE 缓冲。

## 前后端开发

```sh
git clone --branch backend https://github.com/lxiaonan/knowledge-base.git knowledge-backend
git clone --branch frontend https://github.com/lxiaonan/knowledge-base.git knowledge-frontend
```

后端：JDK 21 + Maven 3.9+，在后端分支根目录先执行 `python scripts/local_setup.py`、`docker compose up -d postgres`，宿主机安装 Ollama 并执行 `ollama pull bge-m3`，最后执行 `python scripts/dev.py backend-run`。默认监听 8080。

前端：Node.js 22.12+，进入前端分支的 `frontend/`，执行 `npm ci` 与 `npm run dev`。开发代理默认指向同一机器的 `127.0.0.1:8080`，访问 `http://127.0.0.1:5173`。

若手头是同时包含 `backend/` 和 `frontend/` 的完整工作区，可执行 `python scripts/deploy.py up --local-source` 从本地源码构建。该选项仅影响构建来源，数据与容器配置仍使用部署环境。
