package com.pulseink.repository.run;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CampaignRunMapper extends BaseMapper<CampaignRunEntity> {

    @Update("""
            UPDATE campaign_run
            SET state = #{state},
                failure_reason = #{failureReason},
                started_at = #{startedAt},
                completed_at = #{completedAt},
                version = version + 1,
                updated_at = updated_at
            WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updateStateCas(
            @Param("id") long id,
            @Param("state") String state,
            @Param("failureReason") String failureReason,
            @Param("startedAt") Instant startedAt,
            @Param("completedAt") Instant completedAt,
            @Param("expectedVersion") long expectedVersion);
}
