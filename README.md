# AI 视频内容解析与知识库问答平台

这是一个基于 Spring Boot 和 Vue3 的 AI 视频内容解析与知识库问答平台。项目当前已打通视频解析链路和轻量级 RAG 知识库问答链路，支持视频内容处理、文档上传、文本切块、Embedding 入库、TopK 相似度检索和基于引用片段的问答。

当前 RAG 功能位于 `rag-version` 分支。

## 已完成功能

- 视频上传
- 音频提取
- ASR 语音转文字
- AI 智能总结
- `txt` / `md` 文档上传
- 文本切块
- Embedding 入库
- TopK 相似度检索
- RAG 问答
- sources 引用展示

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
- SiliconFlow API

### 前端

- Vue3
- Vite
- JavaScript
- Fetch API

## 启动步骤

### 1. 启动基础服务

在项目根目录执行：

```bash
docker compose up -d
```

该命令会启动 MySQL、Redis、MinIO、RocketMQ 等基础服务。

### 2. 初始化数据库

数据库初始化文件位于：

- `docs/sql/init_media.sql`
- `docs/sql/init_rag.sql`

如果使用 MySQL Workbench，可以分别打开这两个 SQL 文件，复制内容到查询窗口后执行。

如果使用 `mysql` 命令行，可以进入 MySQL 后依次执行：

```sql
source docs/sql/init_media.sql;
source docs/sql/init_rag.sql;
```

### 3. 配置环境变量

后端需要通过环境变量读取 SiliconFlow API Key。

PowerShell 示例：

```powershell
[Environment]::SetEnvironmentVariable("SILICONFLOW_API_KEY", "YOUR_API_KEY", "User")
```

不要将真实 API Key 写入代码、配置文件或 README。

### 4. 启动后端

进入后端目录：

```bash
cd server
```

在 Windows 下可以使用 Maven Wrapper 启动 Spring Boot 服务：

```bash
.\mvnw.cmd spring-boot:run
```

后端默认端口：

```text
http://localhost:9090
```

### 5. 启动前端

进入前端目录：

```bash
cd client
```

安装依赖：

```bash
npm install
```

启动开发服务：

```bash
npm run dev
```

## 主要接口

### 视频模块

- `POST /media/init-upload` 初始化视频分片上传
- `POST /media/upload-chunk` 上传视频分片
- `POST /media/complete-upload` 合并视频分片
- `POST /media/upload-url` 通过视频链接上传
- `GET /media/list` 查询视频列表

### 知识库 / RAG 模块

- `POST /knowledge/upload` 上传 txt / md 文档  
  参数：`file`、`userId`  
  返回：`documentId`、`status`、`chunkCount`

- `GET /knowledge/list?userId=1` 查询当前用户的知识库文档列表

- `POST /knowledge/search` 根据问题做 TopK chunk 相似度检索

- `POST /knowledge/ask` 基于 TopK chunk 拼接上下文并调用大模型回答  
  返回：`answer` 和 `sources`

## RAG 当前实现说明

- 第一版只支持 `txt` / `md` 文档。
- 文档原文件保存到 MinIO。
- 文档文本按固定字符长度切块后保存到 MySQL。
- chunk embedding 使用 SiliconFlow Embedding 生成，并以 JSON 字符串保存到 MySQL。
- 检索阶段在 Java 中计算余弦相似度，并返回 TopK chunk。
- 问答阶段只基于检索到的上下文生成回答，并返回 sources 引用片段。

## 注意事项

- 当前 README 只描述已经完成的功能。
- 项目不包含任何 API Key。
- 启动后端前需要确保 `SILICONFLOW_API_KEY` 已在运行环境中配置。
- RAG 第一版未引入 Elasticsearch、Milvus、Qdrant 等向量数据库。
