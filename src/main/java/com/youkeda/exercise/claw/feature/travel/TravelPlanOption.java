package com.youkeda.exercise.claw.feature.travel;

import com.fasterxml.jackson.databind.JsonNode;

/** 旅游/出游流程中的一个候选方案。 */
public class TravelPlanOption {

    private String optionId;
    private String displayName;
    private int version = 1;
    private String positioning;
    private String highlights;
    private String itinerarySummary;
    private PlanStatus planStatus = PlanStatus.CANDIDATE;
    private CostStatus costStatus = CostStatus.NOT_CALCULATED;
    private JsonNode costResult;

    public String getOptionId() { return optionId; }
    public void setOptionId(String optionId) { this.optionId = optionId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getPositioning() { return positioning; }
    public void setPositioning(String positioning) { this.positioning = positioning; }
    public String getHighlights() { return highlights; }
    public void setHighlights(String highlights) { this.highlights = highlights; }
    public String getItinerarySummary() { return itinerarySummary; }
    public void setItinerarySummary(String itinerarySummary) { this.itinerarySummary = itinerarySummary; }
    public PlanStatus getPlanStatus() { return planStatus; }
    public void setPlanStatus(PlanStatus planStatus) { this.planStatus = planStatus; }
    public CostStatus getCostStatus() { return costStatus; }
    public void setCostStatus(CostStatus costStatus) { this.costStatus = costStatus; }
    public JsonNode getCostResult() { return costResult; }
    public void setCostResult(JsonNode costResult) { this.costResult = costResult; }
}
