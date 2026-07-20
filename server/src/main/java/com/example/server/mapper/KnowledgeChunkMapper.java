package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    @Delete("""
            DELETE FROM knowledge_chunks
            WHERE document_id = #{documentId}
              AND user_id = #{userId}
            """)
    int deleteByDocumentAndUser(@Param("documentId") Long documentId,
                                @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_chunks
            WHERE document_id = #{documentId}
              AND user_id = #{userId}
            """)
    int countByDocumentAndUser(@Param("documentId") Long documentId,
                               @Param("userId") Long userId);

    @Insert({
            "<script>",
            "INSERT INTO knowledge_chunks (document_id, user_id, chunk_index, content, content_hash,",
            "char_count, embedding, embedding_model, embedding_dim) VALUES",
            "<foreach collection='chunks' item='chunk' separator=','>",
            "(#{chunk.documentId}, #{chunk.userId}, #{chunk.chunkIndex}, #{chunk.content},",
            "#{chunk.contentHash}, #{chunk.charCount}, #{chunk.embedding},",
            "#{chunk.embeddingModel}, #{chunk.embeddingDim})",
            "</foreach>",
            "</script>"
    })
    int insertBatch(@Param("chunks") List<KnowledgeChunk> chunks);

    @Select("""
            SELECT c.id, c.document_id, c.user_id, c.chunk_index, c.content,
                   c.content_hash, c.char_count, c.embedding, c.embedding_model,
                   c.embedding_dim, c.create_time
            FROM knowledge_chunks c
            INNER JOIN knowledge_documents d
                    ON c.document_id = d.id
                   AND c.user_id = d.user_id
            WHERE c.user_id = #{userId}
              AND d.user_id = #{userId}
              AND d.status = 'READY'
              AND c.embedding IS NOT NULL
              AND c.embedding <> ''
            ORDER BY c.document_id ASC, c.chunk_index ASC
            """)
    List<KnowledgeChunk> selectRetrievableByUser(@Param("userId") Long userId);
}
