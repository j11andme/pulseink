package com.pulseink.repository.run;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RunCheckpointMapper extends BaseMapper<RunCheckpointEntity> {

    @Select("""
            SELECT id, run_id, checkpoint_type, checkpoint_data_json, schema_version, version, created_at
            FROM run_checkpoint
            WHERE run_id = #{runId}
            ORDER BY id DESC
            LIMIT 1
            """)
    @Results(id = "runCheckpointResult", value = {
            @Result(column = "run_id", property = "runId"),
            @Result(column = "checkpoint_type", property = "checkpointType"),
            @Result(column = "checkpoint_data_json", property = "checkpointDataJson",
                    typeHandler = JacksonTypeHandler.class),
            @Result(column = "schema_version", property = "schemaVersion"),
            @Result(column = "created_at", property = "createdAt")
    })
    RunCheckpointEntity latest(@Param("runId") long runId);
}
