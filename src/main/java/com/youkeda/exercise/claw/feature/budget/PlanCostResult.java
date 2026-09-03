package com.youkeda.exercise.claw.feature.budget;

import java.util.ArrayList;
import java.util.List;

/** 批量方案成本核算结果。 */
public class PlanCostResult {

    private String status;
    private String city;
    private Integer headcount;
    private Integer days;
    private Integer nights;
    private List<OptionCostResult> plans = new ArrayList<>();
    private List<String> withinBudgetPlans = new ArrayList<>();
    private List<String> overBudgetPlans = new ArrayList<>();
    private String lowestCostPlan;
    private String highestCostPlan;
    private List<String> warnings = new ArrayList<>();

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public Integer getHeadcount() { return headcount; }
    public void setHeadcount(Integer headcount) { this.headcount = headcount; }
    public Integer getDays() { return days; }
    public void setDays(Integer days) { this.days = days; }
    public Integer getNights() { return nights; }
    public void setNights(Integer nights) { this.nights = nights; }
    public List<OptionCostResult> getPlans() { return plans; }
    public void setPlans(List<OptionCostResult> plans) { this.plans = plans; }
    public List<String> getWithinBudgetPlans() { return withinBudgetPlans; }
    public void setWithinBudgetPlans(List<String> withinBudgetPlans) { this.withinBudgetPlans = withinBudgetPlans; }
    public List<String> getOverBudgetPlans() { return overBudgetPlans; }
    public void setOverBudgetPlans(List<String> overBudgetPlans) { this.overBudgetPlans = overBudgetPlans; }
    public String getLowestCostPlan() { return lowestCostPlan; }
    public void setLowestCostPlan(String lowestCostPlan) { this.lowestCostPlan = lowestCostPlan; }
    public String getHighestCostPlan() { return highestCostPlan; }
    public void setHighestCostPlan(String highestCostPlan) { this.highestCostPlan = highestCostPlan; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
}
