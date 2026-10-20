package com.aiops.service;

/**
 * 一键命令异步执行任务的状态快照
 */
public class ExecTask {
    private String taskId;
    private String status;   // RUNNING / SUCCESS / FAILED / ERROR
    private String output;
    private boolean success;
    private String targetIp;

    public ExecTask(String taskId) {
        this.taskId = taskId; this.status = "RUNNING"; this.output = ""; this.success = false;
    }

    public String getTaskId() { return taskId; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getOutput() { return output; } public void setOutput(String v) { output = v; }
    public boolean isSuccess() { return success; } public void setSuccess(boolean v) { success = v; }
    public String getTargetIp() { return targetIp; } public void setTargetIp(String v) { targetIp = v; }
}