package org.remus.giteabot.bitbucket;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives Bitbucket Cloud webhook events and translates them into the
 * provider-agnostic {@link WebhookPayload} consumed by {@link BotWebhookService}.
 * <p>
 * Bitbucket Cloud sends an {@code X-Event-Key} header indicating the event type
 * (e.g. {@code pullrequest:created}, {@code pullrequest:comment_created}).
 * <p>
 * Each bot has its own webhook URL:
 * {@code POST /api/bitbucket-webhook/{webhookSecret}}.
 */
@Slf4j
@RestController
@RequestMapping("/api/bitbucket-webhook")
public class BitbucketWebhookController {

    private final BotService botService;
    private final BotWebhookService botWebhookService;

    public BitbucketWebhookController(BotService botService,
                                      BotWebhookService botWebhookService) {
        this.botService = botService;
        this.botWebhookService = botWebhookService;
    }

    @PostMapping("/{webhookSecret}")
    public ResponseEntity<String> handleBitbucketWebhook(
            @PathVariable String webhookSecret,
            @RequestHeader(value = "X-Event-Key", required = false) String eventKey,
            @RequestBody Map<String, Object> rawPayload) {

        return botService.findByWebhookSecret(webhookSecret)
                .map(bot -> {
                    if (!bot.isEnabled()) {
                        log.debug("Bot '{}' is disabled, ignoring webhook", bot.getName());
                        return ResponseEntity.ok("bot disabled");
                    }
                    botService.incrementWebhookCallCount(bot);
                    log.info("Bitbucket webhook received for bot '{}' (event={})", bot.getName(), eventKey);

                    WebhookPayload payload = translatePayload(eventKey, rawPayload);
                    if (payload == null) {
                        log.debug("Unsupported Bitbucket event: {}", eventKey);
                        return ResponseEntity.ok("ignored");
                    }
                    return handleBotWebhookEvent(bot, payload);
                })
                .orElseGet(() -> {
                    log.warn("No bot found for Bitbucket webhook secret: {}...",
                            webhookSecret.substring(0, Math.min(8, webhookSecret.length())));
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Routes the translated payload to the appropriate handler on {@link BotWebhookService}.
     * Mirrors the logic in {@link org.remus.giteabot.gitea.GiteaWebhookController}.
     */
    private ResponseEntity<String> handleBotWebhookEvent(Bot bot, WebhookPayload payload) {
        if (botWebhookService.isBotUser(bot, payload)) {
            log.debug("Ignoring Bitbucket webhook event from bot's own user '{}'", bot.getUsername());
            return ResponseEntity.ok("ignored");
        }

        String botAlias = botWebhookService.getBotAlias(bot);

        // Inline review comment
        if (payload.getComment() != null && payload.getComment().getPath() != null
                && !payload.getComment().getPath().isBlank()) {
            if ("created".equals(payload.getAction())
                    && payload.getComment().getBody() != null
                    && payload.getComment().getBody().contains(botAlias)) {
                botWebhookService.handleInlineComment(bot, payload);
                return ResponseEntity.ok("inline comment response triggered");
            }
            return ResponseEntity.ok("ignored");
        }

        // Issue/PR comments with bot mention
        if (payload.getComment() != null && payload.getIssue() != null) {
            if (!"created".equals(payload.getAction())) {
                return ResponseEntity.ok("ignored");
            }
            String body = payload.getComment().getBody();
            if (body == null || !body.contains(botAlias)) {
                return ResponseEntity.ok("ignored");
            }
            if (payload.getIssue().getPullRequest() != null) {
                botWebhookService.handleBotCommand(bot, payload);
                return ResponseEntity.ok("command received");
            }
            botWebhookService.handleIssueComment(bot, payload);
            return ResponseEntity.ok("issue comment received");
        }

        // PR events
        if (payload.getPullRequest() == null) {
            return ResponseEntity.ok("ignored");
        }

        String action = payload.getAction();

        if ("closed".equals(action)) {
            botWebhookService.handlePrClosed(bot, payload);
            return ResponseEntity.ok("session closed");
        }

        if ("opened".equals(action) || "synchronized".equals(action)) {
            botWebhookService.reviewPullRequest(bot, payload);
            return ResponseEntity.ok("review triggered");
        }

        return ResponseEntity.ok("ignored");
    }

    // ---- Payload translation ----

    /**
     * Translates a raw Bitbucket Cloud webhook payload into the provider-agnostic
     * {@link WebhookPayload} model. Returns {@code null} for unsupported events.
     */
    @SuppressWarnings("unchecked")
    WebhookPayload translatePayload(String eventKey, Map<String, Object> raw) {
        if (eventKey == null) {
            return null;
        }

        return switch (eventKey) {
            case "pullrequest:created" -> translatePullRequestEvent(raw, "opened");
            case "pullrequest:updated" -> translatePullRequestEvent(raw, "synchronized");
            case "pullrequest:fulfilled", "pullrequest:rejected" -> translatePullRequestEvent(raw, "closed");
            case "pullrequest:comment_created" -> translatePullRequestComment(raw);
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestEvent(Map<String, Object> raw, String action) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction(action);

        Map<String, Object> prData = (Map<String, Object>) raw.get("pullrequest");
        if (prData != null) {
            payload.setPullRequest(translatePullRequest(prData));
            payload.setNumber(getAsLong(prData, "id"));
        }

        Map<String, Object> repoData = (Map<String, Object>) raw.get("repository");
        if (repoData != null) {
            payload.setRepository(translateRepository(repoData));
        }

        Map<String, Object> actorData = (Map<String, Object>) raw.get("actor");
        if (actorData != null) {
            payload.setSender(translateUser(actorData));
        }

        return payload;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestComment(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("created");

        Map<String, Object> commentData = (Map<String, Object>) raw.get("comment");
        if (commentData != null) {
            WebhookPayload.Comment comment = new WebhookPayload.Comment();
            comment.setId(getAsLong(commentData, "id"));

            Map<String, Object> content = (Map<String, Object>) commentData.get("content");
            if (content != null) {
                comment.setBody((String) content.get("raw"));
            }

            Map<String, Object> user = (Map<String, Object>) commentData.get("user");
            if (user != null) {
                WebhookPayload.Owner owner = new WebhookPayload.Owner();
                owner.setLogin(resolveUsername(user));
                comment.setUser(owner);
            }

            Map<String, Object> inline = (Map<String, Object>) commentData.get("inline");
            if (inline != null) {
                comment.setPath((String) inline.get("path"));
                Object toVal = inline.get("to");
                if (toVal instanceof Number num) {
                    comment.setLine(num.intValue());
                }
            }

            payload.setComment(comment);
        }

        Map<String, Object> prData = (Map<String, Object>) raw.get("pullrequest");
        if (prData != null) {
            payload.setPullRequest(translatePullRequest(prData));
            payload.setNumber(getAsLong(prData, "id"));

            // Also set an "issue" with pull_request reference so the routing logic
            // recognises this as a PR comment (same as Gitea payloads)
            WebhookPayload.Issue issue = new WebhookPayload.Issue();
            issue.setNumber(getAsLong(prData, "id"));
            String title = (String) prData.get("title");
            issue.setTitle(title);
            WebhookPayload.IssuePullRequest issuePr = new WebhookPayload.IssuePullRequest();
            issue.setPullRequest(issuePr);
            payload.setIssue(issue);
        }

        Map<String, Object> repoData = (Map<String, Object>) raw.get("repository");
        if (repoData != null) {
            payload.setRepository(translateRepository(repoData));
        }

        Map<String, Object> actorData = (Map<String, Object>) raw.get("actor");
        if (actorData != null) {
            payload.setSender(translateUser(actorData));
        }

        return payload;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.PullRequest translatePullRequest(Map<String, Object> prData) {
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setId(getAsLong(prData, "id"));
        pr.setNumber(getAsLong(prData, "id"));
        pr.setTitle((String) prData.get("title"));
        pr.setBody((String) prData.get("description"));
        pr.setState((String) prData.get("state"));

        Map<String, Object> source = (Map<String, Object>) prData.get("source");
        if (source != null) {
            pr.setHead(translateBranch(source));
        }

        Map<String, Object> destination = (Map<String, Object>) prData.get("destination");
        if (destination != null) {
            pr.setBase(translateBranch(destination));
        }

        return pr;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Head translateBranch(Map<String, Object> branchData) {
        WebhookPayload.Head head = new WebhookPayload.Head();
        Map<String, Object> branch = (Map<String, Object>) branchData.get("branch");
        if (branch != null) {
            head.setRef((String) branch.get("name"));
        }
        Map<String, Object> commit = (Map<String, Object>) branchData.get("commit");
        if (commit != null) {
            head.setSha((String) commit.get("hash"));
        }
        return head;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Repository translateRepository(Map<String, Object> repoData) {
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName((String) repoData.get("name"));
        repository.setFullName((String) repoData.get("full_name"));

        Map<String, Object> ownerData = (Map<String, Object>) repoData.get("owner");
        if (ownerData != null) {
            WebhookPayload.Owner owner = new WebhookPayload.Owner();
            owner.setLogin(resolveUsername(ownerData));
            repository.setOwner(owner);
        }

        return repository;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Owner translateUser(Map<String, Object> userData) {
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin(resolveUsername(userData));
        return owner;
    }

    /**
     * Resolves a username from a Bitbucket user object.
     * Bitbucket uses {@code nickname} (or {@code display_name}) instead of {@code login}.
     */
    private String resolveUsername(Map<String, Object> userData) {
        String nickname = (String) userData.get("nickname");
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        return (String) userData.get("display_name");
    }

    private Long getAsLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number num) {
            return num.longValue();
        }
        return null;
    }
}
