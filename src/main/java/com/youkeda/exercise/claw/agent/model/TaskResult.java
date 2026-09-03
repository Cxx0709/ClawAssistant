package com.youkeda.exercise.claw.agent.model;

/**
 * 单个任务的执行产物。
 *
 * <p>所有权规则：
 * <ul>
 *   <li>{@code rawResult} 和 {@code timestamp} 由 Runtime 在工具执行完成后写入（不需要理解内容）</li>
 *   <li>{@code summary} 由 LLM 在评估阶段写入语义摘要</li>
 * </ul>
 *
 * <p>{@code TaskResult} 没有主动更新机制——{@code timestamp} 让 LLM 能判断
 * 结果是否过时，是否需要重读 message 历史或重新执行。
 */
public class TaskResult {

    private String taskId;
    private String toolName;
    private ResultStatus status;

    /** Runtime 写入——工具的原始输出，不做语义理解 */
    private Object rawResult;

    /** LLM 写入——对结果的语义摘要（如"北京 25°C，适合户外"） */
    private String summary;

    /** 执行完成时间戳 */
    private long timestamp;

    public TaskResult() {}

    public TaskResult(String taskId, String toolName, ResultStatus status,
                      Object rawResult, long timestamp) {
        this.taskId = taskId;
        this.toolName = toolName;
        this.status = status;
        this.rawResult = rawResult;
        this.timestamp = timestamp;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public ResultStatus getStatus() { return status; }
    public void setStatus(ResultStatus status) { this.status = status; }

    public Object getRawResult() { return rawResult; }
    public void setRawResult(Object rawResult) { this.rawResult = rawResult; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
