package com.pulseink.repository.memory;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CampaignInsightMapper {

    @Insert("""
            INSERT INTO campaign_insight
                (campaign_id, run_id, category, title, insight_text, scope_type, scope_value,
                 applicable_channels_json, evidence_refs_json, limitations_json, confidence,
                 source_snapshot_hash, prompt_version, status, index_status, index_attempts,
                 next_index_attempt_at, last_index_error, created_by, reviewed_by,
                 review_comment, version, created_at, reviewed_at, indexed_at)
            VALUES
                (#{campaignId}, #{runId}, #{category}, #{title}, #{insightText}, #{scopeType},
                 #{scopeValue}, #{applicableChannelsJson}, #{evidenceRefsJson},
                 #{limitationsJson}, #{confidence}, #{sourceSnapshotHash}, #{promptVersion},
                 #{status}, #{indexStatus}, #{indexAttempts}, #{nextIndexAttemptAt},
                 #{lastIndexError}, #{createdBy}, #{reviewedBy}, #{reviewComment}, #{version},
                 #{createdAt}, #{reviewedAt}, #{indexedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CampaignInsightEntity insight);

    @Select("SELECT * FROM campaign_insight WHERE id = #{id}")
    CampaignInsightEntity selectById(long id);

    @Select("""
            SELECT * FROM campaign_insight
            WHERE run_id = #{runId} AND source_snapshot_hash = #{snapshotHash}
              AND prompt_version = #{promptVersion}
            """)
    CampaignInsightEntity selectBySnapshot(@Param("runId") long runId,
                                           @Param("snapshotHash") String snapshotHash,
                                           @Param("promptVersion") String promptVersion);

    @Select("""
            SELECT * FROM campaign_insight
            WHERE campaign_id = #{campaignId}
            ORDER BY created_at DESC, id DESC
            """)
    List<CampaignInsightEntity> selectByCampaign(long campaignId);

    @Select("""
            SELECT id FROM campaign_insight
            WHERE status = 'APPROVED'
              AND index_status IN ('INDEX_PENDING', 'RETRY_WAIT', 'INDEXING')
              AND (next_index_attempt_at IS NULL OR next_index_attempt_at <= #{now})
            ORDER BY id
            LIMIT #{batchSize}
            FOR UPDATE SKIP LOCKED
            """)
    List<Long> lockIndexClaimIds(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Update("""
            <script>
            UPDATE campaign_insight
            SET index_status = 'INDEXING',
                index_attempts = index_attempts + 1,
                next_index_attempt_at = #{nextAttemptAt},
                version = version + 1
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            </script>
            """)
    int claimIndexByIds(@Param("ids") List<Long> ids, @Param("nextAttemptAt") Instant nextAttemptAt);

    @Select("""
            <script>
            SELECT * FROM campaign_insight
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY id
            </script>
            """)
    List<CampaignInsightEntity> selectByIds(@Param("ids") List<Long> ids);

    @Update("""
            UPDATE campaign_insight
            SET status = #{status},
                index_status = #{indexStatus},
                reviewed_by = #{reviewedBy},
                review_comment = #{comment},
                reviewed_at = #{reviewedAt},
                next_index_attempt_at = #{nextIndexAttemptAt},
                version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
              AND status = 'PENDING'
            """)
    int decideCas(@Param("id") long id,
                  @Param("expectedVersion") long expectedVersion,
                  @Param("status") String status,
                  @Param("indexStatus") String indexStatus,
                  @Param("reviewedBy") long reviewedBy,
                  @Param("comment") String comment,
                  @Param("reviewedAt") Instant reviewedAt,
                  @Param("nextIndexAttemptAt") Instant nextIndexAttemptAt);

    @Update("""
            UPDATE campaign_insight
            SET index_status = 'INDEXED',
                indexed_at = #{indexedAt},
                last_index_error = NULL,
                version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
              AND index_status = 'INDEXING'
            """)
    int markIndexedCas(@Param("id") long id,
                       @Param("expectedVersion") long expectedVersion,
                       @Param("indexedAt") Instant indexedAt);

    @Update("""
            UPDATE campaign_insight
            SET index_status = 'RETRY_WAIT',
                next_index_attempt_at = #{nextAttemptAt},
                last_index_error = #{error},
                version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
              AND index_status = 'INDEXING'
            """)
    int markIndexRetryCas(@Param("id") long id,
                          @Param("expectedVersion") long expectedVersion,
                          @Param("nextAttemptAt") Instant nextAttemptAt,
                          @Param("error") String error);

    @Update("""
            UPDATE campaign_insight
            SET index_status = 'FAILED',
                last_index_error = #{error},
                version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
              AND index_status = 'INDEXING'
            """)
    int markIndexFailedCas(@Param("id") long id,
                           @Param("expectedVersion") long expectedVersion,
                           @Param("error") String error);
}
