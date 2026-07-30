package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AiAnalysisOutput;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.example.server.entity.AnalysisMode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private MediaFileMapper mediaFileMapper;
    @Mock
    private VideoContextService videoContextService;
    @Mock
    private AgentLoopService agentLoopService;

    private AiService service;

    @BeforeEach
    void setUp() {
        service = new AiService();
        ReflectionTestUtils.setField(service, "mediaFileMapper", mediaFileMapper);
        ReflectionTestUtils.setField(service, "videoContextService", videoContextService);
        ReflectionTestUtils.setField(service, "agentLoopService", agentLoopService);
    }

    @Test
    void noMatchStillPersistsTranscriptFromOriginalVideoContext() {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setId(1L);
        mediaFile.setFilePath("video.mp4");
        VideoContext originalContext = new VideoContext(
                "video.mp4",
                AnalysisMode.GOAL,
                "goal",
                List.of(
                        new VideoContext.VideoSegment(
                                0, 60_000, "first", List.of(), List.of()),
                        new VideoContext.VideoSegment(
                                60_000, 120_000, "second", List.of(), List.of())
                )
        );
        AnalysisResult noMatchResult = new AnalysisResult(
                "目标分析结果",
                List.of("视频内容不足以支持该分析目标"),
                List.of(),
                List.of("可调整分析目标后重新提交")
        );
        AgentState noMatchState = new AgentState(
                "goal", null, noMatchResult, null, 0);
        when(mediaFileMapper.selectById(1L)).thenReturn(mediaFile);
        when(videoContextService.build("video.mp4", AnalysisMode.GOAL, "goal"))
                .thenReturn(originalContext);
        when(agentLoopService.run(originalContext)).thenReturn(noMatchState);

        AiAnalysisOutput output = service.analyze(1L, AnalysisMode.GOAL, "goal");

        assertEquals("first\nsecond", output.transcriptText());
        assertEquals(noMatchResult.toMarkdown(), output.aiSummary());
        verify(agentLoopService).run(originalContext);
    }
}
