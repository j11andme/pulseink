package com.pulseink.repository.campaign;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.Instant;
import java.util.List;

@TableName(value = "campaign", autoResultMap = true)
public class CampaignEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String objective;
    private String audience;

    @TableField(value = "channels_json", typeHandler = JacksonTypeHandler.class)
    private List<String> channelsJson;

    @TableField(value = "constraints_json", typeHandler = JacksonTypeHandler.class)
    private List<String> constraintsJson;

    private String status;

    @TableField("created_by")
    private Long createdBy;

    private Long version;

    private Instant createdAt;

    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getObjective() {
        return objective;
    }

    public void setObjective(String objective) {
        this.objective = objective;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public List<String> getChannelsJson() {
        return channelsJson;
    }

    public void setChannelsJson(List<String> channelsJson) {
        this.channelsJson = channelsJson;
    }

    public List<String> getConstraintsJson() {
        return constraintsJson;
    }

    public void setConstraintsJson(List<String> constraintsJson) {
        this.constraintsJson = constraintsJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
