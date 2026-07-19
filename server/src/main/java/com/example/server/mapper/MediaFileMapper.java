package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.AnalysisStatus;
import com.example.server.entity.MediaFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {

    @Update("""
            UPDATE media_files
            SET analysis_status = 'QUEUED',
                analysis_request_id = #{analysisRequestId},
                analysis_goal = #{analysisGoal,jdbcType=VARCHAR},
                analysis_error = NULL,
                analysis_started_at = NULL,
                analysis_finished_at = NULL
            WHERE id = #{mediaId}
              AND (
                    (#{expectedAnalysisStatus,jdbcType=VARCHAR} IS NULL AND analysis_status IS NULL)
                    OR analysis_status = #{expectedAnalysisStatus,jdbcType=VARCHAR}
                  )
              AND analysis_request_id <=> #{expectedAnalysisRequestId,jdbcType=VARCHAR}
            """)
    int queueAnalysis(@Param("mediaId") Long mediaId,
                      @Param("analysisRequestId") String analysisRequestId,
                      @Param("analysisGoal") String analysisGoal,
                      @Param("expectedAnalysisStatus") AnalysisStatus expectedAnalysisStatus,
                      @Param("expectedAnalysisRequestId") String expectedAnalysisRequestId);

    @Update("""
            UPDATE media_files
            SET analysis_status = 'RUNNING',
                analysis_started_at = #{startedAt},
                analysis_error = NULL
            WHERE id = #{mediaId}
              AND analysis_request_id = #{analysisRequestId}
              AND analysis_status IN ('QUEUED', 'RUNNING')
            """)
    int markAnalysisRunning(@Param("mediaId") Long mediaId,
                            @Param("analysisRequestId") String analysisRequestId,
                            @Param("startedAt") LocalDateTime startedAt);

    @Update("""
            UPDATE media_files
            SET transcript_text = #{transcriptText,jdbcType=LONGVARCHAR},
                ai_summary = #{aiSummary,jdbcType=LONGVARCHAR},
                analysis_status = 'SUCCESS',
                analysis_error = NULL,
                analysis_finished_at = #{finishedAt}
            WHERE id = #{mediaId}
              AND analysis_request_id = #{analysisRequestId}
              AND analysis_status IN ('QUEUED', 'RUNNING')
            """)
    int markAnalysisSuccess(@Param("mediaId") Long mediaId,
                            @Param("analysisRequestId") String analysisRequestId,
                            @Param("transcriptText") String transcriptText,
                            @Param("aiSummary") String aiSummary,
                            @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            UPDATE media_files
            SET analysis_status = 'FAILED',
                analysis_error = #{analysisError,jdbcType=VARCHAR},
                analysis_finished_at = #{finishedAt}
            WHERE id = #{mediaId}
              AND analysis_request_id = #{analysisRequestId}
              AND analysis_status = 'QUEUED'
            """)
    int markSubmitFailed(@Param("mediaId") Long mediaId,
                         @Param("analysisRequestId") String analysisRequestId,
                         @Param("analysisError") String analysisError,
                         @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            UPDATE media_files
            SET analysis_status = 'FAILED',
                analysis_error = #{analysisError,jdbcType=VARCHAR},
                analysis_finished_at = #{finishedAt}
            WHERE id = #{mediaId}
              AND analysis_request_id = #{analysisRequestId}
              AND analysis_status = 'RUNNING'
            """)
    int markExecutionFailed(@Param("mediaId") Long mediaId,
                            @Param("analysisRequestId") String analysisRequestId,
                            @Param("analysisError") String analysisError,
                            @Param("finishedAt") LocalDateTime finishedAt);
}
