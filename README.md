# AI 视频内容解析与问答平台

基于开源 DOVideo-AI 二次改造的 AI 视频内容解析平台，支持视频上传、音频提取、语音转文字、AI 智能总结、异步任务处理和对象存储。

## 项目功能

- 用户注册与登录
- 视频文件上传
- MinIO 对象存储
- FFmpeg 音频提取
- ASR 语音转文字
- AI 智能总结
- RocketMQ 异步分析任务
- Redis 缓存视频列表
- 前端侧边栏展示分析结果

## 技术栈

### 后端

- Java 21
- Spring Boot
- MyBatis-Plus
- MySQL
- Redis
- RocketMQ
- MinIO
- FFmpeg
- LangChain4j
- SiliconFlow API

### 前端

- Vue 3
- Vite
- JavaScript
- Fetch API

## 本次改造重点

本项目基于原 DOVideo-AI 项目进行二次改造，主要完成了以下工作：

1. 修复 Redis expire 导致分片上传失败的问题；
2. 接入本地 Docker 环境，包括 MySQL、Redis、MinIO、RocketMQ；
3. 配置 FFmpeg，打通音频提取和下载链路；
4. 修复 ASR API 配置问题，完成语音转文字功能；
5. 修复抽帧失败导致 AI 总结整体失败的问题，增加 ASR-only 降级策略；
6. 修复模型不可用、请求超时、LLM JSON 输出不稳定导致的 AI 分析失败；
7. 在 Agent Critic 校验失败时降级使用 Executor 结果，提升任务成功率；
8. 修复前端 AI 总结重复触发和旧状态缓存问题；
9. 将 API Key 改为环境变量读取，避免敏感信息提交到 GitHub。

## 环境变量配置

后端启动前需要配置 SiliconFlow API Key：

```powershell
[Environment]::SetEnvironmentVariable("SILICONFLOW_API_KEY", "YOUR_API_KEY", "User")