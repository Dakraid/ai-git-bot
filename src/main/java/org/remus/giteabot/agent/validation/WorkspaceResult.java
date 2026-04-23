package org.remus.giteabot.agent.validation;

import java.nio.file.Path;

/**
 * Result of workspace preparation — either a path to the ready workspace
 * or an error description.
 */
public record WorkspaceResult(
        boolean success,
        Path workspacePath,
        String error
) {
    public static WorkspaceResult success(Path path) {
        return new WorkspaceResult(true, path, null);
    }

    public static WorkspaceResult failure(String error) {
        return new WorkspaceResult(false, null, error);
    }
}
