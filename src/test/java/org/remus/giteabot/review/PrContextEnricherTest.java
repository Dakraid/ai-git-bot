package org.remus.giteabot.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrContextEnricherTest {

    @Mock
    private RepositoryApiClient repositoryClient;

    private PrContextEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new PrContextEnricher(repositoryClient);
    }

    // --- extractChangedFilePaths ---

    @Test
    void extractChangedFilePaths_parsesUnifiedDiff() {
        String diff = """
                diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
                index abc123..def456 100644
                --- a/src/main/java/Foo.java
                +++ b/src/main/java/Foo.java
                @@ -10,7 +10,7 @@
                 some context
                -old line
                +new line
                diff --git a/src/test/java/FooTest.java b/src/test/java/FooTest.java
                index 111..222 100644
                """;

        List<String> paths = enricher.extractChangedFilePaths(diff);

        assertEquals(2, paths.size());
        assertTrue(paths.contains("src/main/java/Foo.java"));
        assertTrue(paths.contains("src/test/java/FooTest.java"));
    }

    @Test
    void extractChangedFilePaths_emptyDiff() {
        List<String> paths = enricher.extractChangedFilePaths("");
        assertTrue(paths.isEmpty());
    }

    @Test
    void extractChangedFilePaths_noDiffHeaders() {
        String diff = "+just some added content\n-and removed content";
        List<String> paths = enricher.extractChangedFilePaths(diff);
        assertTrue(paths.isEmpty());
    }

    @Test
    void extractChangedFilePaths_deduplicatesFiles() {
        String diff = """
                diff --git a/file.txt b/file.txt
                some changes
                diff --git a/file.txt b/file.txt
                more changes
                """;

        List<String> paths = enricher.extractChangedFilePaths(diff);
        assertEquals(1, paths.size());
        assertEquals("file.txt", paths.getFirst());
    }

    // --- extractIssueReferences ---

    @Test
    void extractIssueReferences_findsHashReferences() {
        Set<Long> issues = enricher.extractIssueReferences("This PR fixes #42 and relates to #123");
        assertTrue(issues.contains(42L));
        assertTrue(issues.contains(123L));
    }

    @Test
    void extractIssueReferences_findsKeywordReferences() {
        Set<Long> issues = enricher.extractIssueReferences("closes #10, fixes #20, resolves #30");
        assertEquals(3, issues.size());
        assertTrue(issues.contains(10L));
        assertTrue(issues.contains(20L));
        assertTrue(issues.contains(30L));
    }

    @Test
    void extractIssueReferences_emptyBody() {
        Set<Long> issues = enricher.extractIssueReferences("");
        assertTrue(issues.isEmpty());
    }

    @Test
    void extractIssueReferences_nullBody() {
        Set<Long> issues = enricher.extractIssueReferences(null);
        assertTrue(issues.isEmpty());
    }

    @Test
    void extractIssueReferences_noReferences() {
        Set<Long> issues = enricher.extractIssueReferences("This is a simple PR with no issue references");
        assertTrue(issues.isEmpty());
    }

    // --- buildRepositoryTreeContext ---

    @Test
    void buildRepositoryTreeContext_formatsTree() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenReturn(List.of(
                        Map.of("type", "blob", "path", "pom.xml"),
                        Map.of("type", "blob", "path", "src/main/java/Foo.java"),
                        Map.of("type", "tree", "path", "src")
                ));

        String result = enricher.buildRepositoryTreeContext("owner", "repo", "main");

        assertTrue(result.contains("pom.xml"));
        assertTrue(result.contains("src/main/java/Foo.java"));
        assertTrue(result.contains("Repository structure"));
        // tree type entries should not be listed as files
        assertFalse(result.contains("  src\n"));
    }

    @Test
    void buildRepositoryTreeContext_emptyTree() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenReturn(List.of());

        String result = enricher.buildRepositoryTreeContext("owner", "repo", "main");

        assertEquals("", result);
    }

    @Test
    void buildRepositoryTreeContext_handlesApiError() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenThrow(new RuntimeException("API error"));

        String result = enricher.buildRepositoryTreeContext("owner", "repo", "main");

        assertEquals("", result);
    }

    // --- buildChangedFileContents ---

    @Test
    void buildChangedFileContents_fetchesContent() {
        String diff = "diff --git a/src/Foo.java b/src/Foo.java\n+new line";
        when(repositoryClient.getFileContent("owner", "repo", "src/Foo.java", "feature"))
                .thenReturn("public class Foo {\n}");

        String result = enricher.buildChangedFileContents("owner", "repo", diff, "feature");

        assertTrue(result.contains("src/Foo.java"));
        assertTrue(result.contains("public class Foo"));
        assertTrue(result.contains("Changed files"));
    }

    @Test
    void buildChangedFileContents_emptyDiff() {
        String result = enricher.buildChangedFileContents("owner", "repo", "", "feature");
        assertEquals("", result);
    }

    @Test
    void buildChangedFileContents_nullRef() {
        String result = enricher.buildChangedFileContents("owner", "repo", "some diff", null);
        assertEquals("", result);
    }

    @Test
    void buildChangedFileContents_handlesFileNotFound() {
        String diff = "diff --git a/deleted.java b/deleted.java\n-old content";
        when(repositoryClient.getFileContent("owner", "repo", "deleted.java", "feature"))
                .thenThrow(new RuntimeException("Not found"));

        String result = enricher.buildChangedFileContents("owner", "repo", diff, "feature");

        // Should not crash, returns the header but no file content
        assertTrue(result.contains("Changed files"));
    }

    @Test
    void buildChangedFileContents_truncatesLargeFiles() {
        String diff = "diff --git a/large.txt b/large.txt\n+content";
        String largeContent = "x".repeat(PrContextEnricher.MAX_SINGLE_FILE_CHARS + 1000);
        when(repositoryClient.getFileContent("owner", "repo", "large.txt", "feature"))
                .thenReturn(largeContent);

        String result = enricher.buildChangedFileContents("owner", "repo", diff, "feature");

        assertTrue(result.contains("truncated"));
    }

    // --- buildCommitMessagesContext ---

    @Test
    void buildCommitMessagesContext_formatsGiteaCommits() {
        // Gitea/GitHub format: commit message is nested under "commit" key
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenReturn(List.of(
                        Map.of("sha", "abc1234567890", "commit", Map.of("message", "Initial implementation")),
                        Map.of("sha", "def5678901234", "commit", Map.of("message", "Fix bug"))
                ));

        String result = enricher.buildCommitMessagesContext("owner", "repo", 1L);

        assertTrue(result.contains("Commit messages"));
        assertTrue(result.contains("abc1234"));
        assertTrue(result.contains("Initial implementation"));
        assertTrue(result.contains("def5678"));
        assertTrue(result.contains("Fix bug"));
    }

    @Test
    void buildCommitMessagesContext_formatsGitLabCommits() {
        // GitLab format: message is at top level, sha is in "id"
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenReturn(List.of(
                        Map.of("id", "abc1234567890", "message", "Add feature\n\nDetailed description"),
                        Map.of("id", "def5678901234", "message", "Update tests")
                ));

        String result = enricher.buildCommitMessagesContext("owner", "repo", 1L);

        assertTrue(result.contains("abc1234"));
        assertTrue(result.contains("Add feature"));
        // Only first line should be included
        assertFalse(result.contains("Detailed description"));
    }

    @Test
    void buildCommitMessagesContext_emptyCommits() {
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenReturn(List.of());

        String result = enricher.buildCommitMessagesContext("owner", "repo", 1L);

        assertEquals("", result);
    }

    @Test
    void buildCommitMessagesContext_handlesApiError() {
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenThrow(new RuntimeException("API error"));

        String result = enricher.buildCommitMessagesContext("owner", "repo", 1L);

        assertEquals("", result);
    }

    // --- buildReferencedIssueContext ---

    @Test
    void buildReferencedIssueContext_fetchesIssueDetails() {
        when(repositoryClient.getIssueDetails("owner", "repo", 42L))
                .thenReturn(Map.of("title", "Add user authentication", "body", "We need OAuth2 support"));

        String result = enricher.buildReferencedIssueContext("owner", "repo", "fixes #42");

        assertTrue(result.contains("Referenced issues"));
        assertTrue(result.contains("#42"));
        assertTrue(result.contains("Add user authentication"));
        assertTrue(result.contains("OAuth2 support"));
    }

    @Test
    void buildReferencedIssueContext_multipleIssues() {
        when(repositoryClient.getIssueDetails("owner", "repo", 10L))
                .thenReturn(Map.of("title", "Issue 10", "body", "Body 10"));
        when(repositoryClient.getIssueDetails("owner", "repo", 20L))
                .thenReturn(Map.of("title", "Issue 20", "body", "Body 20"));

        String result = enricher.buildReferencedIssueContext("owner", "repo", "closes #10, fixes #20");

        assertTrue(result.contains("#10"));
        assertTrue(result.contains("Issue 10"));
        assertTrue(result.contains("#20"));
        assertTrue(result.contains("Issue 20"));
    }

    @Test
    void buildReferencedIssueContext_nullBody() {
        String result = enricher.buildReferencedIssueContext("owner", "repo", null);
        assertEquals("", result);
    }

    @Test
    void buildReferencedIssueContext_noReferences() {
        String result = enricher.buildReferencedIssueContext("owner", "repo", "Simple PR description");
        assertEquals("", result);
    }

    @Test
    void buildReferencedIssueContext_handlesApiError() {
        when(repositoryClient.getIssueDetails("owner", "repo", 42L))
                .thenThrow(new RuntimeException("Not found"));

        String result = enricher.buildReferencedIssueContext("owner", "repo", "fixes #42");

        assertTrue(result.contains("Referenced issues"));
    }

    // --- buildEnrichedContext ---

    @Test
    void buildEnrichedContext_combinesAllSections() {
        String diff = "diff --git a/src/Foo.java b/src/Foo.java\n+new line";

        when(repositoryClient.getRepositoryTree("owner", "repo", "feature"))
                .thenReturn(List.of(
                        Map.of("type", "blob", "path", "src/Foo.java"),
                        Map.of("type", "blob", "path", "pom.xml")
                ));
        when(repositoryClient.getFileContent("owner", "repo", "src/Foo.java", "feature"))
                .thenReturn("class Foo {}");
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenReturn(List.of(
                        Map.of("sha", "abc1234", "commit", Map.of("message", "Add Foo"))
                ));
        when(repositoryClient.getIssueDetails("owner", "repo", 5L))
                .thenReturn(Map.of("title", "Create Foo class", "body", "Need a Foo implementation"));

        String result = enricher.buildEnrichedContext("owner", "repo", 1L, diff, "feature", "fixes #5");

        assertTrue(result.contains("Repository structure"));
        assertTrue(result.contains("pom.xml"));
        assertTrue(result.contains("Changed files"));
        assertTrue(result.contains("class Foo"));
        assertTrue(result.contains("Commit messages"));
        assertTrue(result.contains("Add Foo"));
        assertTrue(result.contains("Referenced issues"));
        assertTrue(result.contains("Create Foo class"));
    }

    @Test
    void buildEnrichedContext_handlesAllFailuresGracefully() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenThrow(new RuntimeException("tree error"));
        when(repositoryClient.getPullRequestCommits("owner", "repo", 1L))
                .thenThrow(new RuntimeException("commits error"));

        String result = enricher.buildEnrichedContext("owner", "repo", 1L, null, "main", null);

        // Should not throw, returns empty or minimal content
        assertNotNull(result);
    }

    @Test
    void buildEnrichedContext_nullDiffAndBody() {
        when(repositoryClient.getRepositoryTree("owner", "repo", "main"))
                .thenReturn(List.of(Map.of("type", "blob", "path", "README.md")));

        String result = enricher.buildEnrichedContext("owner", "repo", 1L, null, "main", null);

        assertTrue(result.contains("Repository structure"));
        assertTrue(result.contains("README.md"));
        // No file contents, commits, or issue sections when diff and body are null
        assertFalse(result.contains("Changed files"));
        verify(repositoryClient, never()).getFileContent(anyString(), anyString(), anyString(), anyString());
    }
}
