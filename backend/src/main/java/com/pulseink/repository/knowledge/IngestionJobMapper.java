package com.pulseink.repository.knowledge;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IngestionJobMapper extends BaseMapper<IngestionJobEntity> {

    @Update("""
            UPDATE ingestion_job
            SET status = #{status}, failure_code = #{failureCode},
                attempt = #{attempt},
                started_at = #{startedAt},
                completed_at = #{completedAt},
                version = version + 1,
                updated_at = updated_at
            WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updateStateCas(
            @Param("id") long id,
            @Param("status") String status,
            @Param("failureCode") String failureCode,
            @Param("attempt") int attempt,
            @Param("startedAt") java.time.Instant startedAt,
            @Param("completedAt") java.time.Instant completedAt,
            @Param("expectedVersion") long expectedVersion);

    @Select("""
            SELECT * FROM ingestion_job
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT #{limit}
            """)
    List<IngestionJobEntity> findPending(@Param("limit") int limit);

    @Select("""
            SELECT * FROM ingestion_job
            WHERE status = 'PENDING'
               OR (status = 'PROCESSING' AND started_at IS NOT NULL
                   AND started_at < #{staleBefore})
            ORDER BY created_at ASC
            LIMIT #{limit}
            """)
    List<IngestionJobEntity> findRecoverable(@Param("staleBefore") java.time.Instant staleBefore,
                                             @Param("limit") int limit);
}
