package com.youkeda.exercise.claw.feature.budget;

import java.math.BigDecimal;

/** 一个费用分类的金额区间。 */
public class CostBreakdown {

    private String category;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;

    public CostBreakdown() {
    }

    public CostBreakdown(String category, BigDecimal minAmount, BigDecimal maxAmount) {
        this.category = category;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
    }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal minAmount) { this.minAmount = minAmount; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(BigDecimal maxAmount) { this.maxAmount = maxAmount; }
}
