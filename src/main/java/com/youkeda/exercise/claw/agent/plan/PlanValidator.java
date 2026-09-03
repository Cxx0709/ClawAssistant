package com.youkeda.exercise.claw.agent.plan;

import com.youkeda.exercise.claw.agent.model.PlanState;
import com.youkeda.exercise.claw.agent.model.PlanTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 计划合法性校验。
 *
 * <p>校验 LLM 产出的 Plan 是否结构合法。第一版覆盖：
 * <ul>
 *   <li>任务 ID 唯一性</li>
 *   <li>依赖指向存在的任务 ID</li>
 *   <li>DAG 无循环依赖</li>
 *   <li>任务数量在合理范围（1-20）</li>
 * </ul>
 */
@Component
public class PlanValidator {

    private static final Logger log = LoggerFactory.getLogger(PlanValidator.class);
    private static final int MAX_TASKS = 20;
    private static final int MIN_TASKS = 1;

    /**
     * 校验 LLM 产出的 Plan 是否结构合法。
     */
    public ValidationResult validate(PlanState plan) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (plan == null) {
            return new ValidationResult(false, List.of("Plan 为 null"), List.of());
        }

        List<PlanTask> tasks = plan.getTasks();
        if (tasks == null || tasks.isEmpty()) {
            return new ValidationResult(false, List.of("Plan 没有任务"), List.of());
        }

        // 1. 任务数量检查
        if (tasks.size() < MIN_TASKS) {
            errors.add("任务数量不能为 0");
        }
        if (tasks.size() > MAX_TASKS) {
            errors.add("任务数量超过上限 " + MAX_TASKS + "，当前 " + tasks.size());
        }

        // 2. 任务 ID 唯一性
        Set<String> ids = new HashSet<>();
        for (PlanTask task : tasks) {
            if (task.getId() == null || task.getId().isBlank()) {
                errors.add("任务缺少 ID: " + task.getDescription());
                continue;
            }
            if (!ids.add(task.getId())) {
                errors.add("任务 ID 重复: " + task.getId());
            }
        }
        if (!errors.isEmpty()) return new ValidationResult(false, errors, warnings);

        // 3. 依赖存在性检查
        for (PlanTask task : tasks) {
            if (task.getDependencies() == null) continue;
            for (String depId : task.getDependencies()) {
                if (!ids.contains(depId)) {
                    errors.add("任务 " + task.getId() + " 的依赖 " + depId + " 不存在");
                }
            }
        }

        // 4. DAG 无循环依赖（拓扑排序）
        if (errors.isEmpty()) {
            String cycle = detectCycle(tasks);
            if (cycle != null) {
                errors.add("任务依赖存在循环: " + cycle);
            }
        }

        // 5. 无孤立任务（可选警告）
        boolean allIsolated = true;
        for (PlanTask task : tasks) {
            if (task.getDependencies() != null && !task.getDependencies().isEmpty()) {
                allIsolated = false;
                break;
            }
            boolean isDep = tasks.stream()
                    .filter(t -> t.getDependencies() != null)
                    .anyMatch(t -> t.getDependencies().contains(task.getId()));
            if (isDep) {
                allIsolated = false;
                break;
            }
        }
        if (allIsolated && tasks.size() > 1) {
            warnings.add("所有任务之间没有依赖关系，可能是独立执行的简单任务");
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * 检测 DAG 中是否存在循环依赖。
     *
     * @return 循环描述字符串，无循环则返回 null
     */
    private String detectCycle(List<PlanTask> tasks) {
        Map<String, List<String>> adj = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (PlanTask task : tasks) {
            adj.putIfAbsent(task.getId(), new ArrayList<>());
            inDegree.putIfAbsent(task.getId(), 0);
        }
        for (PlanTask task : tasks) {
            if (task.getDependencies() == null) continue;
            for (String depId : task.getDependencies()) {
                adj.computeIfAbsent(depId, k -> new ArrayList<>()).add(task.getId());
                inDegree.merge(task.getId(), 1, Integer::sum);
            }
        }

        // Kahn 拓扑排序
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        int visited = 0;
        while (!queue.isEmpty()) {
            String node = queue.poll();
            visited++;
            for (String neighbor : adj.getOrDefault(node, List.of())) {
                inDegree.merge(neighbor, -1, Integer::sum);
                if (inDegree.get(neighbor) == 0) queue.add(neighbor);
            }
        }

        if (visited != tasks.size()) {
            // 找到仍在环中的节点
            List<String> cyclicNodes = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
                if (entry.getValue() > 0) cyclicNodes.add(entry.getKey());
            }
            return String.join(" → ", cyclicNodes);
        }
        return null;
    }
}
