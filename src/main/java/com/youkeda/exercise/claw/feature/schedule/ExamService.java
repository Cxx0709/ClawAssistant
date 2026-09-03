package com.youkeda.exercise.claw.feature.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ExamService {
    private static final Logger log = LoggerFactory.getLogger(ExamService.class);
    private final ExamRepository examRepository;
    public ExamService(ExamRepository examRepository) { this.examRepository = examRepository; }

    public List<ExamEntity> saveExams(String userId, List<ExamEntity> exams) {
        exams.forEach(e -> e.setUserId(userId));
        return examRepository.replaceAll(userId, exams);
    }

    public List<ExamEntity> getAllExams(String userId) { return examRepository.findByUserId(userId); }
    public List<ExamEntity> getUpcomingExams(String userId) {
        return getExamsWithinDays(userId, 30);
    }
    public List<ExamEntity> getExamsWithinDays(String userId, int days) {
        List<ExamEntity> upcoming = examRepository.findUpcoming(userId, LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        LocalDate deadline = LocalDate.now().plusDays(days);
        return upcoming.stream().filter(e -> { try { return !LocalDate.parse(e.getExamDate(), DateTimeFormatter.ISO_LOCAL_DATE).isAfter(deadline); } catch (Exception ex) { return false; } }).collect(Collectors.toList());
    }
    public List<ExamEntity> getExamsByDate(String userId, String examDate) { return examRepository.findByDate(userId, examDate); }
    public int getExamCount(String userId) { return examRepository.countByUserId(userId); }
    public void deleteAll(String userId) { examRepository.deleteByUserId(userId); }
    public boolean deleteExam(Long id, String userId) { return examRepository.deleteById(id, userId); }
    public boolean updateExam(ExamEntity exam) { if (exam.getId() == null || exam.getUserId() == null) return false; return examRepository.update(exam); }
    public ExamEntity findExamById(Long id) { return examRepository.findById(id); }
}