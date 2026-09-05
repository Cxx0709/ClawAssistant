package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.feature.schedule.ExamEntity;
import com.youkeda.exercise.claw.feature.schedule.ExamService;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/workspace")
public class ExamBoardController {
    private final ExamService exams;
    private final AuthenticatedUser users;
    private final UserExecutionContext context;

    public ExamBoardController(ExamService exams, AuthenticatedUser users, UserExecutionContext context) {
        this.exams = exams;
        this.users = users;
        this.context = context;
    }

    @GetMapping("/exams")
    public Map<String, Object> upcoming(Authentication authentication) {
        return scoped(authentication, () -> {
            String userId = context.requireUserId();
            List<ExamView> items = exams.getUpcomingExams(userId).stream()
                    .map(ExamView::from)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(ExamView::examDate)
                            .thenComparing(ExamView::startTime))
                    .toList();
            return Map.of("items", items);
        });
    }

    private <T> T scoped(Authentication authentication, Supplier<T> operation) {
        String userId = users.require(authentication).id();
        try (var ignored = context.open(userId)) {
            return operation.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "考试安排暂时不可用，请稍后重试", e);
        }
    }

    public record ExamView(
            Long id,
            String courseName,
            String examDate,
            String startTime,
            String endTime,
            String location,
            String seatNumber,
            String examType,
            String examTypeDisplay,
            String notes,
            long daysLeft
    ) {
        static ExamView from(ExamEntity exam) {
            try {
                LocalDate date = LocalDate.parse(exam.getExamDate());
                long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), date);
                if (daysLeft < 0) return null;
                return new ExamView(
                        exam.getId(), text(exam.getCourseName()), exam.getExamDate(),
                        text(exam.getStartTime()), text(exam.getEndTime()), text(exam.getLocation()),
                        text(exam.getSeatNumber()), text(exam.getExamType()),
                        text(exam.getExamTypeDisplay()), text(exam.getNotes()), daysLeft);
            } catch (DateTimeParseException | NullPointerException ignored) {
                return null;
            }
        }

        private static String text(String value) {
            return value == null ? "" : value;
        }
    }
}
