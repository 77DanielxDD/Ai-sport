package com.example.aisport.agent;

public class ToolCallRecord {

    private String tool;
    private boolean success;
    private String summary;
    private long durationMs;

    public ToolCallRecord() {}

    public ToolCallRecord(String tool, boolean success, String summary, long durationMs) {
        this.tool = tool;
        this.success = success;
        this.summary = summary;
        this.durationMs = durationMs;
    }

    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
}
