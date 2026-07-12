# AI 视频解析与知识库问答平台

一个基于 Spring Boot + Vue 3 的全栈 AI 应用项目，围绕“视频内容理解”和“轻量级知识库问答”两条链路展开。项目支持本地视频上传、URL 视频拉取、分片上传、MinIO 对象存储、RocketMQ 异步视频分析、FFmpeg 音频提取、ASR 转写、AI 总结，以及文档切片、Embedding 检索、RAG 问答和 sources 引用展示。

本项目适合作为 Java 后端实习简历项目展示，重点体现大文件上传、消息队列异步解耦、Redis/Redisson 控制重复提交和限流、AI 服务接入、RAG 检索问答等后端工程能力。

## 项目预览

### 工作台首页

![工作台首页](docs/images/workbench.png)

### 视频全文转写

![视频全文转写](docs/images/video-transcript.png)

### 视频 AI 总结

![视频 AI 总结](docs/images/video-summary.png)

### RAG 问答 sources

![RAG 问答 sources](docs/images/rag-sources.png)

### 无关问题过滤

![无关问题过滤](docs/images/rag-filter.png)

## 技术栈

### 后端

- Java 21
- Spring Boot
- MyBatis-Plus
- MySQL
- Redis
- Redisson
- RocketMQ
- MinIO
- LangChain4j
- FFmpeg
- yt-dlp
- SiliconFlow API

### 前端

- Vue 3
- Vite
- JavaScript
- Fetch API / Axios
- marked

### 部署与中间件

- Docker Compose
- MySQL 8.0
- Redis
- MinIO
- RocketMQ NameServer / Broker / Dashboard

## 核心功能

### 视频内容解析

- 支持本地视频上传，并将视频文件保存到 MinIO。
- 支持基于 yt-dlp 的 URL 视频拉取，实际可用性受目标平台规则、网络环境和视频权限限制。
- 支持分片上传、已上传分片查询和合并上传，Redis 记录上传会话和已上传分片下标。
- 支持通过正式接口提交视频分析任务，并将任务投递到 RocketMQ。
- RocketMQ 消费者收到任务后，将耗时 AI 分析逻辑派发到业务线程池异步执行。
- 使用 FFmpeg 提取音频并按片段处理，调用 ASR 服务生成转写文本。
- 支持关键帧抽取和 OCR 识别；关键帧抽取失败时降级为 ASR-only 分析。
- 基于大模型生成结构化视频分析结果。

### 知识库 / RAG 问答

- 支持上传 `txt` / `md` 文档，原文件保存到 MinIO。
- 文档内容按固定长度切片，当前 chunk 大小为 1000 字符，overlap 为 150 字符。
- 使用 SiliconFlow Embedding 接口生成向量。
- Embedding 以 JSON 字符串形式保存到 MySQL，没有引入 Milvus、Qdrant、Elasticsearch 等向量数据库。
- 查询时对用户问题生成 Embedding，在 Java 服务内计算余弦相似度并返回 TopK 片段。
- 问答时将检索到的上下文交给大模型生成答案，并返回 `sources` 引用片段。
- 对低于相似度阈值的结果进行过滤，无相关片段时返回知识库未找到相关信息。
- 支持删除知识库文档及其对应 chunk。

## 核心业务流程

### 视频上传与存储

1. 前端发起本地视频上传或 URL 视频拉取。
2. 普通上传会计算文件 MD5，并将视频上传到 MinIO。
3. 分片上传流程中，后端创建 `uploadId`，Redis 记录文件名、分片总数和用户信息。
4. 每个分片上传成功后，Redis Set 记录已上传分片下标。
5. 合并上传时，后端校验分片数量，本地合并完整文件后上传到 MinIO。
6. MySQL 保存媒体文件记录，Redis 保存 `media:md5:{mediaId}` 内容指纹。

### 异步视频分析

1. 客户端调用正式分析接口 `POST /media/analyze/{mediaId}`。
2. 后端从 `Authorization` 解析当前用户，并校验 `media_files.user_id`。
3. 读取或计算视频内容指纹 `contentHash`。
4. 使用 Redis `analysis:active:{contentHash}` 防止重复提交。
5. 使用 Redisson RateLimiter 对 AI 分析入口做全局限流。
6. 将 `AnalysisTaskMsg` 投递到 RocketMQ 的 `video-analysis-topic`。
7. RocketMQ 消费者收到任务后，使用 Redisson 分布式锁兜底防重复消费。
8. 消费者将实际 AI 分析派发到线程池执行。
9. 分析完成后写回 MySQL，并释放 Redis activeKey。

### AI 分析链路

视频文件 -> FFmpeg 音频提取 -> ASR 转写 -> 关键帧抽取/OCR -> 构建 VideoContext -> 长视频相关片段召回 -> Planner / Executor / Critic 分析 -> 结构化总结写回 MySQL

### RAG 问答链路

文档上传 -> MinIO 存储 -> 文本切片 -> Embedding 生成 -> MySQL 存储 -> 问题 Embedding -> 余弦相似度 TopK 检索 -> 阈值过滤 -> 大模型生成答案 -> 返回 sources

## 正式视频分析接口

### 提交分析任务

```http
POST /media/analyze/{mediaId}
Authorization: Bearer {{token}}
Content-Type: application/json

{
  "goal": "Summarize the core ideas of this video and return structured conclusions with evidence."
}
```

### 返回状态

- 首次提交成功：返回 `SUBMITTED`。
- 相同视频正在分析：返回 `RUNNING`。
- 未登录或 token 无效：返回 `401`。
- 当前用户不是视频所有者：返回 `403`。
- 超过分析入口限流：返回 `429`。

### 身份说明

正式接口只从 `Authorization: Bearer {{token}}` 中解析当前用户身份，并通过服务端查询确认用户存在。query 参数或请求体中的 `userId` 不作为当前用户身份来源，即使传入也会被忽略。

当前项目使用轻量级 Token 认证，`/user/login` 返回形如 `user_{id}` 的 token，用于本项目本地演示和接口权限校验。生产环境建议替换为 JWT、OAuth 2.0 等标准方案。

## 配置说明

运行前需要根据本地环境配置以下变量或配置项。请不要将真实密钥、token、数据库密码提交到仓库。

### 环境变量

- `SILICONFLOW_API_KEY`
- `ALIYUN_API_KEY`
- `YTDLP_PATH`

### 关键配置项

配置文件位于 `server/src/main/resources/application.properties`，当前包含：

- `server.port`
- `spring.datasource.url`
- `spring.datasource.username`
- `spring.datasource.password`
- `spring.data.redis.host`
- `spring.data.redis.port`
- `minio.endpoint`
- `minio.accessKey`
- `minio.secretKey`
- `minio.bucketName`
- `ai.deepseek.api-key`
- `ai.deepseek.base-url`
- `ai.deepseek.model`
- `ai.embedding.model`
- `ai.aliyun.api-key`
- `ytdlp.path`
- `tool.ffmpeg.dir`
- `tool.ocr.command`
- `rocketmq.name-server`
- `rocketmq.producer.group`

README 只列出配置名称，不包含真实 API Key、token 或个人本地路径。

当前后端默认端口为 `9090`。Docker Compose 中 MySQL 映射到宿主机 `3307` 端口，应用默认连接的数据库名为 `media_db`。

## 本地启动

### 1. 启动中间件

在项目根目录执行：

```bash
docker compose up -d
```

该命令会启动 MySQL、Redis、MinIO、RocketMQ NameServer、RocketMQ Broker 和 RocketMQ Dashboard。

### 2. 初始化数据库

进入 MySQL 后执行项目提供的 SQL 文件：

```sql
source docs/sql/init_media.sql;
source docs/sql/init_rag.sql;
```

Windows 用户推荐使用 MySQL Workbench、DataGrip 等客户端直接打开并执行以下 SQL 文件；如果使用 MySQL 命令行，`source` 后的路径请按实际当前目录调整。

- `docs/sql/init_media.sql`
- `docs/sql/init_rag.sql`

### 3. 配置环境变量

至少需要配置 AI 服务密钥：

```powershell
[Environment]::SetEnvironmentVariable("SILICONFLOW_API_KEY", "YOUR_KEY", "User")
```

如果使用 URL 视频拉取能力，需要安装 yt-dlp，并配置：

```powershell
[Environment]::SetEnvironmentVariable("YTDLP_PATH", "YOUR_YTDLP_EXECUTABLE", "User")
```

设置用户级环境变量后，需要重新打开终端或重启 IDEA，后端进程才能读取到新值。

如需使用特定 FFmpeg 安装目录，可调整 `tool.ffmpeg.dir`；同时确保 `ffmpeg` 命令可被后端进程调用。

OCR 默认命令为 `tesseract`。如果单帧 OCR 识别失败，该帧 OCR 文本会置空，分析仍可基于 ASR 继续；如果关键帧抽取流程整体失败，则降级为 ASR-only。

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

## 测试说明

仓库根目录提供了 `test.http`，可用于测试正式视频分析接口。

测试文件中的 token 均为占位符：

```http
@token = <token-from-login>
@otherToken = <other-user-token>
```

请先通过 `/user/login` 获取本地演示 token，再替换占位符。不要将真实 token 写入仓库。

已人工验证的正式分析接口场景包括：

- 不带 `Authorization` 返回 `401 login required`。
- 使用合法 token 首次提交指定视频返回 `SUBMITTED`。
- 相同视频重复提交返回 `RUNNING`。
- query/body 中伪造 `userId` 会被忽略。
- 非视频所有者访问视频返回 `403`。
- RocketMQ 消费者可以收到任务并完成异步分析。
- 分析完成后 Redis activeKey 正常释放。

## 项目亮点

- 大文件分片上传：支持上传初始化、分片上传、已上传分片查询和合并上传。
- MinIO 对象存储：统一保存视频文件和知识库文档。
- RocketMQ 异步解耦：将长耗时视频分析从 HTTP 请求链路中拆出。
- Redis 防重复提交：使用内容指纹和 activeKey 避免重复分析任务。
- Redisson 限流与分布式锁：限制 AI 分析入口调用频率，并在消费侧兜底防重复处理。
- 用户与资源权限校验：正式分析接口校验当前用户是否拥有目标视频。
- AI 总结：结合 ASR、OCR 和大模型生成结构化视频分析结果。
- RAG 问答：支持文档切片、Embedding 检索、阈值过滤和 sources 引用展示。

## 注意事项

- 本项目不包含任何真实 API Key，运行前需要自行配置环境变量。
- URL 视频拉取能力受目标平台规则、网络环境和视频权限限制。
- 当前 RAG 实现为轻量版本，Embedding 存储在 MySQL 中，适合学习和项目展示。
- 当前登录 token 为本地演示用途，生产环境建议替换为 JWT、OAuth 2.0 等标准认证方案。
