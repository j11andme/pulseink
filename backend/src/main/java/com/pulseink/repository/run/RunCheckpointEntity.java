package com.pulseink.repository.run;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.Instant;
import java.util.Map;

@TableName(value = "run_checkpoint", autoResultMap = true)
public class RunCheckpointEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("run_id")
    private Long runId;

    @TableField("checkpoint_type")
    private String checkpointType;

    @TableField(value = "checkpoint_data_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> checkpointDataJson;

    @TableField("schema_version")
    private Integer schemaVersion;

    private Long version;

    @TableField("created_at")
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public String getCheckpointType() {
        return checkpointType;
    }

    public void setCheckpointType(String checkpointType) {
        this.checkpointType = checkpointType;
    }

    public Map<String, Object> getCheckpointDataJson() {
        return checkpointDataJson;
    }

    public void setCheckpointDataJson(Map<String, Object> checkpointDataJson) {
        this.checkpointDataJson = checkpointDataJson;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
