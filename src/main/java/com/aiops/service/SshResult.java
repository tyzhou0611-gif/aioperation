package com.aiops.service;

public class SshResult {
    private Long serverId;
    private String hostname;
    private String ip;
    private boolean success;
    private String stdout;
    private String stderr;
    private int exitCode;
    private String errorMessage;

    public SshResult() {}
    public Long getServerId() { return serverId; } public void setServerId(Long v) { this.serverId = v; }
    public String getHostname() { return hostname; } public void setHostname(String v) { this.hostname = v; }
    public String getIp() { return ip; } public void setIp(String v) { this.ip = v; }
    public boolean isSuccess() { return success; } public void setSuccess(boolean v) { this.success = v; }
    public String getStdout() { return stdout; } public void setStdout(String v) { this.stdout = v; }
    public String getStderr() { return stderr; } public void setStderr(String v) { this.stderr = v; }
    public int getExitCode() { return exitCode; } public void setExitCode(int v) { this.exitCode = v; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { this.errorMessage = v; }
}