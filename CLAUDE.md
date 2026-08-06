# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

Requires JDK 17 and the Gradle wrapper.

```shell
# Desktop JVM (primary development target)
./gradlew :composeApp:run

# Android debug APK
./gradlew :composeApp:assembleDebug

# Fast compilation checks (primary verification during migration)
./gradlew :composeApp:compileKotlinJvm
./gradlew :composeApp:compileDebugKotlinAndroid
./gradlew :composeApp:compileKotlinIosSimulatorArm64

# Module-focused check while editing (e.g.)
./gradlew :feature-model:compileKotlinJvm

# Static analysis (applied to all subprojects)
./gradlew detekt

# Run available tests (scaffolding only, not a delivery gate)
./gradlew test

# FastAPI backend (separate repo at C:\Users\wang2\fat-ai-server)
python main.py
```

The project uses Gradle configuration cache — avoid changing build scripts unnecessarily during feature work.

Use `./gradlew` on macOS/Linux; `.\gradlew.bat` on Windows.

## Architecture Overview

FatAI is a Kotlin Multiplatform (KMP) AI chat workspace targeting Android, Desktop JVM, and iOS (`iosArm64`, `iosSimulatorArm64`). UI is Compose Multiplatform with Material 3. The namespace is `ai.fatai`; the Android application ID and iOS bundle identifier are `ai.fatai.app`.

### Module dependency graph

```
composeApp (Compose UI, Decompose navigation, platform entry points)
    ├── shared (centralized Koin DI wiring; temporary bridge, don't add new logic here)
    │       ├── feature-user → feature-chat, feature-model, feature-prompt,
    │       │   feature-memory, feature-files, feature-workspace, feature-settings, feature-tools
    │       ├── core (shared primitives: ChatItemType, MessageContentType, ProviderType,
    │       │          SyncMutationSink, CurrentLanguage)
    │       └── database (SQLDelight schema v11, 10 migrations, platform drivers)
    │
feature-knowledge / feature-agent  ← KMP scaffolds only (build.gradle.kts, no src/)
```

**feature-knowledge** and **feature-agent** have no Kotlin source files — only `build.gradle.kts` declaring intended dependencies. Do not claim them as implemented features.

### Dependency injection (Koin)

There are only two Koin modules in the entire project:

- **`sharedModule`** (`shared/src/commonMain/kotlin/ai/fatai/di/sharedModule.kt`) — registers every service from feature modules as `single {}` (~20 definitions). Feature modules do NOT define their own Koin modules; they expose plain Kotlin classes/repositories/interfaces, and `sharedModule` wires them by calling constructors with `get()`.
- **`appModule`** (`composeApp/src/commonMain/kotlin/ai/fatai/di/appModule.kt`) — registers `AIChatViewModel`.

Bootstrap: `initKoin()` in composeApp calls `startKoin { modules(sharedModule, appModule, platformModule()) }`. Each platform contributes a `platformModule()` via expect/actual (registers `DatabaseDriverFactory` — Android takes `Context`, JVM/iOS take no args).

To add a new feature module: add `implementation(project(":feature-x"))` to `shared/build.gradle.kts` and register its singletons in `sharedModule`.

### Navigation (Decompose)

Single `RootComponent` with a `StackNavigation<Configuration>`: `Configuration.Chat` ↔ `Configuration.Settings` (simple push/pop). Screens are plain composable classes (`AIChatScreen`, `AISettingsScreen`), not Decompose components. A single `App()` composable is the root across all platforms.

### Critical: context assembly is server-side

Despite what the README's "Context pipeline" section describes, the `ContextEngine`, ordered `PromptProvider` extension points, and baseline policy **do not exist in this Kotlin codebase**. `feature-prompt/` contains only `PromptTemplateRepository` (CRUD + sync of user-defined prompt templates). The README's documented pipeline order (baseline policy → templates → workspace instruction → memory → file metadata → history) is assembled by the external FastAPI server at `POST /v1/chat/stream`.

The client sends a `ChatContext` with `workspaceId`, `conversationId`, `responseLanguageTag`, `toolResults`, and `includeContextualReferences` — the server uses these to look up templates, workspace instructions, memories, and assemble the full prompt. The `PromptTemplateRepository.enabledFor()` method has **no callers** in the client.

### Chat flow (client-owned IDs, server persistence)

1. `AIChatViewModel.sendMessage` creates the conversation if needed, inserts the user message locally with `sync = false`, kicks off async memory extraction.
2. `streamChat` builds history from local `ChatItem`s, executes `docling_document_read` locally for attachments, constructs a `ChatContext`, then calls `modelGateway.stream(...)`.
3. Chunks are **throttled to 16ms per render frame** (`awaitNextStreamFrame`) to prevent StateFlow from collapsing intermediate text.
4. The **server persists turns during streaming** under client-owned message IDs. On stream completion, the client writes the local cache via `insertMessage(..., sync = false, id = assistantMsg.id)` — chat messages are NOT enqueued through the sync outbox.
5. `stopGeneration` cancels the job, persists the partial answer locally (server saves it on disconnect under the same ID). `continueGeneration` appends "Please continue from where you left off." and streams again.
6. On stream failure, the catch block enqueues the user question as a fallback sync operation.

### Two streaming paths (both yield `Flow<ChatStreamChunk>`)

- **`FatAiServerModelGateway`** (active, DI-selected): SSE from `POST /v1/chat/stream` with Bearer token. Named events: `message` (content/reasoning_content), `tool_call` (server already executed the tool, surfaces structured `sources`), `done`. API keys are uploaded to the server and never sent with chat requests — the client sends `modelConfigurationId` instead.
- **`OpenAICompatibleProvider`** (fallback): Direct SSE from `POST {base}/chat/completions`. Manual SSE line parsing, accumulates tool-call deltas per `index`, emits `ProviderToolCall` on `finish_reason == "tool_calls"` or `[DONE]`. Handles both `content`/`text` and `reasoning_content`/`reasoning` delta fields (OpenAI + DeepSeek).

The `ChatProvider` interface in `feature-model` is the extension point for new providers — implement `chat(messages, config, tools): Flow<ChatStreamChunk>`.

### Server sync (bidirectional, outbox pattern)

`FatAiServerSync` in `feature-model` runs two background loops:

- **drainLoop**: FIFO pushes outbox ops to `POST /v1/sync/operations`, mutex-serialized, exponential backoff (1s–60s). Permanent 4xx (except 401/408/409/429) are marked failed.
- **pullLoop**: Every 5s, cursor-based `GET /v1/sync/changes`. Applies remote changes directly to local tables (preserving local markdown AST and source chips). Emits `remoteChangesApplied` so the UI reloads.

`SyncOutboxStore` provides a durable SQLDelight queue with per-entity sequence numbers, coalescing (only newest unsent state per entity survives), and retry/fail/succeed state transitions. `SyncMutationSink` (in `core`) is the feature-neutral interface all repositories write through.

### Database

Single SQLDelight schema file `Watson.sq` at `database/src/commonMain/sqldelight/ai/fatai/database/sqldelight/Watson.sq` (v11). Migrations in `migrations/2.sqm` through `10.sqm` — no `1.sqm`, the base `.sq` is v1. Every table is user-scoped via `userId`. Three `ColumnAdapter`s encode/decode `ChatItemType`, `MessageContentType`, and `ProviderType` as their `name` strings.

The JVM driver (`database/src/jvmMain`) has an elaborate legacy-migration path: copies `~/.ai-assistant/app.db` on first launch, infers schema version from probed columns when `PRAGMA user_version` is 0, then runs `Schema.migrate`. Be careful when adding migrations — the inference logic may skip them for DBs that already have `SyncOutbox`.

### Markdown rendering

`feature-chat` contains a hand-rolled `MarkdownParser` and `MarkdownDocument` AST (NOT a library AST). Streaming-aware: unclosed `**`/`*`/`~~` markers render the remaining run in its eventual style so mid-stream text doesn't reflow. Persisted via `MarkdownDocumentCodec` (length-prefixed binary codec, `FATAI_MD_1` header).

### Tool system (feature-tools)

Platform-neutral contracts: `Tool` (definition + `isModelCallable` + `execute`), `ToolRegistry` (validates args, enforces `ToolExecutionPolicy` with 24k char output cap). `ToolProviderAdapter` translates neutral definitions to provider wire schemas (`OpenAICompatibleToolAdapter`, `GeminiToolAdapter`, `AnthropicToolAdapter`).

Built-in tools (all `isModelCallable = false`): `CalculatorTool` (hand-written recursive-descent arithmetic parser), `TextTransformTool`, `JsonTool`, `CurrentTimeTool`, `UuidTool`. Server-backed tools (model-callable): `WebSearchTool`, `WeatherTool` (both POST to `http://127.0.0.1:8080/v1/tools/*`). `DoclingDocumentTool` (document→Markdown, `isModelCallable = false` — model must never choose local file paths).

### Memory system (feature-memory)

- `MemoryScope`: GLOBAL, WORKSPACE, CONVERSATION. `MemoryKind`: FACT, SUMMARY.
- `UserMemoryExtractionService`: After each user input, sends it to the model with a one-line protocol (`MEMORY|<key>|<fact>` or `NONE`). Key regex: `[a-z][a-z0-9_]{0,47}`, fact ≤ 400 chars.
- `ConversationMemoryService`: At every 500th message, streams the last 500 messages through the model and saves a `CONVERSATION`/`SUMMARY` memory entry.
- `MemoryRepository.upsertGlobalFact`: Archives all prior facts with the same key prefix so only the latest value for a key stays active.
- No embedding, vector DB, or semantic search yet.

### User scoping

`CurrentUserProvider` interface in `feature-user` (`val currentUserId: String`) — every repository depends on this. Currently hardcoded to `"local-default"` with a seeded `UserAccount` row. Replacing `CurrentUserProvider` with a real login implementation is the intended pattern for multi-user support.

### Responsive layout

Chat screen uses `BoxWithConstraints`: below 840.dp → `ModalNavigationDrawer` sidebar; above → fixed 288.dp sidebar `Row` with `VerticalDivider`.

## Module Dependency Rules

- `core` depends on nothing except Ktor kotlinx-json.
- `database` depends on `:core` (for enum column adapters) and SQLDelight runtime.
- `feature-user` depends only on `:database` — it is the leaf module.
- All other feature modules depend on `:feature-user` via `CurrentUserProvider`.
- `feature-memory` additionally depends on `:feature-model` (for LLM-driven extraction/summarization).
- `feature-tools` depends on `:core` (ProviderType) and Ktor.
- Keep common code platform-neutral; use `expect`/`actual` only at platform boundaries.
- Prefer feature-local APIs and depend on `core` abstractions rather than reaching across feature modules.

## Coding Conventions

- Four-space indentation, no wildcard imports, PascalCase types, camelCase functions/properties, `UPPER_SNAKE_CASE` constants.
- Detekt config at `config/detekt/detekt.yml`. Run before submitting.
- Commit style: Conventional Commits (`feat:`, `fix:`, `perf:`, etc.), one concern per commit.
- Every change to network endpoints, request/response schemas, sync protocol, database tables, SQLDelight queries, migrations, or persistence behavior must update `CLIENT_NETWORK_DATABASE_REFERENCE.md` in the same commit.
- Place cross-platform tests in `src/commonTest/kotlin`, platform-specific tests in the matching test source set.

## Key Libraries

| Area | Library |
|------|---------|
| UI | Compose Multiplatform 1.9.0 / Material 3 |
| Navigation | Decompose 3.3.0 |
| DI | Koin 4.1.1 |
| Network | Ktor Client 3.3.1 |
| Persistence | SQLDelight 2.1.0 |
| Files/Images | FileKit 0.12.0, Coil 3.3.0 |
| Markdown | multiplatform-markdown-renderer-m3 0.37.0 |

## Platform-Specific Notes

- **Android HTTP**: OkHttp with 10-minute read timeout (SSE can be silent during tool execution rounds).
- **JVM HTTP**: CIO engine with `requestTimeout = 0` (CIO's default 15s would kill mid-stream).
- **JVM DB path**: `~/.fatai/app.db`; migrates legacy `~/.ai-assistant/app.db` on first launch.
- **iOS DB path**: `watson.db` (different filename from Android's `app.db`).
- **iOS**: Open `iosApp/` in Xcode to run. Koin is initialized implicitly on first `koinInject` call.
