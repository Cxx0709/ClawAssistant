package com.youkeda.exercise.claw.feature.budget;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 一个或多个候选方案的成本核算请求。 */
public class BatchPlanCostRequest {

    private Integer headcount;
    private Integer days;
    private Integer nights;
    private String city;
    @JsonProperty("target_total_budget")
    private BigDecimal targetTotalBudget;
    @JsonProperty("target_per_person_budget")
    private BigDecimal targetPerPersonBudget;
    @JsonProperty("contingency_rate")
    private BigDecimal contingencyRate = new BigDecimal("0.10");
    private List<PlanCostOption> plans = new ArrayList<>();

    public Integer getHeadcount() { return headcount; }
    public void setHeadcount(Integer headcount) { this.headcount = headcount; }
    public Integer getDays() { return days; }
    public void setDays(Integer days) { this.days = days; }
    public Integer getNights() { return nights; }
    public void setNights(Integer nights) { this.nights = nights; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public BigDecimal getTargetTotalBudget() { return targetTotalBudget; }
    public void setTargetTotalBudget(BigDecimal targetTotalBudget) { this.targetTotalBudget = targetTotalBudget; }
    public BigDecimal getTargetPerPersonBudget() { return targetPerPersonBudget; }
    public void setTargetPerPersonBudget(BigDecimal targetPerPersonBudget) {
        this.targetPerPersonBudget = targetPerPersonBudget;
    }
    public BigDecimal getContingencyRate() { return contingencyRate; }
    public void setContingencyRate(BigDecimal contingencyRate) {
        this.contingencyRate = contingencyRate;
    }
    public List<PlanCostOption> getPlans() { return plans; }
    public void setPlans(List<PlanCostOption> plans) {
        this.plans = plans != null ? plans : new ArrayList<>();
    }
}
