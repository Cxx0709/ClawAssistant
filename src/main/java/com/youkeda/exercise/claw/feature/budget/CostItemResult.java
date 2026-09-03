package com.youkeda.exercise.claw.feature.budget;

import java.math.BigDecimal;

/** 单个费用项目的确定性计算结果。 */
public class CostItemResult {

    private String category;
    private String itemName;
    private PricingMode pricingMode;
    private BigDecimal multiplier;
    private BigDecimal minUnitPrice;
    private BigDecimal maxUnitPrice;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private PriceStatus priceStatus;
    private String priceSource;
    private String notes;

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public PricingMode getPricingMode() { return pricingMode; }
    public void setPricingMode(PricingMode pricingMode) { this.pricingMode = pricingMode; }
    public BigDecimal getMultiplier() { return multiplier; }
    public void setMultiplier(BigDecimal multiplier) { this.multiplier = multiplier; }
    public BigDecimal getMinUnitPrice() { return minUnitPrice; }
    public void setMinUnitPrice(BigDecimal minUnitPrice) { this.minUnitPrice = minUnitPrice; }
    public BigDecimal getMaxUnitPrice() { return maxUnitPrice; }
    public void setMaxUnitPrice(BigDecimal maxUnitPrice) { this.maxUnitPrice = maxUnitPrice; }
    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal minAmount) { this.minAmount = minAmount; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(BigDecimal maxAmount) { this.maxAmount = maxAmount; }
    public PriceStatus getPriceStatus() { return priceStatus; }
    public void setPriceStatus(PriceStatus priceStatus) { this.priceStatus = priceStatus; }
    public String getPriceSource() { return priceSource; }
    public void setPriceSource(String priceSource) { this.priceSource = priceSource; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
