package com.youkeda.exercise.claw.feature.transport.didi.model;

/**
 * 创建打车订单请求参数
 *
 * <p>调用 {@code taxi_create_order} 所需参数。
 * 必须在调用 {@code taxi_estimate} 获取到 {@code traceId} 后，
 * 展示价格给用户并获得明确确认，才能创建订单。
 *
 * <p>参数来源对照：
 * <ul>
 *   <li>{@code productCategory} ← {@link TaxiEstimateResponse.EstimateItem#productCategory}</li>
 *   <li>{@code estimateTraceId} ← {@link TaxiEstimateResponse#traceId}</li>
 * </ul>
 */
public class TaxiOrderRequest {

    /** 车型标识，来自 taxi_estimate 返回的 productCategory */
    private String productCategory;

    /** 预估流程 ID，来自 taxi_estimate 返回的 traceId */
    private String estimateTraceId;

    /** 叫车人手机号（可选，不传则使用账号绑定的手机号） */
    private String callerCarPhone;

    /** 目的地地址名称（仅日志/展示用途，非 MCP 必需参数） */
    private String destName;

    public TaxiOrderRequest() {
    }

    // ==================== Getters & Setters ====================

    public String getProductCategory() {
        return productCategory;
    }

    public void setProductCategory(String productCategory) {
        this.productCategory = productCategory;
    }

    public String getEstimateTraceId() {
        return estimateTraceId;
    }

    public void setEstimateTraceId(String estimateTraceId) {
        this.estimateTraceId = estimateTraceId;
    }

    public String getCallerCarPhone() {
        return callerCarPhone;
    }

    public void setCallerCarPhone(String callerCarPhone) {
        this.callerCarPhone = callerCarPhone;
    }

    public String getDestName() {
        return destName;
    }

    public void setDestName(String destName) {
        this.destName = destName;
    }

    @Override
    public String toString() {
        return "TaxiOrderRequest{"
                + "productCategory='" + productCategory + '\''
                + ", estimateTraceId='" + estimateTraceId + '\''
                + '}';
    }
}
