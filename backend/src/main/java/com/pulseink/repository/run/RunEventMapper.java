package com.pulseink.repository.run;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RunEventMapper extends BaseMapper<RunEventEntity> {

    @Select("""
            SELECT MAX(sequence_no) FROM run_event WHERE run_id = #{runId}
            """)
    Long maxSequence(@Param("runId") long runId);

    @Select("""
            SELECT id, run_id, sequence_no, event_type, payload_json, created_at
            FROM run_event
            WHERE run_id = #{runId} AND sequence_no > #{lastSequence}
            ORDER BY sequence_no ASC
            """)
    @Results(id = "runEventResult", value = {
            @Result(column = "run_id", property = "runId"),
            @Result(column = "sequence_no", property = "sequenceNo"),
            @Result(column = "event_type", property = "eventType"),
            @Result(column = "payload_json", property = "payloadJson",
                    typeHandler = JacksonTypeHandler.class),
            @Result(column = "created_at", property = "createdAt")
    })
    List<RunEventEntity> findAfter(@Param("runId") long runId,
                                   @Param("lastSequence") long lastSequence);
}
