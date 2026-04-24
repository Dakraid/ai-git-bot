package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.config.AgentConfigProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionServiceTest {

    private ToolExecutionService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new ToolExecutionService(new AgentConfigProperties());
    }

    @Test
    void getAvailableContextTools_containsExpectedTools() {
        assertThat(service.getAvailableContextTools())
                .contains("rg", "grep", "find", "cat", "git-log", "git-blame", "tree");
    }

    @Test
    void executeContextTool_cat_readsRequestedRangeWithLineNumbers() throws IOException {
        Path file = tempDir.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                line 1
                line 2
                line 3
                """);

        ToolResult result = service.executeContextTool(tempDir, "cat", List.of("src/Main.java", "2", "3"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("2 | line 2");
        assertThat(result.output()).contains("3 | line 3");
        assertThat(result.output()).doesNotContain("1 | line 1");
    }

    @Test
    void executeContextTool_rg_searchesWorkspace() throws IOException {
        Path file = tempDir.resolve("src/Config.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                class Config {
                    private ConfigService service;
                }
                """);

        ToolResult result = service.executeContextTool(tempDir, "rg", List.of("ConfigService", "src"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/Config.java:2:     private ConfigService service;");
    }

    @Test
    void executeContextTool_find_matchesGlobPattern() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/app.yml"), "name: app");
        Files.writeString(tempDir.resolve("src/Config.java"), "class Config {}");

        ToolResult result = service.executeContextTool(tempDir, "find", List.of("*.yml", "src"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/app.yml");
    }

    // ---- File tool tests ----

    @Test
    void executeFileTool_writeFile_createsFileWithContent() throws IOException {
        ToolResult result = service.executeFileTool(tempDir, "write-file",
                List.of("src/main/Foo.java", "public class Foo {}"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/main/Foo.java");
        assertThat(Files.readString(tempDir.resolve("src/main/Foo.java"))).isEqualTo("public class Foo {}");
    }

    @Test
    void executeFileTool_writeFile_overwritesExistingFile() throws IOException {
        Path target = tempDir.resolve("Existing.java");
        Files.writeString(target, "old content");

        ToolResult result = service.executeFileTool(tempDir, "write-file",
                List.of("Existing.java", "new content"));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(target)).isEqualTo("new content");
    }

    @Test
    void executeFileTool_patchFile_replacesExactText() throws IOException {
        Path target = tempDir.resolve("Service.java");
        Files.writeString(target, "class Service {\n    private int x = 1;\n}\n");

        ToolResult result = service.executeFileTool(tempDir, "patch-file",
                List.of("Service.java", "private int x = 1;", "private int x = 42;"));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(target)).contains("private int x = 42;");
    }

    @Test
    void executeFileTool_patchFile_searchTextNotFound_returnsFailure() throws IOException {
        Path target = tempDir.resolve("Service.java");
        Files.writeString(target, "class Service {}");

        ToolResult result = service.executeFileTool(tempDir, "patch-file",
                List.of("Service.java", "does not exist", "replacement"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("search text not found");
    }

    @Test
    void executeFileTool_mkdir_createsDirectory() {
        ToolResult result = service.executeFileTool(tempDir, "mkdir",
                List.of("new/nested/dir"));

        assertThat(result.success()).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("new/nested/dir"))).isTrue();
    }

    @Test
    void executeFileTool_deleteFile_removesFile() throws IOException {
        Path target = tempDir.resolve("ToDelete.java");
        Files.writeString(target, "delete me");

        ToolResult result = service.executeFileTool(tempDir, "delete-file",
                List.of("ToDelete.java"));

        assertThat(result.success()).isTrue();
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void executeFileTool_deleteFile_nonexistent_returnsSuccess() {
        ToolResult result = service.executeFileTool(tempDir, "delete-file",
                List.of("DoesNotExist.java"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("did not exist");
    }

    @Test
    void executeFileTool_pathTraversal_returnsError() {
        ToolResult result = service.executeFileTool(tempDir, "write-file",
                List.of("../../etc/passwd", "evil content"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("escapes");
    }

    @Test
    void isFileTool_returnsCorrectly() {
        assertThat(service.isFileTool("write-file")).isTrue();
        assertThat(service.isFileTool("patch-file")).isTrue();
        assertThat(service.isFileTool("mkdir")).isTrue();
        assertThat(service.isFileTool("delete-file")).isTrue();
        assertThat(service.isFileTool("mvn")).isFalse();
        assertThat(service.isFileTool("rg")).isFalse();
        assertThat(service.isFileTool(null)).isFalse();
    }

    @Test
    void isSilentTool_fileToolsAreSilent() {
        assertThat(service.isSilentTool("write-file")).isTrue();
        assertThat(service.isSilentTool("cat")).isTrue();
        assertThat(service.isSilentTool("mvn")).isFalse();
    }

    @Test
    void isValidationTool_recognizesConfiguredTools() {
        // mvn is in the configured available-tools list (set up via agentConfig in setUp)
        assertThat(service.isValidationTool("mvn")).isTrue();
        // file and context tools are NOT validation tools
        assertThat(service.isValidationTool("write-file")).isFalse();
        assertThat(service.isValidationTool("cat")).isFalse();
        assertThat(service.isValidationTool("rg")).isFalse();
        assertThat(service.isValidationTool(null)).isFalse();
    }

    @Test
    void isValidationTool_notEquivalentToNotSilentTool() {
        // An unknown tool that is also not in the silent lists must NOT count as a validation tool.
        // Previously '!isSilentTool' was used which would incorrectly classify unknown tools.
        assertThat(service.isSilentTool("unknown-tool")).isFalse();    // not silent
        assertThat(service.isValidationTool("unknown-tool")).isFalse(); // but also not a validation tool
    }
}
