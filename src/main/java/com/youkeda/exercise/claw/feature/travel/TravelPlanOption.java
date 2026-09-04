package com.youkeda.exercise.claw.feature.travel;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 旅游/出游流程中的一个候选方案。 */
public class TravelPlanOption {

    private String optionId;
    private String displayName;
    private int version = 1;
    private String positioning;
    private String highlights;
    private String itinerarySummary;
    private PlanStatus planStatus = PlanStatus.CANDIDATE;
    private CostStatus costStatus = CostStatus.NOT_CALCULATED;
    private JsonNode costResult;
    /** 结构化行程项（可空，旧数据自动容错） */
    private List<ItineraryItem> itineraryItems;

    public String getOptionId() { return optionId; }
    public void setOptionId(String optionId) { this.optionId = optionId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getPositioning() { return positioning; }
    public void setPositioning(String positioning) { this.positioning = positioning; }
    public String getHighlights() { return highlights; }
    public void setHighlights(String highlights) { this.highlights = highlights; }
    public String getItinerarySummary() { return itinerarySummary; }
    public void setItinerarySummary(String itinerarySummary) { this.itinerarySummary = itinerarySummary; }
    public PlanStatus getPlanStatus() { return planStatus; }
    public void setPlanStatus(PlanStatus planStatus) { this.planStatus = planStatus; }
    public CostStatus getCostStatus() { return costStatus; }
    public void setCostStatus(CostStatus costStatus) { this.costStatus = costStatus; }
    public JsonNode getCostResult() { return costResult; }
    public void setCostResult(JsonNode costResult) { this.costResult = costResult; }
    public List<ItineraryItem> getItineraryItems() { return itineraryItems; }
    public void setItineraryItems(List<ItineraryItem> itineraryItems) { this.itineraryItems = itineraryItems; }

    /** 结构化行程项 */
    public static class ItineraryItem {
        private String day;      // 如 "Day 1"
        private int seq;         // 序号
        private String title;    // 如 "西湖游览"
        private String time;     // 如 "09:00"
        private String status;   // done/adjusted/added
        private String note;     // 如 "刚从对话加进来"

        public ItineraryItem() {}

        public ItineraryItem(String day, int seq, String title, String time, String status, String note) {
            this.day = day;
            this.seq = seq;
            this.title = title;
            this.time = time;
            this.status = status;
            this.note = note;
        }

        public String getDay() { return day; }
        public void setDay(String day) { this.day = day; }
        public int getSeq() { return seq; }
        public void setSeq(int seq) { this.seq = seq; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }
}
