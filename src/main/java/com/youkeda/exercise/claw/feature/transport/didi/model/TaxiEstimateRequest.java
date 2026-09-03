package com.youkeda.exercise.claw.feature.transport.didi.model;

/**
 * 打车估价请求参数
 *
 * <p>封装用户发起打车估价所需的起点和终点信息。
 * 地址和坐标可以只填其一，{@code DidiRideService} 在必要时
 * 内部调用 {@code maps_textsearch} 补全坐标。
 *
 * <p>MCP 工具 {@code taxi_estimate} 要求坐标必须来自
 * {@code maps_textsearch} 的返回值，不支持外部坐标输入。
 */
public class TaxiEstimateRequest {

    /** 起点地址名称 */
    private String originName;

    /** 起点经度（可选，填了则必须来自 maps_textsearch） */
    private String originLng;

    /** 起点纬度（可选，填了则必须来自 maps_textsearch） */
    private String originLat;

    /** 终点地址名称 */
    private String destName;

    /** 终点经度（可选，填了则必须来自 maps_textsearch） */
    private String destLng;

    /** 终点纬度（可选，填了则必须来自 maps_textsearch） */
    private String destLat;

    public TaxiEstimateRequest() {
    }

    // ==================== Getters & Setters ====================

    public String getOriginName() {
        return originName;
    }

    public void setOriginName(String originName) {
        this.originName = originName;
    }

    public String getOriginLng() {
        return originLng;
    }

    public void setOriginLng(String originLng) {
        this.originLng = originLng;
    }

    public String getOriginLat() {
        return originLat;
    }

    public void setOriginLat(String originLat) {
        this.originLat = originLat;
    }

    public String getDestName() {
        return destName;
    }

    public void setDestName(String destName) {
        this.destName = destName;
    }

    public String getDestLng() {
        return destLng;
    }

    public void setDestLng(String destLng) {
        this.destLng = destLng;
    }

    public String getDestLat() {
        return destLat;
    }

    public void setDestLat(String destLat) {
        this.destLat = destLat;
    }

    /** 判断是否已包含完整坐标信息 */
    public boolean hasFullCoordinates() {
        return originLng != null && !originLng.isBlank()
                && originLat != null && !originLat.isBlank()
                && destLng != null && !destLng.isBlank()
                && destLat != null && !destLat.isBlank();
    }

    /** 判断是否缺少坐标 */
    public boolean needsGeocoding() {
        return originName != null && !originName.isBlank()
                && destName != null && !destName.isBlank()
                && !hasFullCoordinates();
    }

    @Override
    public String toString() {
        return "TaxiEstimateRequest{"
                + "originName='" + originName + '\''
                + ", destName='" + destName + '\''
                + '}';
    }
}
