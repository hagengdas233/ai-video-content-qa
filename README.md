# AI 视频解析与知识库问答平台

## 项目简介

AI 视频解析与知识库问答平台是一个基于 Spring Boot + Vue3 的全栈项目，围绕“视频内容理解”和“轻量级知识库问答”两条链路展开。

当前 `rag-version` 分支已实现视频上传、基于 yt-dlp 的 URL 视频下载能力、ASR 转写、AI 总结、文档上传、文本切块、Embedding 入库、TopK 检索、RAG 问答和 sources 引用展示。项目使用 MySQL 保存业务数据与 embedding JSON，通过 MinIO 存储上传文件，并使用 Redis / RocketMQ 支撑上传状态、任务投递和异步分析流程。

## 项目预览

### 工作台首页

![工作台首页](docs/images/workbench.png)

### 视频全文转写

![视频全文转写](docs/images/video-transcript.png)

### 视频 AI 总结

![视频 AI 总结](docs/images/video-summary.png)

### RAG 问答与 sources

![RAG 问答与 sources](docs/images/rag-sources.png)

### 无关问题过滤

![无关问题过滤](docs/images/rag-filter.png)

## 核心功能

### 视频内容解析

- 支持本地视频上传，包含分片上传、上传状态查询和合并上传。
- 支持基于 yt-dlp 的 URL 视频下载能力，实际可用性受目标平台规则、网络环境和视频权限限制。
- 视频文件上传至 MinIO，并在 MySQL 中记录文件信息。
- 支持通过 FFmpeg 提取音频，并调用 ASR 能力生成文本转写。
- 支持将转写文本交给大模型生成 Markdown 格式的视频内容总结。
- 使用 RocketMQ 投递视频分析任务，后端消费者异步执行 AI 分析流程。

### 知识库 / RAG 问答

- 支持上传 `txt` / `md` 文档，原文件保存至 MinIO。
- 文档内容按固定长度切块，当前 chunk 大小为 1000 字符，重叠长度为 150 字符。
- 使用 SiliconFlow Embedding 接口生成向量，并以 JSON 字符串形式保存到 MySQL。
- 查询时对用户问题生成 embedding，在 Java 服务内计算余弦相似度并返回 TopK 片段。
- 问答时基于检索到的上下文调用大模型生成回答，并返回 `sources` 引用片段。
- 对相似度低于阈值的结果进行过滤；无相关片段时返回“知识库中没有找到相关信息”。

### 工程化配置

- 后端基于 Spring Boot，使用 MyBatis-Plus 访问 MySQL。
- 使用 Redis 记录分片上传状态和缓存信息。
- 使用 MinIO 管理视频文件与知识库文档对象存储。
- 使用 RocketMQ 进行视频分析任务投递和异步消费。
- 通过环境变量配置 SiliconFlow API Key，避免将敏感信息写入代码。
- yt-dlp 路径支持通过 `YTDLP_PATH` 或 `ytdlp.path` 配置。

## 技术栈

### 后端

- Spring Boot
- MyBatis-Plus
- MySQL
- Redis
- RocketMQ
- MinIO
- LangChain4j
- FFmpeg
- yt-dlp
- SiliconFlow API

### 前端

- Vue3
- Vite
- JavaScript
- Fetch API

### 部署

- Docker Compose

## 系统流程

### 视频链路

视频上传 -> MinIO 存储 -> FFmpeg 音频提取 -> ASR 转写 -> AI 总结

### RAG 链路

文档上传 -> 文本切块 -> Embedding -> MySQL 存储 -> TopK 检索 -> RAG 问答 -> sources 展示

## 本地部署

### 1. 启动基础服务

在项目根目录执行：

```bash
docker compose up -d
```

该命令会启动 MySQL、Redis、MinIO、RocketMQ 和 RocketMQ Dashboard。

### 2. 初始化数据库

进入 MySQL 后依次执行：

```sql
source docs/sql/init_media.sql;
source docs/sql/init_rag.sql;
```

也可以使用 MySQL Workbench 等客户端打开并执行以下文件：

- `docs/sql/init_media.sql`
- `docs/sql/init_rag.sql`

### 3. 配置环境变量

后端需要读取 SiliconFlow API Key。PowerShell 示例：

```powershell
[Environment]::SetEnvironmentVariable("SILICONFLOW_API_KEY", "YOUR_SILICONFLOW_API_KEY", "User")
```

支持基于 yt-dlp 的 URL 视频下载能力，实际可用性受目标平台规则、网络环境和视频权限限制。可选配置 `YTDLP_PATH`：

```powershell
[Environment]::SetEnvironmentVariable("YTDLP_PATH", "D:/yt-dlp/yt-dlp.exe", "User")
```

也可以在 `server/src/main/resources/application.properties` 中调整：

```properties
ytdlp.path=${YTDLP_PATH:D:/yt-dlp/yt-dlp.exe}
```

### 4. 启动后端

```bash
cd server
.\mvnw.cmd spring-boot:run
```

默认后端地址：

```text
http://localhost:9090
```

### 5. 启动前端

```bash
cd client
npm install
npm run dev
```

前端开发服务启动后，按终端输出的本地地址访问页面。

## 注意事项

- 本项目不包含任何真实 API Key，运行前需要自行配置 `SILICONFLOW_API_KEY`。
- 支持基于 yt-dlp 的 URL 视频下载能力，实际可用性受目标平台规则、网络环境和视频权限限制。
- 当前 RAG 第一版使用 MySQL 保存 embedding JSON，没有引入 Milvus、Qdrant、Elasticsearch。
- 当前知识库文档上传支持 `txt` / `md` 文档。
