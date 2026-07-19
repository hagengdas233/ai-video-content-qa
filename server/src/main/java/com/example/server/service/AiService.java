package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AiAnalysisOutput;
import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AiService {

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    // 【关键】必须注入 Redis 工具！
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private VideoContextService videoContextService;

    @Autowired
    private AgentLoopService agentLoopService;

    public AiAnalysisOutput analyze(Long mediaId, String userGoal) {
        System.out.println(" [线程池] 开始处理任务，ID: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            throw new IllegalArgumentException("media not found: " + mediaId);
        }

        try {
            // ASR + 场景关键帧 OCR 按时间轴合并为统一上下文
            VideoContext videoContext = videoContextService.build(mediaFile.getFilePath(), userGoal);

            // Planner -> Executor -> Critic，最多两轮后强制结束
            AgentState agentState = agentLoopService.run(videoContext);
            if (agentState == null || agentState.result() == null) {
                throw new IllegalStateException("AI analysis returned no result");
            }
            return new AiAnalysisOutput(
                    videoContext.transcriptText(), agentState.result().toMarkdown());

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("❌ [线程池] 任务失败: " + e.getMessage());
            throw new IllegalStateException("AI analysis failed", e);
        }
    }



    //异步提取全文 (专门负责提取文字)
    @Async("aiTaskExecutor")
    public void asyncTranscribe(Long mediaId) {
        System.out.println(" [线程池] 开始全文提取任务，ID: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) return;

        try {
            //只做语音转文字
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
            mediaFile.setTranscriptText(text);

            //保存数据库
            mediaFileMapper.updateById(mediaFile);

            //强制删除 Redis 缓存
            String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
            String cacheKey = "media:list:user:" + userIdStr;
            redisTemplate.delete(cacheKey);

            System.out.println(" [线程池] 全文提取完成，缓存已清除！Key: " + cacheKey);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println(" [线程池] 提取失败: " + e.getMessage());
        }
    }
}
