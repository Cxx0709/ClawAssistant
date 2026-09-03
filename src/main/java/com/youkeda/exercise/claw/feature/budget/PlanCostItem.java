package com.youkeda.exercise.claw.feature.budget;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** 一个可独立核算的方案费用项目。 */
public class PlanCostItem {

    private String category;
    @JsonProperty("item_name")
    private String itemName;
    @JsonProperty("pricing_mode")
    private PricingMode pricingMode;
    @JsonProperty("unit_price")
    private BigDecimal unitPrice;
    @JsonProperty("min_unit_price")
    private BigDecimal minUnitPrice;
    @JsonProperty("max_unit_price")
    private BigDecimal maxUnitPrice;
    private BigDecimal quantity;
    private Integer occurrences;
    private Integer capacity;
    @JsonProperty("applicable_headcount")
    private Integer applicableHeadcount;
    @JsonProperty("price_source")
    private String priceSource;
    @JsonProperty("price_status")
    private PriceStatus priceStatus = PriceStatus.ESTIMATED;
    private String notes;

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public PricingMode getPricingMode() { return pricingMode; }
    public void setPricingMode(PricingMode pricingMode) { this.pricingMode = pricingMode; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getMinUnitPrice() { return minUnitPrice; }
    public void setMinUnitPrice(BigDecimal minUnitPrice) { this.minUnitPrice = minUnitPrice; }
    public BigDecimal getMaxUnitPrice() { return maxUnitPrice; }
    public void setMaxUnitPrice(BigDecimal maxUnitPrice) { this.maxUnitPrice = maxUnitPrice; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public Integer getOccurrences() { return occurrences; }
    public void setOccurrences(Integer occurrences) { this.occurrences = occurrences; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public Integer getApplicableHeadcount() { return applicableHeadcount; }
    public void setApplicableHeadcount(Integer applicableHeadcount) { this.applicableHeadcount = applicableHeadcount; }
    public String getPriceSource() { return priceSource; }
    public void setPriceSource(String priceSource) { this.priceSource = priceSource; }
    public PriceStatus getPriceStatus() { return priceStatus; }
    public void setPriceStatus(PriceStatus priceStatus) {
        this.priceStatus = priceStatus != null ? priceStatus : PriceStatus.ESTIMATED;
    }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
