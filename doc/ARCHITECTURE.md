# Architecture

This document describes the high-level architecture of the AI Gitea Bot, including component responsibilities and request flows.

## System Overview

```mermaid
graph LR
    Gitea["Gitea Instance"]
    Bitbucket["Bitbucket Cloud"]
    Bot["AI Gitea Bot"]
    AI["AI Provider<br/>(Anthropic / OpenAI / Ollama / llama.cpp)"]
    DB["PostgreSQL Database"]

    Gitea -- "Webhook (PR/Comment/Review event)" --> Bot
    Bot -- "Fetch PR diff" --> Gitea
    Bot -- "Post review/comment" --> Gitea
    Bot -- "Fetch reviews & comments" --> Gitea
    Bot -- "Add reaction" --> Gitea
    Bitbucket -- "Webhook (PR/Comment event)" --> Bot
    Bot -- "Fetch PR diff" --> Bitbucket
    Bot -- "Post review/comment" --> Bitbucket
    Bot -- "Review diff / Chat" --> AI
    AI -- "Review text" --> Bot
    Bot -- "Config & Sessions" --> DB
```

The bot sits between one or more Git hosting providers (Gitea, Bitbucket Cloud) and a configurable AI provider. When a pull request is opened or updated, the provider sends a webhook to the bot. The bot fetches the diff, sends it to the configured AI provider for review, and posts the review back as a PR comment. All configuration (AI integrations, Git integrations, bots) and conversation sessions are persisted in a database.

The bot also responds to inline review comments and submitted reviews containing bot mentions by fetching the relevant review data from the Git provider API and posting context-aware replies.

## Git Provider Architecture

The bot uses a **provider-agnostic abstraction layer** for repository operations:

### RepositoryApiClient Interface

Each Git provider implements `RepositoryApiClient` to provide:
- Pull request operations (fetch diff, post reviews/comments, reactions)
- Repository operations (branches, files, trees)
- Authentication via provider-specific tokens

```
RepositoryApiClient (interface)
 ├── GiteaApiClient (Gitea REST API v1)
 │    └── Auth: Authorization: token <TOKEN>
 │    └── Base: /api/v1/repos/{owner}/{repo}/...
 └── BitbucketApiClient (Bitbucket Cloud REST API 2.0)
      └── Auth: Authorization: Bearer <TOKEN>
      └── Base: /2.0/repositories/{workspace}/{repo_slug}/...
```

### Webhook Controllers

Each provider has its own webhook controller that translates provider-specific payloads into the internal `WebhookPayload` model:

| Provider  | Controller                    | Endpoint                                  |
|-----------|-------------------------------|-------------------------------------------|
| Gitea     | `GiteaWebhookController`      | `POST /api/webhook/{secret}`              |
| Bitbucket | `BitbucketWebhookController`  | `POST /api/bitbucket-webhook/{secret}`    |

### GiteaClientFactory

Creates `RestClient` instances with the correct authentication header for each provider type:
- **Gitea**: `Authorization: token <TOKEN>`
- **Bitbucket**: `Authorization: Bearer <TOKEN>`

### BotWebhookService

Dispatches to the correct `RepositoryApiClient` implementation based on `GitIntegration.providerType`:
- `GITEA` → `GiteaApiClient`
- `BITBUCKET` → `BitbucketApiClient`

## Component Diagram

```mermaid
graph TD
    subgraph "Spring Boot Application"
        subgraph "Web Layer"
            WebhookController["GiteaWebhookController<br/><i>Per-bot webhook endpoints</i>"]
            AdminControllers["Admin Controllers<br/><i>Dashboard, Bots, Integrations</i>"]
            SetupController["SetupController<br/><i>Initial setup</i>"]
        end
        
        subgraph "Service Layer"
            BotService["BotService<br/><i>Bot CRUD</i>"]
            BotWebhookService["BotWebhookService<br/><i>Webhook processing</i>"]
            AiIntegrationService["AiIntegrationService<br/><i>AI config CRUD</i>"]
            GitIntegrationService["GitIntegrationService<br/><i>Git config CRUD</i>"]
            SessionService["SessionService<br/><i>Session lifecycle</i>"]
            EncryptionService["EncryptionService<br/><i>API key encryption</i>"]
        end

        subgraph "AI Provider Layer"
            AiClientFactory["AiClientFactory<br/><i>Client creation & caching</i>"]
            AiProviderRegistry["AiProviderRegistry<br/><i>Provider discovery</i>"]
            subgraph "Provider Metadata"
                AnthropicMeta["AnthropicProviderMetadata"]
                OpenAiMeta["OpenAiProviderMetadata"]
                OllamaMeta["OllamaProviderMetadata"]
                LlamaCppMeta["LlamaCppProviderMetadata"]
            end
            subgraph "AI Clients"
                AiInterface["AiClient<br/><i>Interface</i>"]
                AbstractClient["AbstractAiClient<br/><i>Chunking & retry</i>"]
                AnthropicImpl["AnthropicAiClient"]
                OpenAiImpl["OpenAiClient"]
                OllamaImpl["OllamaClient"]
                LlamaCppImpl["LlamaCppClient"]
            end
        end

        subgraph "Repository Layer"
            BotRepo["BotRepository"]
            AiIntegrationRepo["AiIntegrationRepository"]
            GitIntegrationRepo["GitIntegrationRepository"]
            SessionRepo["ReviewSessionRepository"]
            AdminRepo["AdminUserRepository"]
        end
    end

    subgraph "External"
        Gitea["Gitea"]
        Anthropic["Anthropic API"]
        OpenAI["OpenAI API"]
        Ollama["Ollama (local)"]
        LlamaCpp["llama.cpp (local)"]
        PromptFiles["Prompt Files<br/><i>prompts/*.md</i>"]
        DB["Database<br/><i>PostgreSQL / H2</i>"]
    end

    WebhookController --> BotService
    WebhookController --> BotWebhookService
    BotWebhookService --> AiClientFactory
    BotWebhookService --> SessionService
    AiClientFactory --> AiProviderRegistry
    AiClientFactory --> AiIntegrationService
    AiProviderRegistry --> AnthropicMeta
    AiProviderRegistry --> OpenAiMeta
    AiProviderRegistry --> OllamaMeta
    AiProviderRegistry --> LlamaCppMeta
    AnthropicMeta --> AnthropicImpl
    OpenAiMeta --> OpenAiImpl
    OllamaMeta --> OllamaImpl
    LlamaCppMeta --> LlamaCppImpl
    AiInterface -.-> AbstractClient
    AbstractClient -.-> AnthropicImpl
    AbstractClient -.-> OpenAiImpl
    AbstractClient -.-> OllamaImpl
    AbstractClient -.-> LlamaCppImpl
    AnthropicImpl --> Anthropic
    OpenAiImpl --> OpenAI
    OllamaImpl --> Ollama
    LlamaCppImpl --> LlamaCpp
    BotRepo --> DB
    SessionRepo --> DB
```

## AI Provider Architecture

The bot uses a **provider-agnostic abstraction layer** with metadata-driven configuration:

### AiProviderMetadata Interface

Each AI provider implements `AiProviderMetadata` to define:
- Provider type identifier (e.g., "anthropic", "openai")
- Default API URL
- Suggested models list
- Whether API key is required
- How to build the `RestClient`
- How to create the `AiClient` instance

```
AiProviderMetadata (interface)
 ├── AnthropicProviderMetadata
 │    └── Default URL: https://api.anthropic.com
 │    └── Models: claude-opus-4-6, claude-sonnet-4-6, claude-haiku-4-5-20251001
 ├── OpenAiProviderMetadata
 │    └── Default URL: https://api.openai.com
 │    └── Models: gpt-5.4, gpt-5.3-codex, gpt-5.1-codex-max, gpt-5-codex
 ├── OllamaProviderMetadata
 │    └── Default URL: http://localhost:11434
 │    └── Models: (user-configured)
 └── LlamaCppProviderMetadata
      └── Default URL: http://localhost:8081
      └── Models: (user-configured)
```

### AiProviderRegistry

Spring `@Service` that collects all `AiProviderMetadata` beans and provides:
- List of available provider types
- Lookup by provider type
- Maps of default API URLs and suggested models (for UI)

### AiClientFactory

Creates and caches `AiClient` instances per `AiIntegration`:
- Uses `AiProviderRegistry` to find the correct metadata
- Delegates to metadata for `RestClient` and `AiClient` creation
- Caches clients by integration ID + `updatedAt` timestamp
- Automatically rebuilds clients when configuration changes

### AiClient Hierarchy

```
AiClient (interface)
 └── AbstractAiClient (abstract class — chunking, retry, message building)
      ├── AnthropicAiClient (Anthropic Messages API)
      ├── OpenAiClient (OpenAI Chat Completions API)
      ├── OllamaClient (Ollama /api/chat)
      └── LlamaCppClient (llama.cpp /v1/chat/completions with GBNF grammar)
```

### Provider Differences

| Feature | Anthropic | OpenAI | Ollama | llama.cpp |
|---------|-----------|--------|--------|-----------|
| System prompt | Top-level `system` field | `role: "system"` message | `role: "system"` message | `role: "system"` message |
| Endpoint | `/v1/messages` | `/v1/chat/completions` | `/api/chat` | `/v1/chat/completions` |
| Auth | `x-api-key` header | `Bearer` token | None | None |
| Streaming | Not used | Not used | Disabled (`stream: false`) | Disabled (`stream: false`) |
| JSON Mode | N/A | N/A | `format: "json"` | GBNF grammar |

## Entity Model

```mermaid
erDiagram
    AdminUser {
        Long id PK
        String username UK
        String passwordHash
        Instant createdAt
    }
    
    AiIntegration {
        Long id PK
        String name UK
        String providerType
        String apiUrl
        String apiKey
        String apiVersion
        String model
        int maxTokens
        int maxDiffCharsPerChunk
        int maxDiffChunks
        int retryTruncatedChunkChars
        Instant createdAt
        Instant updatedAt
    }
    
    GitIntegration {
        Long id PK
        String name UK
        RepositoryType providerType
        String url
        String token
        Instant createdAt
        Instant updatedAt
    }
    
    Bot {
        Long id PK
        String name UK
        String username
        String prompt
        String webhookSecret UK
        boolean enabled
        boolean agentEnabled
        long webhookCallCount
        Instant lastWebhookAt
        String lastError
        Instant lastErrorAt
        Instant createdAt
        Instant updatedAt
    }
    
    ReviewSession {
        Long id PK
        String repoOwner
        String repoName
        int prNumber
        Instant createdAt
        Instant updatedAt
    }
    
    ConversationMessage {
        Long id PK
        String role
        String content
        Instant createdAt
    }
    
    Bot ||--o{ AiIntegration : "uses"
    Bot ||--o{ GitIntegration : "uses"
    ReviewSession ||--|{ ConversationMessage : "contains"
```

## Components

### GiteaWebhookController

- **Package:** `org.remus.giteabot.gitea`
- **Endpoint:** `POST /api/webhook/{webhookSecret}`
- Receives Gitea webhook payloads for pull request, issue comment, and review comment events
- Looks up Bot by webhook secret
- Routes events based on payload structure to `BotWebhookService`

### BitbucketWebhookController

- **Package:** `org.remus.giteabot.bitbucket`
- **Endpoint:** `POST /api/bitbucket-webhook/{webhookSecret}`
- Receives Bitbucket Cloud webhook events (via `X-Event-Key` header)
- Translates Bitbucket payload format to the internal `WebhookPayload` model
- Supported events: `pullrequest:created`, `pullrequest:updated`, `pullrequest:fulfilled`, `pullrequest:rejected`, `pullrequest:comment_created`
- Routes translated events to `BotWebhookService`

### BitbucketApiClient

- **Package:** `org.remus.giteabot.bitbucket`
- Implements `RepositoryApiClient` for Bitbucket Cloud REST API 2.0
- Maps workspace/repo-slug identifiers from owner/repo parameters
- All PR and repository operations use `/2.0/repositories/` endpoints
- Note: Bitbucket Cloud does not support comment reactions (gracefully ignored)

### BotWebhookService

- **Package:** `org.remus.giteabot.admin`
- Processes webhook events for a specific bot
- Gets AI client from `AiClientFactory` using bot's `AiIntegration`
- Creates Git client using bot's `GitIntegration`
- Handles:
  - PR reviews (opened, synchronized)
  - Bot commands (PR comments with mention)
  - Inline review comments
  - Review submitted events
  - Issue assignments (agent feature)

### AiClientFactory

- **Package:** `org.remus.giteabot.admin`
- Creates and caches `AiClient` instances
- Uses `AiProviderRegistry` for provider lookup
- Rebuilds clients when integration config changes

### AiProviderRegistry

- **Package:** `org.remus.giteabot.ai`
- Collects all `AiProviderMetadata` implementations via Spring DI
- Provides provider lookup and metadata access

### AiProviderMetadata Implementations

- **Packages:** `org.remus.giteabot.ai.{anthropic,openai,ollama,llamacpp}`
- Define provider-specific defaults and client creation logic
- Registered as `@Component` beans

### SessionService

- **Package:** `org.remus.giteabot.session`
- Manages the lifecycle of review sessions per PR
- Stores conversation messages for context
- Sessions identified by (repoOwner, repoName, prNumber)

### EncryptionService

- **Package:** `org.remus.giteabot.admin`
- Encrypts API keys and tokens using AES-256-GCM
- Uses `APP_ENCRYPTION_KEY` environment variable

## Request Flows

### Per-Bot Webhook Flow

```mermaid
sequenceDiagram
    participant Gitea
    participant Controller as WebhookController
    participant BotService
    participant BotWebhook as BotWebhookService
    participant Factory as AiClientFactory
    participant Registry as AiProviderRegistry
    participant Meta as ProviderMetadata
    participant AI as AiClient
    participant GiteaAPI

    Gitea->>Controller: POST /api/webhook/{secret}
    Controller->>BotService: findByWebhookSecret(secret)
    BotService-->>Controller: Bot
    Controller->>BotWebhook: handleBotWebhookEvent(bot, payload)
    BotWebhook->>Factory: getClient(bot.aiIntegration)
    Factory->>Registry: getProviderOrThrow(providerType)
    Registry-->>Factory: ProviderMetadata
    Factory->>Meta: buildRestClient(integration, apiKey)
    Meta-->>Factory: RestClient
    Factory->>Meta: createClient(restClient, integration)
    Meta-->>Factory: AiClient
    Factory-->>BotWebhook: AiClient (cached)
    BotWebhook->>GiteaAPI: getPullRequestDiff()
    GiteaAPI-->>BotWebhook: diff
    BotWebhook->>AI: reviewDiff(diff, prompt)
    AI-->>BotWebhook: review text
    BotWebhook->>GiteaAPI: postReviewComment(review)
```

### Bot Command Flow

```mermaid
sequenceDiagram
    participant User
    participant Gitea
    participant Controller as WebhookController
    participant BotWebhook as BotWebhookService
    participant Session as SessionService
    participant Factory as AiClientFactory
    participant AI as AiClient
    participant GiteaAPI

    User->>Gitea: Comment: "@ai_bot explain this"
    Gitea->>Controller: POST /api/webhook/{secret}
    Controller->>BotWebhook: handleBotCommand(bot, payload)
    BotWebhook->>GiteaAPI: addReaction(commentId, "eyes")
    BotWebhook->>Session: getOrCreateSession(owner, repo, pr)
    Session-->>BotWebhook: session (with history)
    BotWebhook->>Factory: getClient(bot.aiIntegration)
    Factory-->>BotWebhook: AiClient
    BotWebhook->>AI: chat(history, comment, prompt)
    AI-->>BotWebhook: response
    BotWebhook->>Session: addMessage("user", comment)
    BotWebhook->>Session: addMessage("assistant", response)
    BotWebhook->>GiteaAPI: postComment(response)
```

## Webhook Routing Flow

```mermaid
flowchart TD
    A["Webhook received at /api/webhook/{secret}"] --> B{Bot found?}
    B -- No --> Z["404 Not Found"]
    B -- Yes --> C{Bot enabled?}
    C -- No --> Y["200 'bot disabled'"]
    C -- Yes --> D{Is bot's own action?}
    D -- Yes --> X["200 'ignored'"]
    D -- No --> E{comment with path?}
    E -- Yes --> F["handleInlineComment()"]
    E -- No --> G{comment + issue?}
    G -- Yes --> H{Bot mentioned?}
    H -- No --> X
    H -- Yes --> I{Is PR?}
    I -- Yes --> J["handleBotCommand()"]
    I -- No --> K["handleIssueComment()"]
    G -- No --> L{Issue assigned to bot?}
    L -- Yes --> M["handleIssueAssigned()"]
    L -- No --> N{pullRequest present?}
    N -- No --> X
    N -- Yes --> O{action = reviewed?}
    O -- Yes --> P["handleReviewSubmitted()"]
    O -- No --> Q{action = closed?}
    Q -- Yes --> R["handlePrClosed()"]
    Q -- No --> S{action = opened/synchronized?}
    S -- Yes --> T["reviewPullRequest()"]
    S -- No --> X
```

## Docker Deployment

```mermaid
graph LR
    subgraph "Docker Compose"
        subgraph "App Container"
            App["app.jar<br/>(Spring Boot)"]
            Prompts["/app/prompts/<br/>Prompt templates"]
        end
        subgraph "DB Container"
            Postgres["PostgreSQL 17<br/>(Config & Sessions)"]
            PGData["pgdata volume"]
        end
    end

    Host["Host filesystem<br/>./prompts/"] -- "bind mount :ro" --> Prompts
    App -- reads --> Prompts
    App -- "JDBC" --> Postgres
    Postgres -- stores --> PGData
```

- All configuration (AI integrations, Git integrations, bots) is stored in the database
- The `prompts/` directory contains prompt templates loaded at runtime
- PostgreSQL persists configuration and review sessions
- Session data survives container restarts via the `pgdata` volume

