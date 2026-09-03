package com.youkeda.exercise.claw.feature.transport.didi.model;

/**
 * 创建订单响应结果
 *
 * <p>封装 {@code taxi_create_order} 工具的返回数据。
 * 包含订单 ID、状态和起终点信息。
 *
 * <p>{@code orderId} 可用于后续调用 {@code taxi_query_order} 查询订单状态，
 * 以及 {@code taxi_get_driver_location} 获取司机位置。
 */
public class TaxiOrderResponse {

    /** 订单 ID */
    private String orderId;

    /** 订单初始状态 */
    private String status;

    /** 起点名称 */
    private String fromName;

    /** 终点名称 */
    private String toName;

    /** 账号手机号尾号（展示给用户确认用） */
    private String phoneNumberSuffix;

    /** 预估到达时间（预留字段，未来可能由 MCP 返回） */
    private String estimatedArrival;

    public TaxiOrderResponse() {
    }

    // ==================== Getters & Setters ====================

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getToName() {
        return toName;
    }

    public void setToName(String toName) {
        this.toName = toName;
    }

    public String getPhoneNumberSuffix() {
        return phoneNumberSuffix;
    }

    public void setPhoneNumberSuffix(String phoneNumberSuffix) {
        this.phoneNumberSuffix = phoneNumberSuffix;
    }

    public String getEstimatedArrival() {
        return estimatedArrival;
    }

    public void setEstimatedArrival(String estimatedArrival) {
        this.estimatedArrival = estimatedArrival;
    }

    @Override
    public String toString() {
        return "TaxiOrderResponse{"
                + "orderId='" + orderId + '\''
                + ", status='" + status + '\''
                + '}';
    }
}
