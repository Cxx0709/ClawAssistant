package com.youkeda.exercise.claw.web.conversation;

import java.util.List;

public record ConversationPage(List<Conversation> items, String nextCursor) {
}
