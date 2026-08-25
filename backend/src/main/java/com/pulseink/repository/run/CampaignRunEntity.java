package com.pulseink.repository.run;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@TableName(value = "campaign_run", autoResultMap = true)
public class CampaignRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("campaign_id")
    private Long campaignId;

    @TableField("requested_policy")
    private String requestedPolicy;

    @TableField("selected_mode")
    private String selectedMode;

    @TableField("selector_policy_version")
    private String selectorPolicyVersion;

    @TableField(value = "selection_reason_json", typeHandler = JacksonTypeHandler.class)
    private List<String> selectionReasonJson;

    @TableField(value = "selection_feature_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> selectionFeatureJson;

    @TableField("estimated_token_budget")
    private Long estimatedTokenBudget;

    private String state;

    @TableField("failure_reason")
    private String failureReason;

    private Long version;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("completed_at")
    private Instant completedAt;

    private Instant createdAt;

    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(Long campaignId) {
        this.campaignId = campaignId;
    }

    public String getRequestedPolicy() {
        return requestedPolicy;
    }

    public void setRequestedPolicy(String requestedPolicy) {
        this.requestedPolicy = requestedPolicy;
    }

    public String getSelectedMode() {
        return selectedMode;
    }

    public void setSelectedMode(String selectedMode) {
        this.selectedMode = selectedMode;
    }

    public String getSelectorPolicyVersion() {
        return selectorPolicyVersion;
    }

    public void setSelectorPolicyVersion(String selectorPolicyVersion) {
        this.selectorPolicyVersion = selectorPolicyVersion;
    }

    public List<String> getSelectionReasonJson() {
        return selectionReasonJson;
    }

    public void setSelectionReasonJson(List<String> selectionReasonJson) {
        this.selectionReasonJson = selectionReasonJson;
    }

    public Map<String, Object> getSelectionFeatureJson() {
        return selectionFeatureJson;
    }

    public void setSelectionFeatureJson(Map<String, Object> selectionFeatureJson) {
        this.selectionFeatureJson = selectionFeatureJson;
    }

    public Long getEstimatedTokenBudget() {
        return estimatedTokenBudget;
    }

    public void setEstimatedTokenBudget(Long estimatedTokenBudget) {
        this.estimatedTokenBudget = estimatedTokenBudget;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
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
