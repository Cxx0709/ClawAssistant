package com.youkeda.exercise.claw.domain.transport;

/**
 * 交通方式推荐请求参数
 *
 * <p>由 LLM 提取并传入 {@link com.youkeda.exercise.claw.feature.transport.TransportRecommendFunction}
 */
public class TransportRequest {

    /** 出发地 */
    private String from;

    /** 目的地 */
    private String to;

    /** 出行人数 */
    private int people;

    /** 可选预算（元） */
    private Integer budget;

    public TransportRequest() {
    }

    public TransportRequest(String from, String to, int people, Integer budget) {
        this.from = from;
        this.to = to;
        this.people = people;
        this.budget = budget;
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

    public Integer getBudget() {
        return budget;
    }

    public void setBudget(Integer budget) {
        this.budget = budget;
    }

    @Override
    public String toString() {
        return "TransportRequest{" +
                "from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", people=" + people +
                ", budget=" + budget +
                '}';
    }
}