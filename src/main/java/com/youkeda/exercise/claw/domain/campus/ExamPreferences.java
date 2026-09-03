package com.youkeda.exercise.claw.domain.campus;

import java.util.List;

public class ExamPreferences {
    private int remindBeforeDays = 1;
    private List<String> autoPushTypes = List.of("FINAL_EXAM", "CET");

    public int getRemindBeforeDays() { return remindBeforeDays; }
    public void setRemindBeforeDays(int remindBeforeDays) { this.remindBeforeDays = remindBeforeDays; }

    public List<String> getAutoPushTypes() { return autoPushTypes; }
    public void setAutoPushTypes(List<String> autoPushTypes) { this.autoPushTypes = autoPushTypes; }
}
