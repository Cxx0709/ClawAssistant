package com.youkeda.exercise.claw.domain.transport;

/**
 * 单种交通方式的对比结果
 */
public class TransportOption {

    /** 交通方式类型：bus / self_drive / high_speed_rail / flight */
    private String type;

    /** 类型中文名 */
    private String typeName;

    /** 总费用（元） */
    private double totalCost;

    /** 人均费用（元） */
    private double perPersonCost;

    /** 预计耗时（分钟） */
    private int durationMinutes;

    /** 耗时描述 */
    private String durationText;

    /** 行驶距离（公里） */
    private double distanceKm;

    /** 适合人数范围描述，如"30-50人"、"1-5人" */
    private String capacity;

    /** 所需车辆数（大巴/自驾适用），高铁/飞机为 0 */
    private int vehicleCount;

    /** 该方式的优势描述 */
    private String advantage;

    public TransportOption() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public double getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(double totalCost) {
        this.totalCost = totalCost;
    }

    public double getPerPersonCost() {
        return perPersonCost;
    }

    public void setPerPersonCost(double perPersonCost) {
        this.perPersonCost = perPersonCost;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getDurationText() {
        return durationText;
    }

    public void setDurationText(String durationText) {
        this.durationText = durationText;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(double distanceKm) {
        this.distanceKm = distanceKm;
    }

    public String getCapacity() {
        return capacity;
    }

    public void setCapacity(String capacity) {
        this.capacity = capacity;
    }

    public int getVehicleCount() {
        return vehicleCount;
    }

    public void setVehicleCount(int vehicleCount) {
        this.vehicleCount = vehicleCount;
    }

    public String getAdvantage() {
        return advantage;
    }

    public void setAdvantage(String advantage) {
        this.advantage = advantage;
    }

    @Override
    public String toString() {
        return "TransportOption{" +
                "type='" + type + '\'' +
                ", typeName='" + typeName + '\'' +
                ", totalCost=" + totalCost +
                ", perPersonCost=" + perPersonCost +
                ", durationMinutes=" + durationMinutes +
                ", distanceKm=" + distanceKm +
                ", capacity='" + capacity + '\'' +
                ", vehicleCount=" + vehicleCount +
                '}';
    }
}