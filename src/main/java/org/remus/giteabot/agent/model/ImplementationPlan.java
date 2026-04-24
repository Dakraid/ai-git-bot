package org.remus.giteabot.agent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ImplementationPlan {

    /**
     * Short summary of the planned implementation.
     */
    private String summary;

    /**
     * Files the AI requests to see before proceeding.
     * If non-empty, fetch these files and continue the conversation.
     */
    private List<String> requestFiles;

    /**
     * Repository exploration tools the AI wants to run before coding.
     */
    private List<ToolRequest> requestTools;

    /**
     * Branch name suggested by the AI (informational only).
     */
    private String branchName;

    /**
     * Single tool the AI wants to run for validation (kept for backward compatibility).
     * Prefer {@link #toolRequests} for new code.
     */
    private ToolRequest toolRequest;

    /**
     * List of tools the AI wants to run (file modifications, context exploration and/or validation).
     * Takes precedence over the single {@link #toolRequest} field when non-empty.
     */
    private List<ToolRequest> toolRequests;

    /**
     * Returns true if the AI is requesting additional files.
     */
    public boolean hasFileRequests() {
        return requestFiles != null && !requestFiles.isEmpty();
    }

    /**
     * Returns true if the AI is requesting repository exploration tools.
     */
    public boolean hasContextToolRequests() {
        return requestTools != null && !requestTools.isEmpty();
    }

    /**
     * Returns true if the AI is requesting any additional repository context.
     */
    public boolean hasContextRequests() {
        return hasFileRequests() || hasContextToolRequests();
    }


    /**
     * Returns true if the AI wants to run at least one tool.
     */
    public boolean hasToolRequest() {
        return !getEffectiveToolRequests().isEmpty();
    }

    /**
     * Returns the effective list of tool requests, merging {@link #toolRequests} and the
     * legacy {@link #toolRequest} field for backward compatibility.
     * {@link #toolRequests} takes precedence when non-empty.
     */
    public List<ToolRequest> getEffectiveToolRequests() {
        if (toolRequests != null && !toolRequests.isEmpty()) {
            return toolRequests;
        }
        if (toolRequest != null && toolRequest.getTool() != null && !toolRequest.getTool().isBlank()) {
            return List.of(toolRequest);
        }
        return List.of();
    }

    @Data
    @Builder
    public static class ToolRequest {
        /**
         * Stable identifier set by the AI to correlate results back to requests.
         */
        private String id;
        private String tool;
        private List<String> args;
    }
}
