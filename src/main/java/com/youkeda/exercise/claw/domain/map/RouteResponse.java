package com.youkeda.exercise.claw.domain.map;

/**
 * 路线规划结果
 *
 * <p>封装完整的路线规划结果，支持格式化为自然语言文本返回给 LLM。
 */
public class RouteResponse {

    private String origin;
    private String destination;
    private double distance;        // 总距离，单位：米
    private int duration;           // 总耗时，单位：秒
    private String mode;            // driving / walking / transit
    private String polyline;        // 路线概览（简要描述）

    public RouteResponse() {
    }

    public RouteResponse(String origin, String destination, double distance,
                         int duration, String mode, String polyline) {
        this.origin = origin;
        this.destination = destination;
        this.distance = distance;
        this.duration = duration;
        this.mode = mode;
        this.polyline = polyline;
    }

    /**
     * 格式化为自然语言文本
     */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append(origin).append("到").append(destination).append("：\n");

        String modeLabel = switch (mode != null ? mode : "driving") {
            case "walking" -> "步行";
            case "transit" -> "公交";
            default -> "驾车";
        };

        sb.append(modeLabel);

        // 距离
        if (distance > 0) {
            String distText;
            if (distance >= 1000) {
                distText = String.format("约%.0f公里", distance / 1000);
            } else {
                distText = String.format("约%.0f米", distance);
            }
            sb.append(distText);
        }

        // 耗时
        if (duration > 0) {
            int hours = duration / 3600;
            int minutes = (duration % 3600) / 60;
            if (hours > 0 && minutes > 0) {
                sb.append("，预计").append(hours).append("小时").append(minutes).append("分钟");
            } else if (hours > 0) {
                sb.append("，预计").append(hours).append("小时");
            } else {
                sb.append("，预计").append(minutes).append("分钟");
            }
        }

        sb.append("。");

        // 路线概览
        if (polyline != null && !polyline.isBlank()) {
            sb.append("\n推荐路线：\n").append(polyline);
        }

        return sb.toString();
    }

    // ==================== Getters & Setters ====================

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getPolyline() {
        return polyline;
    }

    public void setPolyline(String polyline) {
        this.polyline = polyline;
    }
}