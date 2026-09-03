package com.youkeda.exercise.claw.domain.transport;

import java.util.List;

/**
 * 交通方式推荐完整结果
 */
public class TransportResult {

    /** 出发地 */
    private String from;

    /** 目的地 */
    private String to;

    /** 人数 */
    private int people;

    /** 驾车距离（米） */
    private int distanceMeters;

    /** 驾车距离（公里） */
    private double distanceKm;

    /** 驾车耗时（秒） */
    private int drivingDurationSeconds;

    /** 各交通方式对比列表 */
    private List<TransportOption> options;

    /** 推荐结论（面向用户展示） */
    private String recommendation;

    /** 推荐理由（详细说明为什么推荐该方式） */
    private String recommendationReason;

    /** 可选预算 */
    private Integer budget;

    public TransportResult() {
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public int getPeople() {
        return people;
    }

    public void setPeople(int people) {
        this.people = people;
    }

    public int getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(int distanceMeters) {
        this.distanceMeters = distanceMeters;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(double distanceKm) {
        this.distanceKm = distanceKm;
    }

    public int getDrivingDurationSeconds() {
        return drivingDurationSeconds;
    }

    public void setDrivingDurationSeconds(int drivingDurationSeconds) {
        this.drivingDurationSeconds = drivingDurationSeconds;
    }

    public List<TransportOption> getOptions() {
        return options;
    }

    public void setOptions(List<TransportOption> options) {
        this.options = options;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public String getRecommendationReason() {
        return recommendationReason;
    }

    public void setRecommendationReason(String recommendationReason) {
        this.recommendationReason = recommendationReason;
    }

    public Integer getBudget() {
        return budget;
    }

    public void setBudget(Integer budget) {
        this.budget = budget;
    }

    @Override
    public String toString() {
        return "TransportResult{" +
                "from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", people=" + people +
                ", distanceKm=" + distanceKm +
                ", options=" + (options != null ? options.size() : 0) +
                ", recommendation='" + recommendation + '\'' +
                '}';
    }
}