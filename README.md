<p align="center">
  <img src="assets/branding/fatai-banner.svg" alt="FatAI — context-first AI workspace" width="100%" />
</p>

<p align="center">
  <img src="assets/branding/fatai-icon.svg" alt="FatAI logo" width="112" />
</p>

<h1 align="center">FatAI</h1>

<p align="center">A Kotlin Multiplatform AI workspace built around an explicit context pipeline.</p>

<p align="center">
  <a href="#module-status">Module status</a> ·
  <a href="#what-works-today">What works today</a> ·
  <a href="#context-pipeline">Context pipeline</a> ·
  <a href="README.zh-CN.md">中文文档</a>
</p>

FatAI is a work-in-progress AI assistant. The codebase is organized as KMP feature modules so chat, prompts, memory, files, workspaces, models, knowledge, tools, and agents can evolve independently. This document describes what is in the repository today, not the planned end state.

## Supported targets

- Android
- Desktop JVM
- iOS (`iosArm64` and `iosSimulatorArm64`)
- A small Ktor server module is present as a sample endpoint; it is not an AI proxy or backend.

The application UI is Compose Multiplatform. SQLDelight drivers and Ktor engines are supplied from platform source sets, while feature APIs and domain code live in `commonMain`.

## Module status

| Module | Current implementation | Status |
| --- | --- | --- |
| `core` | `ChatItemType`, `MessageContentType`, provider types, and shared chat primitives. | Implemented |
| `database` | SQLDelight schema; Android, JVM, and iOS drivers; migrations through version 7. | Implemented |
| `feature-user` | Current-user abstraction, default local-user initialization, and data ownership boundary. | Implemented; login and account switching are pending |
| `feature-chat` | Conversation/message repository: create, list, search, pin, archive, delete, persist and update messages. | Implemented |
| `feature-prompt` | Ordered `ContextEngine`, system/template/workspace/memory/file/history providers, and prompt-template persistence. | Implemented; no template-management screen yet |
| `feature-memory` | Global/workspace/conversation memory storage and recall; model-backed summary service at 500 messages. | Implemented; no semantic search or memory-management UI |
| `feature-model` | `ChatProvider` contract, `ModelGateway`, API-key repository, Ktor clients, and one OpenAI-compatible streaming adapter. | Implemented for OpenAI-compatible endpoints |
| `feature-files` | Portable attachment metadata, pending-to-message binding, and attachment persistence. | Implemented; content extraction is pending |
| `feature-workspace` | Default Personal workspace, create/select/update/archive repository operations, workspace instructions. | Implemented; editing/archive UI is pending |
| `feature-settings` | Persisted system/light/dark theme preference. | Implemented |
| `feature-knowledge` | Gradle/KMP module scaffold only. | Not implemented |
| `feature-tools` | Provider-neutral tool contracts, OpenAI-compatible schemas, local safe utilities, and an HTTP-backed web-search tool. | Implemented for the desktop/server local setup |
| `feature-agent` | Gradle/KMP module scaffold only. | Not implemented |
| `shared` | Temporary Koin composition and platform bootstrap bridge while migrations are completed. | Compatibility layer; do not add new feature logic |
| `composeApp` | Decompose root navigation, chat/settings UI, resources, platform entry points, and responsive layouts. | Implemented |

The project includes all planned feature modules, but Knowledge, Tools, and Agent currently contain no domain contracts or runtime behaviour. They are intentionally listed as scaffolds rather than claimed as product features.

## What works today

### Chat and UI

- Responsive Open WebUI-inspired layout: fixed desktop sidebar and mobile navigation drawer.
- Conversation create, search, pin, archive, delete, automatic first-message title, restoration of the latest saved conversation and messages (initially positioned at the latest message), and preservation of the reading position when returning from Settings.
- SSE streaming, stop generation, regenerate, and continue generation.
- A streaming “Thinking…” indicator.
- OpenAI-compatible function calling for built-in tools; web searches call the local FatAI server and return cited result URLs to the model before it writes the final answer.
- Assistant Markdown rendering with GFM tables, links, code blocks, and mobile-oriented typography.
- Decompose stack navigation between Chat and Settings.
- System, light, and dark themes persisted in `AppSetting`, selected through a dialog with an explicit selected state.
- English and Chinese Compose resources selected from the system locale.
- When no API key exists, the app blocks chat and guides the user to Settings; the sidebar footer shows the current user's avatar and name.

### Files and multimodal message presentation

- FileKit picker for PDF, Office files, Markdown, text, and common image formats.
- Pending attachments are stored separately, then assigned to the user message on send.
- Sent images are previewed in chat through FileKit's KMP image integration; non-image attachments render as file cards.
- Reopening a conversation restores attachments beneath their original message.

Attachments are currently represented in the model context as a file manifest (name, MIME type, and size). The app does **not** yet upload file bytes to providers, extract PDF/text content, perform OCR, or send vision message parts.

### Model access

- Add, activate, and delete API-key configurations with provider, base URL, and model fields.
- The active configuration is used for streaming chat requests; new or newly selected keys can start conversations without restarting the app.
- The implemented transport calls the OpenAI Chat Completions SSE shape: `POST {baseUrl}/chat/completions`.

OpenAI, DeepSeek, OpenRouter, Ollama, and Custom can be configured when their endpoint is OpenAI-compatible. Gemini and Claude currently appear in the provider selector only as configuration presets; dedicated Gemini and Anthropic adapters have **not** been implemented, so those native APIs are not supported yet. API keys are stored in the local SQLDelight database; secure platform key storage is still pending.

## Context pipeline

Every outgoing chat request is assembled by `feature-prompt` in deterministic order:

```text
FatAI baseline policy (role, instruction order, uncertainty, and capability boundaries)
  → enabled prompt templates
  → current workspace instruction
  → recalled memory reference
  → conversation file-metadata reference
  → most recent 20 chat messages
  → OpenAI-compatible model gateway
```

`PromptProvider` is the extension point. The baseline policy treats conversation history, memories, file metadata, quoted text, retrieved material, and tool results as reference data, so they cannot replace application or workspace instructions. A future RAG provider, MCP tool-result provider, or agent state provider can join the pipeline without coupling itself to the chat screen.

### Memory

`MemoryEntry` supports `GLOBAL`, `WORKSPACE`, and `CONVERSATION` scopes plus `FACT` and `SUMMARY` kinds. Recall is a SQL query limited to 20 entries. After each user input, the active model classifies whether it contains an explicit durable fact, preference, identity detail, or long-term goal; a matching item is upserted into global user memory and can be recalled in later chats. Temporary requests, questions, secrets, credentials, health data, and one-off task details are excluded. When a completed conversation reaches exactly 500 messages, `ConversationMemoryService` asks the configured model for a conversation summary and stores it as conversation-scoped memory.

There is no embedding generation, vector database, semantic recall, or UI for authoring and reviewing memory yet.

### Multimodal message model

`ChatItemType` identifies the sender (`Question` or `Answer`), while `MessageContentType` identifies the primary body (`Text`, `Markdown`, `Image`, `File`, `ToolResult`, or `Thinking`). `contentType` is persisted on `ChatItem` (migration 4).

`FileAsset` is the attachment sidecar. New attachments have a null `messageId`; on send, migration 5's `messageId` column binds them to the newly created user message. The UI groups assets by this ID, so image previews and file cards remain associated with the correct chat bubble. Only `Text` user input and `Markdown` assistant output are produced by the current chat flow; the other content types are reserved for later renderers.

## Architecture

The Kotlin package namespace and Android module namespaces are standardized on `ai.fatai`; the Android application ID and iOS bundle identifier use `ai.fatai.app`. Replace these identifiers with a domain owned by your organization before publishing.

```text
composeApp (Compose UI, Decompose root, responsive screens)
        │
        ├── shared (temporary Koin wiring and platform bootstrap)
        │      ├── feature-user / feature-chat / feature-model / feature-prompt
        │      ├── feature-memory / feature-files / feature-workspace
        │      └── feature-settings
        │
        ├── core (shared primitives)
        └── database (SQLDelight schema, migrations, platform drivers)

feature-knowledge / feature-tools / feature-agent
        └── KMP scaffolds reserved for V2 implementation
```

`composeApp` is the application shell, not a feature module. Feature data and use cases are kept out of UI code where possible, but Koin composition remains in `shared` while the historical shared module is retired. New domain behaviour should go to the corresponding `feature-*` module.

## Localization and branding

Compose UI strings are in:

- English: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Chinese: `composeApp/src/commonMain/composeResources/values-zh/strings.xml`

Android's app name remains in Android resources under `composeApp/src/androidMain/res/values*`. The native iOS string tables live in `iosApp/iosApp/{en,zh-Hans}.lproj/`. Brand sources are `assets/branding/fatai-icon.svg` and `assets/branding/fatai-banner.svg`.

## Persistence

SQLDelight stores users, conversations, messages, provider configurations, workspaces, memory entries, prompt templates, file assets, and app settings. Every business record carries a `userId`, and repository reads and writes are filtered by the current user. Startup creates the `local-default` local user and migration assigns existing data to it; a future login flow only needs to replace the `CurrentUserProvider` implementation. Desktop stores the database at `~/.fatai/app.db` and copies a legacy `~/.ai-assistant/app.db` on first launch when available. The desktop driver persists the SQLDelight schema version and safely recovers old local databases that were created without one. Android and iOS use their platform SQLDelight drivers.

## Build and run

```shell
# Desktop
./gradlew :composeApp:run

# Android APK
./gradlew :composeApp:assembleDebug

# Compile checks used for the KMP application
./gradlew :composeApp:compileKotlinJvm
./gradlew :composeApp:compileDebugKotlinAndroid
./gradlew :composeApp:compileKotlinIosSimulatorArm64

# Sample Ktor server
./gradlew :server:run
```

With the server running at `http://127.0.0.1:8080`, desktop chat exposes separate `web_search` and `weather` functions to OpenAI-compatible models. `web_search` calls `POST /v1/tools/search` for general current information. `weather` calls `POST /v1/tools/weather` with an explicit location, ranks Timeanddate weather pages first, and is selected instead of generic web search for weather questions. The keyless development provider extracts DuckDuckGo's HTML web-search results (not its limited Instant Answer API); replace it with a supported production provider such as Tavily, Brave, Bing, or SerpAPI before deployment. For weather and other location-dependent questions, provide a location; FatAI asks for it rather than guessing. Tool-backed responses retain a markdown **Information sources** section, and the chat bubble says **Searching…** or **Checking weather…** instead of showing a disruptive tool toast.

The server keeps these boundaries in separate source files: `WebSearch.kt` owns general search contracts and the shared DuckDuckGo HTML transport, while `Weather.kt` owns the weather contract and Timeanddate-specific ranking.

If your network requires an HTTP proxy, the server honors `HTTPS_PROXY`, `HTTP_PROXY`, and `ALL_PROXY` (including lowercase variants) when it fetches search results.

Open `iosApp/` in Xcode to run the iOS app.

## Quality gates

- Detekt is applied to every Gradle subproject using `config/detekt/detekt.yml`.
- Kover is applied to `composeApp` for JVM coverage reporting.
- Tests exist only as minimal scaffolding and are not currently a delivery gate; compilation checks are the primary verification step during the architecture migration.

## Key dependencies

| Area | Library |
| --- | --- |
| UI | Compose Multiplatform 1.9.0 / Material 3 |
| Navigation | Decompose 3.3.0 |
| DI | Koin 4.1.1 |
| Network | Ktor Client 3.3.1 |
| Persistence | SQLDelight 2.1.0 |
| Files and images | FileKit 0.12.0, Coil 3.3.0 |
| Markdown | multiplatform-markdown-renderer-m3 0.37.0 |
