package com.youkeda.exercise.claw.feature.budget;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** 一次批量成本核算中的候选方案。 */
public class PlanCostOption {

    @JsonProperty("plan_id")
    private String planId;
    @JsonProperty("plan_name")
    private String planName;
    @JsonProperty("plan_version")
    private int planVersion = 1;
    private List<PlanCostItem> items = new ArrayList<>();

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }
    public int getPlanVersion() { return planVersion; }
    public void setPlanVersion(int planVersion) { this.planVersion = planVersion; }
    public List<PlanCostItem> getItems() { return items; }
    public void setItems(List<PlanCostItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }
}
