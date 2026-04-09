package org.remus.giteabot.bitbucket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.gitea.model.WebhookPayload;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BitbucketPayloadTranslationTest {

    private BitbucketWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new BitbucketWebhookController(
                mock(BotService.class),
                mock(BotWebhookService.class)
        );
    }

    @Test
    void translatePayload_pullrequestCreated_mapsToOpened() {
        Map<String, Object> raw = Map.of(
                "pullrequest", Map.of(
                        "id", 42,
                        "title", "My PR",
                        "description", "Some description",
                        "state", "OPEN",
                        "source", Map.of("branch", Map.of("name", "feature"), "commit", Map.of("hash", "abc123")),
                        "destination", Map.of("branch", Map.of("name", "main"), "commit", Map.of("hash", "def456"))
                ),
                "repository", Map.of(
                        "name", "myrepo",
                        "full_name", "workspace/myrepo",
                        "owner", Map.of("nickname", "workspace")
                ),
                "actor", Map.of("nickname", "developer")
        );

        WebhookPayload payload = controller.translatePayload("pullrequest:created", raw);

        assertNotNull(payload);
        assertEquals("opened", payload.getAction());
        assertEquals(42L, payload.getNumber());

        assertNotNull(payload.getPullRequest());
        assertEquals(42L, payload.getPullRequest().getId());
        assertEquals(42L, payload.getPullRequest().getNumber());
        assertEquals("My PR", payload.getPullRequest().getTitle());
        assertEquals("Some description", payload.getPullRequest().getBody());

        assertNotNull(payload.getPullRequest().getHead());
        assertEquals("feature", payload.getPullRequest().getHead().getRef());
        assertEquals("abc123", payload.getPullRequest().getHead().getSha());

        assertNotNull(payload.getPullRequest().getBase());
        assertEquals("main", payload.getPullRequest().getBase().getRef());
        assertEquals("def456", payload.getPullRequest().getBase().getSha());

        assertNotNull(payload.getRepository());
        assertEquals("myrepo", payload.getRepository().getName());
        assertEquals("workspace/myrepo", payload.getRepository().getFullName());
        assertEquals("workspace", payload.getRepository().getOwner().getLogin());

        assertNotNull(payload.getSender());
        assertEquals("developer", payload.getSender().getLogin());
    }

    @Test
    void translatePayload_pullrequestUpdated_mapsToSynchronized() {
        Map<String, Object> raw = Map.of(
                "pullrequest", Map.of("id", 5, "title", "PR"),
                "repository", Map.of("name", "repo", "full_name", "ws/repo"),
                "actor", Map.of("nickname", "user")
        );

        WebhookPayload payload = controller.translatePayload("pullrequest:updated", raw);

        assertNotNull(payload);
        assertEquals("synchronized", payload.getAction());
    }

    @Test
    void translatePayload_pullrequestFulfilled_mapsToClosed() {
        Map<String, Object> raw = Map.of(
                "pullrequest", Map.of("id", 5, "title", "PR"),
                "repository", Map.of("name", "repo", "full_name", "ws/repo"),
                "actor", Map.of("nickname", "user")
        );

        WebhookPayload payload = controller.translatePayload("pullrequest:fulfilled", raw);

        assertNotNull(payload);
        assertEquals("closed", payload.getAction());
    }

    @Test
    void translatePayload_pullrequestRejected_mapsToClosed() {
        Map<String, Object> raw = Map.of(
                "pullrequest", Map.of("id", 5, "title", "PR"),
                "repository", Map.of("name", "repo", "full_name", "ws/repo"),
                "actor", Map.of("nickname", "user")
        );

        WebhookPayload payload = controller.translatePayload("pullrequest:rejected", raw);

        assertNotNull(payload);
        assertEquals("closed", payload.getAction());
    }

    @Test
    void translatePayload_commentCreated_mapsCorrectly() {
        Map<String, Object> raw = Map.of(
                "comment", Map.of(
                        "id", 99,
                        "content", Map.of("raw", "@ai_bot review this"),
                        "user", Map.of("nickname", "developer")
                ),
                "pullrequest", Map.of("id", 1, "title", "Test PR"),
                "repository", Map.of("name", "repo", "full_name", "ws/repo", "owner", Map.of("nickname", "ws")),
                "actor", Map.of("nickname", "developer")
        );

        WebhookPayload payload = controller.translatePayload("pullrequest:comment_created", raw);

        assertNotNull(payload);
        assertEquals("created", payload.getAction());

        assertNotNull(payload.getComment());
        assertEquals(99L, payload.getComment().getId());
        assertEquals("@ai_bot review this", payload.getComment().getBody());
        assertEquals("developer", payload.getComment().getUser().getLogin());
        assertNull(payload.getComment().getPath()); // non-inline

        // Issue should be set for routing
        assertNotNull(payload.getIssue());
        assertNotNull(payload.getIssue().getPullRequest());
    }

    @Test
    void translatePayload_inlineCommentCreated_includesPathAndLine() {
        Map<String, Object> raw = Map.of(
                "comment", Map.of(
                        "id", 100,
                        "content", Map.of("raw", "@ai_bot explain this"),
                        "user", Map.of("nickname", "dev"),
                        "inline", Map.of("path", "src/main/Foo.java", "to", 42)
                ),
                "pullrequest", Map.of("id", 3, "title", "PR"),
                "repository", Map.of("name", "repo", "full_name", "ws/repo"),
                "actor", Map.of("nickname", "dev")
        );

        WebhookPayload payload = controller.translatePayload("pullrequest:comment_created", raw);

        assertNotNull(payload);
        assertNotNull(payload.getComment());
        assertEquals("src/main/Foo.java", payload.getComment().getPath());
        assertEquals(42, payload.getComment().getLine());
    }

    @Test
    void translatePayload_unsupportedEvent_returnsNull() {
        Map<String, Object> raw = Map.of("pullrequest", Map.of("id", 1));

        assertNull(controller.translatePayload("pullrequest:approved", raw));
        assertNull(controller.translatePayload("repo:push", raw));
        assertNull(controller.translatePayload(null, raw));
    }

    @Test
    void translatePayload_userFallsBackToDisplayName() {
        Map<String, Object> raw = Map.of(
                "pullrequest", Map.of("id", 1, "title", "PR"),
                "repository", Map.of("name", "repo", "full_name", "ws/repo"),
                "actor", Map.of("display_name", "John Doe")
        );

        WebhookPayload payload = controller.translatePayload("pullrequest:created", raw);

        assertNotNull(payload);
        assertEquals("John Doe", payload.getSender().getLogin());
    }
}
