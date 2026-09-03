package com.youkeda.exercise.claw.feature.transport.didi.model;

import java.util.Collections;
import java.util.List;

/**
 * 打车估价响应结果
 *
 * <p>封装 {@code taxi_estimate} 工具的返回数据。
 * 包含预估流程 ID（{@code traceId}）和所有可用车型的价格信息。
 *
 * <p>{@code traceId} 是后续创建订单的必需参数，
 * 由 {@code DidiRideStateStore} 暂存供用户确认后使用。
 */
public class TaxiEstimateResponse {

    /** 预估流程 ID，创建订单时必须传入作为 estimate_trace_id */
    private String traceId;

    /** 各车型估价列表 */
    private List<EstimateItem> items = List.of();

    public TaxiEstimateResponse() {
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public List<EstimateItem> getItems() {
        return items;
    }

    public void setItems(List<EstimateItem> items) {
        this.items = items != null ? items : List.of();
    }

    /** 获取第一个车型的 productCategory（快捷取法） */
    public String getFirstProductCategory() {
        if (items.isEmpty()) return "";
        return items.get(0).getProductCategory();
    }

    /**
     * 单品估价信息
     */
    public static class EstimateItem {

        /** 车型名称，如"快车"、"优享" */
        private String productName;

        /** 车型标识，创建订单时作为 product_category 传入 */
        private String productCategory;

        /** 预估价格（元），如"45.0" */
        private String priceText;

        /** 预计到达时间描述，如"25分钟" */
        private String destTimeText;

        public EstimateItem() {
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public String getProductCategory() {
            return productCategory;
        }

        public void setProductCategory(String productCategory) {
            this.productCategory = productCategory;
        }

        public String getPriceText() {
            return priceText;
        }

        public void setPriceText(String priceText) {
            this.priceText = priceText;
        }

        public String getDestTimeText() {
            return destTimeText;
        }

        public void setDestTimeText(String destTimeText) {
            this.destTimeText = destTimeText;
        }
    }

    @Override
    public String toString() {
        return "TaxiEstimateResponse{"
                + "traceId='" + traceId + '\''
                + ", items=" + (items != null ? items.size() : 0)
                + '}';
    }
}
