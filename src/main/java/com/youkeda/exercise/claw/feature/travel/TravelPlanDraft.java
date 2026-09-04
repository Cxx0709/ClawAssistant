package com.youkeda.exercise.claw.feature.travel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旅游/出游方案业务数据。
 *
 * <p>仅包含结构化业务数据（出发地、人数、日期、预算、方案列表等）。
 * 不包含编排状态（stage 已移除，由 Agent Runtime 的 PlanState 管理）。
 */
public class TravelPlanDraft {

    private String departureCity;
    private Integer participantCount;
    private String travelDate;
    private String duration;
    private Integer days;
    private Integer nights;
    private Double budgetTotal;
    private Double budgetPerPerson;
    private String budgetLevel;
    private Double maxOverrunAmount;
    private Double maxOverrunRate;
    private Double acceptedOverrunAmount;
    private Double acceptedOverrunRate;
    private String destination;
    private String travelScope;
    private Map<String, String> preferences = new LinkedHashMap<>();
    private List<String> priorities = new ArrayList<>();
    private PlanMode planMode = PlanMode.BALANCED_DEFAULT;
    private int version = 1;
    private String lastFeedback;
    private List<TravelPlanOption> options = new ArrayList<>();
    private int optionCount = 3;
    private String selectedOptionId;
    private int optionSetVersion;
    private CostStatus costStatus = CostStatus.NOT_CALCULATED;

    public CostStatus getCostStatus() { return costStatus; }
    public void setCostStatus(CostStatus costStatus) { this.costStatus = costStatus; }

    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String departureCity) { this.departureCity = departureCity; }
    public Integer getParticipantCount() { return participantCount; }
    public void setParticipantCount(Integer participantCount) { this.participantCount = participantCount; }
    public String getTravelDate() { return travelDate; }
    public void setTravelDate(String travelDate) { this.travelDate = travelDate; }
    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }
    public Integer getDays() { return days; }
    public void setDays(Integer days) { this.days = days; }
    public Integer getNights() { return nights; }
    public void setNights(Integer nights) { this.nights = nights; }
    public Double getBudgetTotal() { return budgetTotal; }
    public void setBudgetTotal(Double budgetTotal) { this.budgetTotal = budgetTotal; }
    public Double getBudgetPerPerson() { return budgetPerPerson; }
    public void setBudgetPerPerson(Double budgetPerPerson) { this.budgetPerPerson = budgetPerPerson; }
    public String getBudgetLevel() { return budgetLevel; }
    public void setBudgetLevel(String budgetLevel) { this.budgetLevel = budgetLevel; }
    public Double getMaxOverrunAmount() { return maxOverrunAmount; }
    public void setMaxOverrunAmount(Double maxOverrunAmount) { this.maxOverrunAmount = maxOverrunAmount; }
    public Double getMaxOverrunRate() { return maxOverrunRate; }
    public void setMaxOverrunRate(Double maxOverrunRate) { this.maxOverrunRate = maxOverrunRate; }
    public Double getAcceptedOverrunAmount() { return acceptedOverrunAmount; }
    public void setAcceptedOverrunAmount(Double acceptedOverrunAmount) {
        this.acceptedOverrunAmount = acceptedOverrunAmount;
    }
    public Double getAcceptedOverrunRate() { return acceptedOverrunRate; }
    public void setAcceptedOverrunRate(Double acceptedOverrunRate) {
        this.acceptedOverrunRate = acceptedOverrunRate;
    }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getTravelScope() { return travelScope; }
    public void setTravelScope(String travelScope) { this.travelScope = travelScope; }
    public Map<String, String> getPreferences() { return preferences; }
    public void setPreferences(Map<String, String> preferences) {
        this.preferences = preferences != null ? preferences : new LinkedHashMap<>();
    }
    public List<String> getPriorities() { return priorities; }
    public void setPriorities(List<String> priorities) {
        this.priorities = priorities != null ? priorities : new ArrayList<>();
    }
    public PlanMode getPlanMode() { return planMode; }
    public void setPlanMode(PlanMode planMode) { this.planMode = planMode; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getLastFeedback() { return lastFeedback; }
    public void setLastFeedback(String lastFeedback) { this.lastFeedback = lastFeedback; }
    public List<TravelPlanOption> getOptions() { return options; }
    public void setOptions(List<TravelPlanOption> options) {
        this.options = options != null ? options : new ArrayList<>();
    }
    public int getOptionCount() { return optionCount; }
    public void setOptionCount(int optionCount) { this.optionCount = optionCount; }
    public String getSelectedOptionId() { return selectedOptionId; }
    public void setSelectedOptionId(String selectedOptionId) { this.selectedOptionId = selectedOptionId; }
    public int getOptionSetVersion() { return optionSetVersion; }
    public void setOptionSetVersion(int optionSetVersion) { this.optionSetVersion = optionSetVersion; }
}
