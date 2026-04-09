package org.remus.giteabot.bitbucket;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BitbucketWebhookController.class)
@ActiveProfiles("test")
class BitbucketWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BotService botService;

    @MockitoBean
    private BotWebhookService botWebhookService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void handleBitbucketWebhook_prCreated_triggersReview() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        String payload = """
                {
                    "pullrequest": {
                        "id": 1,
                        "title": "Test PR",
                        "description": "Some changes",
                        "state": "OPEN",
                        "source": {
                            "branch": {"name": "feature"},
                            "commit": {"hash": "abc123"}
                        },
                        "destination": {
                            "branch": {"name": "main"},
                            "commit": {"hash": "def456"}
                        }
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testworkspace/testrepo",
                        "owner": {"nickname": "testworkspace"}
                    },
                    "actor": {
                        "nickname": "testuser",
                        "display_name": "Test User"
                    }
                }
                """;

        mockMvc.perform(post("/api/bitbucket-webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:created")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(botService).incrementWebhookCallCount(bot);
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBitbucketWebhook_prUpdated_triggersReview() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        String payload = """
                {
                    "pullrequest": {
                        "id": 1,
                        "title": "Test PR",
                        "source": {"branch": {"name": "feature"}},
                        "destination": {"branch": {"name": "main"}}
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testworkspace/testrepo",
                        "owner": {"nickname": "testworkspace"}
                    },
                    "actor": {"nickname": "testuser"}
                }
                """;

        mockMvc.perform(post("/api/bitbucket-webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:updated")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBitbucketWebhook_prFulfilled_closesSession() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        String payload = """
                {
                    "pullrequest": {
                        "id": 1,
                        "title": "Test PR",
                        "state": "MERGED",
                        "source": {"branch": {"name": "feature"}},
                        "destination": {"branch": {"name": "main"}}
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testworkspace/testrepo",
                        "owner": {"nickname": "testworkspace"}
                    },
                    "actor": {"nickname": "testuser"}
                }
                """;

        mockMvc.perform(post("/api/bitbucket-webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:fulfilled")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("session closed"));

        verify(botWebhookService).handlePrClosed(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBitbucketWebhook_prRejected_closesSession() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        String payload = """
                {
                    "pullrequest": {
                        "id": 1,
                        "title": "Test PR",
                        "state": "DECLINED",
                        "source": {"branch": {"name": "feature"}},
                        "destination": {"branch": {"name": "main"}}
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testworkspace/testrepo",
                        "owner": {"nickname": "testworkspace"}
                    },
                    "actor": {"nickname": "testuser"}
                }
                """;

        mockMvc.perform(post("/api/bitbucket-webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:rejected")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("session closed"));

        verify(botWebhookService).handlePrClosed(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBitbucketWebhook_commentWithBotMention_triggersCommand() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(botWebhookService.getBotAlias(bot)).thenReturn("@ai_bot");

        String payload = """
                {
                    "comment": {
                        "id": 42,
                        "content": {"raw": "@ai_bot please explain this"},
                        "user": {"nickname": "testuser"}
                    },
                    "pullrequest": {
                        "id": 1,
                        "title": "Test PR",
                        "source": {"branch": {"name": "feature"}},
                        "destination": {"branch": {"name": "main"}}
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testworkspace/testrepo",
                        "owner": {"nickname": "testworkspace"}
                    },
                    "actor": {"nickname": "testuser"}
                }
                """;

        mockMvc.perform(post("/api/bitbucket-webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:comment_created")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("command received"));

        verify(botWebhookService).handleBotCommand(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBitbucketWebhook_commentWithoutBotMention_ignored() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(botWebhookService.getBotAlias(bot)).thenReturn("@ai_bot");

        String payload = """
                {
                    "comment": {
                        "id": 42,
                        "content": {"raw": "just a regular comment"},
                        "user": {"nickname": "testuser"}
                    },
                    "pullrequest": {
                        "id": 1,
                        "title": "Test PR",
                        "source": {"branch": {"name": "feature"}},
                        "destination": {"branch": {"name": "main"}}
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testworkspace/testrepo",
                        "owner": {"nickname": "testworkspace"}
                    },
                    "actor": {"nickname": "testuser"}
                }
                """;

        mockMvc.perform(post("/api/bitbucket-webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:comment_created")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(botWebhookService, never()).handleBotCommand(any(), any());
    }

    @Test
    void handleBitbucketWebhook_inlineCommentWithBotMention_triggersInlineHandler() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(botWebhookService.getBotAlias(bot)).thenReturn("@ai_bot");

        String payload = """
                {
                    "comment": {
                        "id": 55,
                        "content": {"raw": "@ai_bot explain this code"},
                        "user": {"nickname": "testuser"},
                        "inline": {
                            "path": "src/main/java/Foo.java",
                            "to": 15
                        }
                    },
                    "pullrequest": {
                        "id": 3,
                        "title": "Refactor PR",
                        "source": {"branch": {"name": "feature"}},
                        "destination": {"branch": {"name": "main"}}
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testworkspace/testrepo",
                        "owner": {"nickname": "testworkspace"}
                    },
                    "actor": {"nickname": "testuser"}
                }
                """;

        mockMvc.perform(post("/api/bitbucket-webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:comment_created")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("inline comment response triggered"));

        verify(botWebhookService).handleInlineComment(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBitbucketWebhook_botDisabled_returnsBotDisabled() throws Exception {
        Bot bot = createTestBot();
        bot.setEnabled(false);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        String payload = """
                {
                    "pullrequest": {"id": 1, "title": "Test PR"},
                    "repository": {"name": "testrepo", "full_name": "testworkspace/testrepo", "owner": {"nickname": "testworkspace"}},
                    "actor": {"nickname": "testuser"}
                }
                """;

        mockMvc.perform(post("/api/bitbucket-webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:created")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("bot disabled"));

        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void handleBitbucketWebhook_botNotFound_returns404() throws Exception {
        when(botService.findByWebhookSecret("unknown-secret")).thenReturn(Optional.empty());

        String payload = """
                {
                    "pullrequest": {"id": 1, "title": "Test PR"},
                    "repository": {"name": "testrepo", "full_name": "testworkspace/testrepo"}
                }
                """;

        mockMvc.perform(post("/api/bitbucket-webhook/unknown-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:created")
                        .content(payload))
                .andExpect(status().isNotFound());

        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void handleBitbucketWebhook_botSender_ignored() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(botWebhookService.isBotUser(eq(bot), any())).thenReturn(true);

        String payload = """
                {
                    "pullrequest": {
                        "id": 1,
                        "title": "Test PR",
                        "source": {"branch": {"name": "feature"}},
                        "destination": {"branch": {"name": "main"}}
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testworkspace/testrepo",
                        "owner": {"nickname": "testworkspace"}
                    },
                    "actor": {"nickname": "ai_bot"}
                }
                """;

        mockMvc.perform(post("/api/bitbucket-webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:created")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void handleBitbucketWebhook_unsupportedEvent_ignored() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        String payload = """
                {
                    "pullrequest": {"id": 1, "title": "Test PR"},
                    "repository": {"name": "testrepo", "full_name": "testworkspace/testrepo"}
                }
                """;

        mockMvc.perform(post("/api/bitbucket-webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:approved")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    private Bot createTestBot() {
        Bot bot = new Bot();
        bot.setId(1L);
        bot.setName("Test Bot");
        bot.setUsername("ai_bot");
        bot.setWebhookSecret("test-secret");
        bot.setEnabled(true);

        AiIntegration ai = new AiIntegration();
        ai.setId(1L);
        ai.setName("Test AI");
        ai.setProviderType("anthropic");
        ai.setApiUrl("http://localhost:8081");
        ai.setModel("claude-sonnet-4-20250514");
        ai.setMaxTokens(4096);
        ai.setMaxDiffCharsPerChunk(120000);
        ai.setMaxDiffChunks(8);
        ai.setRetryTruncatedChunkChars(60000);
        ai.setCreatedAt(Instant.now());
        ai.setUpdatedAt(Instant.now());
        bot.setAiIntegration(ai);

        GitIntegration git = new GitIntegration();
        git.setId(1L);
        git.setName("Test Bitbucket");
        git.setProviderType(RepositoryType.BITBUCKET);
        git.setUrl("https://api.bitbucket.org");
        git.setToken("test-token");
        git.setCreatedAt(Instant.now());
        git.setUpdatedAt(Instant.now());
        bot.setGitIntegration(git);

        return bot;
    }
}
