package com.youkeda.exercise.claw.web.conversation;

import java.util.List;

public record MessagePage(List<TranscriptMessage> items, String nextCursor) {
}
