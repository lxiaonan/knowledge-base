# 知序 · 企业知识库

基于 **Spring Boot 3.5 / Spring AI 1.1 / Vue 3 / TypeScript** 的企业知识库。使用 **PostgreSQL 17 + pgvector** 存储与检索向量，通过 **Ollama 本地运行 BGE-M3（1024 维）**，结合大语言模型实现带来源引用的 RAG 问答。

支持 PDF、DOCX、Markdown、TXT 文档上传与异步索引、语义检索、流式问答、上下文追问、全文浏览、成员权限、个人会话和管理员数据查询。

## 分支说明

| 分支 | 内容 |
| --- | --- |
| [`main`](https://github.com/lxiaonan/knowledge-base/tree/main) | 一键部署入口、配置示例与项目文档 |
| [`frontend`](https://github.com/lxiaonan/knowledge-base/tree/frontend) | `frontend/`：Vue 3 前端、Nginx 与前端 Dockerfile |
| [`backend`](https://github.com/lxiaonan/knowledge-base/tree/backend) | `backend/`：Java 后端、数据库迁移、测试与后端 Dockerfile |

前后端分别维护。一键部署会直接从这两个分支构建镜像，无需手动合并分支。

## 快速部署

安装 **Docker Desktop（Linux 容器）或 Docker Engine + Compose v2.20+**，以及 **Python 3.9+**。建议至少 8 GB 可用内存；首次需要联网下载镜像、依赖和约 1.2 GB 的 BGE-M3 模型，预留至少 15 GB 磁盘空间。默认使用 CPU 推理，不需要显卡。

```sh
git clone --branch main https://github.com/lxiaonan/knowledge-base.git
cd knowledge-base
python scripts/deploy.py up
```

Linux / macOS 若没有 `python` 命令，请使用 `python3`。也可以下载 `main` 分支 ZIP 后解压，再执行启动命令。

启动脚本会自动生成独立的 `.env.deploy`，构建前后端镜像、初始化 PostgreSQL、下载 BGE-M3，并等待应用健康检查通过。无需在宿主机安装 Java、Node.js、数据库或 Ollama。首次部署可能需要较长时间，可在另一个终端执行 `python scripts/deploy.py logs` 查看进度。

访问 **http://127.0.0.1:8088**，账号 **`admin`**。通过下面的命令查看本机生成的初始密码：

```sh
python scripts/deploy.py password
```

登录后进入 **「设置 → 对话模型」**，填写自己的 API 地址、模型和密钥，测试并保存即可问答。支持 OpenAI 兼容的 `/v1/chat/completions` 流式接口，设置中填写不带 `/v1` 的服务根地址。预填地址与模型不包含额度或密钥。

**不配置对话密钥也能上传文档、建立本地向量索引和使用语义检索。** 文档及问题的向量化在本地 Ollama 完成；生成回答时，会把问题和召回片段发送给你配置的对话服务。

## 常用操作

```sh
python scripts/deploy.py status    # 查看服务状态
python scripts/deploy.py logs      # 最近 100 行日志
python scripts/deploy.py down      # 停止服务，保留数据卷
python scripts/deploy.py up        # 启动 / 从分支重新构建应用
```

数据库、模型、原始文件和配置加密密钥分别保存在 Docker 命名卷。部署脚本使用独立的 `.env.deploy` 与 `zhixu-deploy` 项目名，不会读取开发用 `.env` 或导入本机已有知识库数据。

默认仅发布本机 Web 端口，数据库和 Ollama 不发布到宿主机。需要局域网访问时，在 `.env.deploy` 设置 `WEB_BIND=0.0.0.0`；端口通过 `WEB_PORT` 调整。公网部署应配置 HTTPS 与访问控制，HTTPS 下设置 `COOKIE_SECURE=true`。

更新、备份恢复、固定前后端版本、故障排查与开发方式见 **[部署指南](docs/deployment.md)**。

## 架构

```text
浏览器 → Nginx / Vue 3 → Spring Boot / Spring AI
                            ├─ 文档解析、分段 → Ollama / BGE-M3 → pgvector
                            ├─ 问题向量化 → 带知识库权限的向量检索
                            └─ 问题与召回片段 → 对话 API → 流式回答与引用
```

当前采用向量余弦相似度和 HNSW 索引，尚未加入关键词混合检索、Reranker 或扫描件 OCR。PDF / DOCX 在线预览为文字内容；原始版面可下载查看。文档上限为 20 MB / 文件、200 份 / 知识库、PDF 500 页。应用当前支持单实例。

## 文档

- [完整部署与运维](docs/deployment.md)
- [开发工作区说明](docs/development.md)
- [本地真实 RAG 与旧数据迁移](docs/local-live.md)
- [功能验证记录](docs/verification.md)

第三方模型 BGE-M3 的模型信息和许可见 [BAAI/bge-m3](https://huggingface.co/BAAI/bge-m3)。
