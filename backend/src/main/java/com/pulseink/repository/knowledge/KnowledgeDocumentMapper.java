package com.pulseink.repository.knowledge;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {

    @Select("""
            SELECT * FROM knowledge_document
            WHERE checksum_sha256 = #{checksum} AND knowledge_type = #{type}
            LIMIT 1
            """)
    KnowledgeDocumentEntity findByChecksumAndType(
            @Param("checksum") String checksum,
            @Param("type") String type);

    @Update("""
            UPDATE knowledge_document
            SET status = #{status}, failure_code = #{failureCode},
                detected_mime_type = #{detectedMimeType},
                embedding_profile_id = #{embeddingProfileId},
                index_name = #{indexName},
                chunk_count = #{chunkCount},
                version = version + 1,
                updated_at = updated_at
            WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updateStateCas(
            @Param("id") long id,
            @Param("status") String status,
            @Param("failureCode") String failureCode,
            @Param("detectedMimeType") String detectedMimeType,
            @Param("embeddingProfileId") String embeddingProfileId,
            @Param("indexName") String indexName,
            @Param("chunkCount") int chunkCount,
            @Param("expectedVersion") long expectedVersion);

    @Select("""
            <script>
            SELECT * FROM knowledge_document
            WHERE 1 = 1
            <if test="status != null">AND status = #{status}</if>
            <if test="type != null">AND knowledge_type = #{type}</if>
            ORDER BY created_at DESC, id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<KnowledgeDocumentEntity> findPage(
            @Param("status") String status,
            @Param("type") String type,
            @Param("offset") int offset,
            @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*) FROM knowledge_document
            WHERE 1 = 1
            <if test="status != null">AND status = #{status}</if>
            <if test="type != null">AND knowledge_type = #{type}</if>
            </script>
            """)
    long countPage(@Param("status") String status, @Param("type") String type);
}
