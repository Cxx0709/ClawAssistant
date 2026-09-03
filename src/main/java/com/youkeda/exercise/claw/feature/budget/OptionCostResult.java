package com.youkeda.exercise.claw.feature.budget;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 单个候选方案的成本核算结果。 */
public class OptionCostResult {

    private String planId;
    private String planName;
    private int planVersion;
    private OptionCostStatus costStatus;
    private BigDecimal knownSubtotalMin;
    private BigDecimal knownSubtotalMax;
    private BigDecimal estimatedTotalMin;
    private BigDecimal estimatedTotalMax;
    private BigDecimal perPersonMin;
    private BigDecimal perPersonMax;
    private BigDecimal contingencyMin;
    private BigDecimal contingencyMax;
    private BigDecimal targetBudget;
    private BudgetStatus budgetStatus = BudgetStatus.NO_LIMIT;
    private BigDecimal overrunMin;
    private BigDecimal overrunMax;
    private BigDecimal overrunRateMax;
    private List<CostItemResult> items = new ArrayList<>();
    private List<CostBreakdown> breakdown = new ArrayList<>();
    private List<String> missingPriceItems = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }
    public int getPlanVersion() { return planVersion; }
    public void setPlanVersion(int planVersion) { this.planVersion = planVersion; }
    public OptionCostStatus getCostStatus() { return costStatus; }
    public void setCostStatus(OptionCostStatus costStatus) { this.costStatus = costStatus; }
    public BigDecimal getKnownSubtotalMin() { return knownSubtotalMin; }
    public void setKnownSubtotalMin(BigDecimal knownSubtotalMin) { this.knownSubtotalMin = knownSubtotalMin; }
    public BigDecimal getKnownSubtotalMax() { return knownSubtotalMax; }
    public void setKnownSubtotalMax(BigDecimal knownSubtotalMax) { this.knownSubtotalMax = knownSubtotalMax; }
    public BigDecimal getEstimatedTotalMin() { return estimatedTotalMin; }
    public void setEstimatedTotalMin(BigDecimal estimatedTotalMin) { this.estimatedTotalMin = estimatedTotalMin; }
    public BigDecimal getEstimatedTotalMax() { return estimatedTotalMax; }
    public void setEstimatedTotalMax(BigDecimal estimatedTotalMax) { this.estimatedTotalMax = estimatedTotalMax; }
    public BigDecimal getPerPersonMin() { return perPersonMin; }
    public void setPerPersonMin(BigDecimal perPersonMin) { this.perPersonMin = perPersonMin; }
    public BigDecimal getPerPersonMax() { return perPersonMax; }
    public void setPerPersonMax(BigDecimal perPersonMax) { this.perPersonMax = perPersonMax; }
    public BigDecimal getContingencyMin() { return contingencyMin; }
    public void setContingencyMin(BigDecimal contingencyMin) { this.contingencyMin = contingencyMin; }
    public BigDecimal getContingencyMax() { return contingencyMax; }
    public void setContingencyMax(BigDecimal contingencyMax) { this.contingencyMax = contingencyMax; }
    public BigDecimal getTargetBudget() { return targetBudget; }
    public void setTargetBudget(BigDecimal targetBudget) { this.targetBudget = targetBudget; }
    public BudgetStatus getBudgetStatus() { return budgetStatus; }
    public void setBudgetStatus(BudgetStatus budgetStatus) { this.budgetStatus = budgetStatus; }
    public BigDecimal getOverrunMin() { return overrunMin; }
    public void setOverrunMin(BigDecimal overrunMin) { this.overrunMin = overrunMin; }
    public BigDecimal getOverrunMax() { return overrunMax; }
    public void setOverrunMax(BigDecimal overrunMax) { this.overrunMax = overrunMax; }
    public BigDecimal getOverrunRateMax() { return overrunRateMax; }
    public void setOverrunRateMax(BigDecimal overrunRateMax) { this.overrunRateMax = overrunRateMax; }
    public List<CostItemResult> getItems() { return items; }
    public void setItems(List<CostItemResult> items) { this.items = items; }
    public List<CostBreakdown> getBreakdown() { return breakdown; }
    public void setBreakdown(List<CostBreakdown> breakdown) { this.breakdown = breakdown; }
    public List<String> getMissingPriceItems() { return missingPriceItems; }
    public void setMissingPriceItems(List<String> missingPriceItems) { this.missingPriceItems = missingPriceItems; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
}
