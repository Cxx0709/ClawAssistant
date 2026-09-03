package com.youkeda.exercise.claw.domain.map;

/**
 * 地点搜索结果
 *
 * <p>封装单个 POI 地点的信息，用于格式化为自然语言文本返回给 LLM。
 */
public class PlaceResponse {

    private String title;
    private String address;
    private double latitude;
    private double longitude;
    private double distance;    // 与搜索中心点的距离，单位：米
    private String category;

    public PlaceResponse() {
    }

    public PlaceResponse(String title, String address, double latitude, double longitude,
                         double distance, String category) {
        this.title = title;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.distance = distance;
        this.category = category;
    }

    /**
     * 格式化为自然语言文本行
     */
    public String toTextLine(int index) {
        StringBuilder sb = new StringBuilder();
        sb.append(index).append(". ").append(title);
        if (category != null && !category.isBlank()) {
            sb.append("（").append(category).append("）");
        }
        sb.append("\n");
        sb.append("   地址：").append(address != null ? address : "暂无").append("\n");
        if (distance > 0) {
            sb.append("   距离：").append(formatDistance(distance));
        }
        return sb.toString();
    }

    private static String formatDistance(double meters) {
        if (meters >= 1000) {
            return String.format("%.0f公里", meters / 1000);
        }
        return String.format("%.0f米", meters);
    }

    // ==================== Getters & Setters ====================

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}