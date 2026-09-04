package com.youkeda.exercise.claw.api;

import com.youkeda.exercise.claw.feature.travel.TravelPlanDraft;
import com.youkeda.exercise.claw.feature.travel.TravelPlanOption;
import com.youkeda.exercise.claw.feature.travel.TravelPlanStateStore;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private final TravelPlanStateStore stateStore;
    private final UserExecutionContext userExecutionContext;

    public WorkspaceController(TravelPlanStateStore stateStore, UserExecutionContext userExecutionContext) {
        this.stateStore = stateStore;
        this.userExecutionContext = userExecutionContext;
    }

    @GetMapping("/boards")
    public ResponseEntity<List<BoardResponse>> getBoards(@RequestParam(required = false) String conversationId) {
        String userId = userExecutionContext.requireUserId();
        TravelPlanDraft draft = stateStore.get(userId);
        if (draft == null) {
            return ResponseEntity.ok(List.of());
        }

        List<BoardResponse> boards = draft.getOptions().stream()
                .filter(o -> o.getItineraryItems() != null && !o.getItineraryItems().isEmpty())
                .map(this::toBoardResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(boards);
    }

    @PatchMapping("/boards/{optionId}/items")
    public ResponseEntity<Map<String, Object>> updateBoardItem(
            @PathVariable String optionId,
            @RequestBody UpdateItemRequest request) {
        String userId = userExecutionContext.requireUserId();
        TravelPlanDraft draft = stateStore.get(userId);
        if (draft == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No active plan"));
        }

        TravelPlanOption option = draft.getOptions().stream()
                .filter(o -> o.getOptionId().equals(optionId))
                .findFirst()
                .orElse(null);

        if (option == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Option not found: " + optionId));
        }

        List<TravelPlanOption.ItineraryItem> items = option.getItineraryItems();
        if (items == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No itinerary items"));
        }

        // Find and update the item
        TravelPlanOption.ItineraryItem item = items.stream()
                .filter(i -> i.getSeq() == request.seq && i.getDay().equals(request.day))
                .findFirst()
                .orElse(null);

        if (item == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Item not found"));
        }

        if (request.title != null) item.setTitle(request.title);
        if (request.time != null) item.setTime(request.time);
        if (request.status != null) item.setStatus(request.status);
        if (request.note != null) item.setNote(request.note);

        stateStore.save(userId, draft);

        return ResponseEntity.ok(Map.of("status", "OK", "updated", toItemResponse(item)));
    }

    private BoardResponse toBoardResponse(TravelPlanOption option) {
        BoardResponse board = new BoardResponse();
        board.optionId = option.getOptionId();
        board.title = option.getDisplayName();
        board.stats = option.getItinerarySummary();
        board.days = groupByDay(option.getItineraryItems());
        return board;
    }

    private List<DayGroup> groupByDay(List<TravelPlanOption.ItineraryItem> items) {
        if (items == null) return List.of();

        Map<String, List<TravelPlanOption.ItineraryItem>> grouped = items.stream()
                .collect(Collectors.groupingBy(TravelPlanOption.ItineraryItem::getDay));

        return grouped.entrySet().stream()
                .map(entry -> {
                    DayGroup day = new DayGroup();
                    day.label = entry.getKey();
                    day.items = entry.getValue().stream()
                            .map(this::toItemResponse)
                            .collect(Collectors.toList());
                    return day;
                })
                .collect(Collectors.toList());
    }

    private ItemResponse toItemResponse(TravelPlanOption.ItineraryItem item) {
        ItemResponse resp = new ItemResponse();
        resp.seq = item.getSeq();
        resp.title = item.getTitle();
        resp.time = item.getTime();
        resp.status = item.getStatus();
        resp.note = item.getNote();
        return resp;
    }

    // Response DTOs
    public static class BoardResponse {
        public String optionId;
        public String title;
        public String stats;
        public List<DayGroup> days;
    }

    public static class DayGroup {
        public String label;
        public List<ItemResponse> items;
    }

    public static class ItemResponse {
        public int seq;
        public String title;
        public String time;
        public String status;
        public String note;
    }

    public static class UpdateItemRequest {
        public String day;
        public int seq;
        public String title;
        public String time;
        public String status;
        public String note;
    }
}
