package com.pulseink.repository.publication;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PublicationMapper {

    @Insert("""
            INSERT INTO publication
                (run_id, content_version_id, approval_record_id, requested_by,
                 channel, idempotency_key, status, attempt_count, next_attempt_at, version)
            VALUES
                (#{runId}, #{contentVersionId}, #{approvalRecordId}, #{requestedBy},
                 #{channel}, #{idempotencyKey}, #{status}, #{attemptCount},
                 #{nextAttemptAt}, #{version})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PublicationEntity publication);

    @Select("SELECT * FROM publication WHERE id = #{id}")
    PublicationEntity selectById(long id);

    @Select("""
            SELECT * FROM publication
            WHERE content_version_id = #{contentVersionId} AND channel = #{channel}
            """)
    PublicationEntity selectByVersionAndChannel(@Param("contentVersionId") long contentVersionId,
                                                @Param("channel") String channel);

    @Select("SELECT * FROM publication WHERE run_id = #{runId} ORDER BY id")
    List<PublicationEntity> selectByRunId(long runId);

    @Select("""
            SELECT id FROM publication
            WHERE status IN ('PENDING', 'RETRY_WAIT', 'SENDING')
              AND (next_attempt_at IS NULL OR next_attempt_at <= #{now})
            ORDER BY id
            LIMIT #{batchSize}
            FOR UPDATE SKIP LOCKED
            """)
    List<Long> lockClaimIds(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Update("""
            <script>
            UPDATE publication
            SET status = 'SENDING',
                attempt_count = attempt_count + 1,
                next_attempt_at = #{nextAttemptAt},
                version = version + 1
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            </script>
            """)
    int claimByIds(@Param("ids") List<Long> ids, @Param("nextAttemptAt") Instant nextAttemptAt);

    @Select("""
            <script>
            SELECT * FROM publication
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY id
            </script>
            """)
    List<PublicationEntity> selectByIds(@Param("ids") List<Long> ids);

    @Update("""
            UPDATE publication
            SET status = 'SENDING',
                attempt_count = attempt_count + 1,
                next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 SECOND),
                version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
              AND status IN ('PENDING', 'RETRY_WAIT')
            """)
    int claimById(@Param("id") long id, @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE publication
            SET status = 'PUBLISHED',
                external_post_id = #{externalPostId},
                receipt_json = #{receiptJson},
                published_at = #{publishedAt},
                failure_code = NULL,
                failure_message = NULL,
                version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
              AND status = 'SENDING'
            """)
    int markPublished(@Param("id") long id, @Param("expectedVersion") long expectedVersion,
                      @Param("externalPostId") String externalPostId,
                      @Param("receiptJson") String receiptJson,
                      @Param("publishedAt") Instant publishedAt);

    @Update("""
            UPDATE publication
            SET status = 'RETRY_WAIT',
                next_attempt_at = #{nextAttemptAt},
                failure_code = #{failureCode},
                failure_message = #{failureMessage},
                version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
              AND status = 'SENDING'
            """)
    int markRetryWait(@Param("id") long id, @Param("expectedVersion") long expectedVersion,
                      @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("failureCode") String failureCode,
                      @Param("failureMessage") String failureMessage);

    @Update("""
            UPDATE publication
            SET status = 'FAILED',
                failure_code = #{failureCode},
                failure_message = #{failureMessage},
                version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
              AND status = 'SENDING'
            """)
    int markFailed(@Param("id") long id, @Param("expectedVersion") long expectedVersion,
                   @Param("failureCode") String failureCode,
                   @Param("failureMessage") String failureMessage);
}
