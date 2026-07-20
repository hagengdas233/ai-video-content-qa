package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    @Select("""
            SELECT *
            FROM knowledge_documents
            WHERE id = #{documentId}
              AND user_id = #{userId}
            """)
    KnowledgeDocument selectOwnedById(@Param("documentId") Long documentId,
                                      @Param("userId") Long userId);

    @Update("""
            UPDATE knowledge_documents
            SET status = 'READY',
                chunk_count = #{chunkCount},
                error_message = NULL,
                update_time = #{updateTime}
            WHERE id = #{documentId}
              AND user_id = #{userId}
              AND status = 'PROCESSING'
            """)
    int markReadyIfProcessing(@Param("documentId") Long documentId,
                              @Param("userId") Long userId,
                              @Param("chunkCount") int chunkCount,
                              @Param("updateTime") LocalDateTime updateTime);

    @Update("""
            UPDATE knowledge_documents
            SET status = 'FAILED',
                chunk_count = 0,
                error_message = #{errorMessage},
                update_time = #{updateTime}
            WHERE id = #{documentId}
              AND user_id = #{userId}
              AND status = 'PROCESSING'
            """)
    int markFailedIfProcessing(@Param("documentId") Long documentId,
                               @Param("userId") Long userId,
                               @Param("errorMessage") String errorMessage,
                               @Param("updateTime") LocalDateTime updateTime);

    @Delete("""
            DELETE FROM knowledge_documents
            WHERE id = #{documentId}
              AND user_id = #{userId}
              AND status IN ('READY', 'FAILED')
            """)
    int deleteOwnedReadyOrFailed(@Param("documentId") Long documentId,
                                 @Param("userId") Long userId);
}
