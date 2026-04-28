package org.remus.giteabot.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookHandlerTest {

    @Mock
    private BotWebhookService botWebhookService;

    private GitHubWebhookHandler handler;
    private Bot bot;

    @BeforeEach
    void setUp() {
        handler = new GitHubWebhookHandler(botWebhookService);
        bot = new Bot();
        bot.setName("test-bot");
        bot.setUsername("ai_bot");

        lenient().when(botWebhookService.isBotUser(eq(bot), any(WebhookPayload.class))).thenReturn(false);
    }

    @Test
    void issueAssignedPayload_withCustomIssueRef_propagatesCompatibilityRefToWebhookPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "assigned");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssigneeAndRef(42L, "ai_bot", "release/1.2"));

        ResponseEntity<String> response = handler.handleWebhook(bot, "issues", payload);

        assertEquals("agent triggered", response.getBody());
        ArgumentCaptor<WebhookPayload> captor = ArgumentCaptor.forClass(WebhookPayload.class);
        verify(botWebhookService).handleIssueAssigned(eq(bot), captor.capture());
        assertEquals("release/1.2", captor.getValue().getIssue().getRef());
    }

    @Test
    void translatePayload_issueWithoutRef_keepsRefNull() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "assigned");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssigneeAndRef(42L, "ai_bot", null));

        WebhookPayload translated = handler.translatePayload("issues", payload);

        assertEquals("assigned", translated.getAction());
        assertNull(translated.getIssue().getRef());
    }

    @Test
    void issueAssignedPayload_toDifferentAssignee_isIgnored() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "assigned");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("issue", issueMapWithAssigneeAndRef(42L, "someone_else", null));

        ResponseEntity<String> response = handler.handleWebhook(bot, "issues", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).handleIssueAssigned(any(), any());
    }

    @Test
    void pullRequestReviewRequested_forBotReviewer_triggersReview() {
        Map<String, Object> payload = pullRequestPayload("review_requested", "Needs review", "ai_bot");

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        ArgumentCaptor<WebhookPayload> captor = ArgumentCaptor.forClass(WebhookPayload.class);
        verify(botWebhookService).reviewPullRequest(eq(bot), captor.capture());
        assertEquals("review_requested", captor.getValue().getAction());
        assertEquals("ai_bot", captor.getValue().getRequestedReviewer().getLogin());
    }

    @Test
    void pullRequestReviewRequested_forDifferentReviewer_isIgnored() {
        Map<String, Object> payload = pullRequestPayload("review_requested", "Needs review", "other_bot");

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void pullRequestBodyWithGenToken_triggersMetadataGeneration() {
        Map<String, Object> payload = pullRequestPayload("opened", "Please /gen a useful title", null);

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService).generatePrTitleAndDescription(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void pullRequestBodyWithGenSubstring_doesNotTriggerMetadataGeneration() {
        Map<String, Object> payload = pullRequestPayload("opened", "Update /generate docs", null);

        ResponseEntity<String> response = handler.handleWebhook(bot, "pull_request", payload);

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).generatePrTitleAndDescription(any(), any());
    }

    @Test
    void prCommentWithGenToken_withoutBotMention_triggersMetadataGeneration() {
        Map<String, Object> payload = issueCommentPayload("Please /gen this PR");

        ResponseEntity<String> response = handler.handleWebhook(bot, "issue_comment", payload);

        assertEquals("generation triggered", response.getBody());
        verify(botWebhookService).generatePrTitleAndDescription(eq(bot), any(WebhookPayload.class));
        verify(botWebhookService, never()).handleBotCommand(any(), any());
    }

    @Test
    void prCommentWithGenSubstring_withoutBotMention_isIgnored() {
        Map<String, Object> payload = issueCommentPayload("Please inspect /genesis handling");

        ResponseEntity<String> response = handler.handleWebhook(bot, "issue_comment", payload);

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).generatePrTitleAndDescription(any(), any());
        verify(botWebhookService, never()).handleBotCommand(any(), any());
    }

    private Map<String, Object> ownerMap(String login) {
        Map<String, Object> m = new HashMap<>();
        m.put("login", login);
        return m;
    }

    private Map<String, Object> repositoryMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("id", 1);
        m.put("name", "my-repo");
        m.put("full_name", "Test/my-repo");
        m.put("owner", ownerMap("Test"));
        return m;
    }

    private Map<String, Object> issueMapWithAssigneeAndRef(long number, String assigneeLogin, String ref) {
        Map<String, Object> m = new HashMap<>();
        m.put("number", number);
        m.put("title", "Some issue");
        m.put("body", "");
        m.put("assignee", ownerMap(assigneeLogin));
        m.put("ref", ref);
        return m;
    }

    private Map<String, Object> pullRequestPayload(String action, String body, String requestedReviewer) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", action);
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("pull_request", pullRequestMap(7L, body));
        if (requestedReviewer != null) {
            payload.put("requested_reviewer", ownerMap(requestedReviewer));
        }
        return payload;
    }

    private Map<String, Object> pullRequestMap(long number, String body) {
        Map<String, Object> pr = new HashMap<>();
        pr.put("id", 99L);
        pr.put("number", number);
        pr.put("title", "Original title");
        pr.put("body", body);
        pr.put("state", "open");
        pr.put("head", Map.of("ref", "feature", "sha", "abc"));
        pr.put("base", Map.of("ref", "main", "sha", "def"));
        return pr;
    }

    private Map<String, Object> issueCommentPayload(String body) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "created");
        payload.put("sender", ownerMap("tom"));
        payload.put("repository", repositoryMap());
        payload.put("comment", Map.of("id", 42L, "body", body, "user", ownerMap("tom")));
        payload.put("issue", Map.of(
                "number", 7L,
                "title", "Original title",
                "body", "Original body",
                "pull_request", Map.of("url", "https://api.github.com/repos/Test/my-repo/pulls/7")
        ));
        return payload;
    }
}
