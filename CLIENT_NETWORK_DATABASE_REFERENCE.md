# 客户端网络与本地数据库调用参考

本文依据客户端源码整理。范围为 `composeApp`、`feature-*`、`core`、`database` 与 `shared` 中由客户端发起的 HTTP 调用和 SQLDelight 调用；不把服务端实现当作客户端接口，也不包含没有请求实现的空 `ApiService`。

## 网络调用

### 通用约定

- 当前 Koin 默认注入 `FatAiServerModelGateway`，因此正常聊天经 FatAI 服务，而不是直接访问模型厂商。
- FatAI 服务默认地址为 `http://127.0.0.1:8080`。构造函数允许替换 `serverUrl`。
- 除设备登录外，FatAI 服务请求都带 `Authorization: Bearer <access_token>`；客户端在每次请求前调用设备登录接口获取令牌。
- 与 FatAI 服务交互的请求体均为 JSON。同步请求先写入 SQLite `SyncOutbox`，后台按实体序号发送并自动重试；任务状态、失败码和错误信息均持久化，应用重启后会恢复未完成任务。
- 工具请求不带认证头。模型厂商直连使用其配置的 `baseUrl` 与 `Authorization: Bearer <apiKey>`。

### FatAI 服务认证与聊天

| 方法与路径 | 用途 | 请求参数 | 响应与客户端处理 |
| --- | --- | --- | --- |
| `POST /v1/auth/device` | 为当前本地设备取得 FatAI 服务访问令牌。首次调用会生成 UUID 并保存到本地设置 `fat_ai_server_device_id`。 | `device_id`：持久设备 UUID；`display_name`：`FatAI <currentUserId>`。 | JSON `{ "access_token": "..." }`。失败会终止后续依赖该令牌的请求。 |
| `POST /v1/chat/stream` | 主聊天模型调用，采用 SSE 接收回答和工具调用。发送前会等待该 `model_configuration_id` 的上传完成。服务端负责上下文组装、工具执行、聊天直存与标题生成。 | `messages`：`[{ role, content }]`，仅原始对话轮次（客户端不再预组装上下文）；`model`：可空，优先使用当前配置模型；`model_configuration_id`：可空本地模型配置 ID；`temperature`：浮点采样温度；`thinking`：布尔，默认 `false`，为 `true` 时开启提供商思考模式（DeepSeek 等）并通过直连流式路径转发思考内容，为 `false` 时向提供商请求 `thinking: {"type": "disabled"}`；`workspace_id`/`conversation_id`：用于服务端按 DB 组装模板、工作空间指令与记忆，并作为聊天直存的归属；`response_language_tag`：响应语言标签（默认 `en`）；`tool_results`：可空字符串数组，客户端侧瞬态工具结果（如 Docling 提取内容），服务端追加在历史之后；`include_contextual_references`：默认为 `true`，附件分析置 `false`；`user_message_id`/`assistant_message_id`：客户端持有的消息 ID，服务端以此直存本轮对话；`tools`：模型可调用工具定义数组。每个工具包含 `name`、`description` 和 `parameters`；每个参数包含 `name`、`description`、`required`、`allowedValues`。 | 接收 `text/event-stream`：`message` 事件为 `{content?}` 与 `{reasoning_content?}` 的增量 JSON（二者可同时出现；思考模式开启时先推 `reasoning_content` 后推 `content`，关闭时仅推 `content`），客户端按字段分别追加思考内容与回答；`tool_call` 含 `{id?, name, arguments, sources?}`，`sources` 为服务端执行工具后返回的结构化来源（`[{ title, url }]`，已按 URL 跨轮次去重），客户端用于渲染来源区块；`done` 表示结束并携带 `{"sources": [...]}`——本次回答引用的服务端 RAG 来源，`[{title, kind, id}]`，`kind` 为 `memory` 或 `knowledge_document`（空数组表示未注入引用）。非 2xx 抛出异常。 |

`/v1/chat/stream` 只发送上述字段。`ProviderConfig.maxTokens`、`topP`、`systemPrompt` 不会传给 FatAI 服务。上下文组装（系统提示词、启用模板、工作空间指令、记忆召回、历史截断）已迁移到服务端 `assemble_context`，客户端 `ContextEngine` 已移除；服务端同时绑定并执行它支持的 `tools`（当前为 `web_search` 与 `weather`），在单次 SSE 流内完成"模型 → 工具 → 模型"循环后输出最终回答。客户端当前向模型广告的工具只有 `web_search` 与 `weather`；calculator、text_transform、json、current_time、uuid 等本地工具不再暴露给模型。

**聊天消息直存**：带 `conversation_id` 与 `assistant_message_id` 的聊天请求，服务端在流结束时把用户消息与助手回答直接写入服务端数据库并进入变更流（会话缺失时服务端自动补建）。客户端因此**不再**为聊天消息入队同步（`insertMessage(sync=false)`、`stopGeneration`/`continueGeneration` 同样只写本地缓存）；仅当流式调用失败时，客户端会把用户问题入队作为兜底。删除仍走 outbox（服务端 DELETE 采用"删即为胜"语义，不受直存 sequence 影响）。

### 文件上传(对象存储)

附件采用 S3-like 语义：用户选文件后客户端先 multipart 上传字节，服务端存到用户隔离目录（模拟对象存储）并返回资产 `id`；后续读取转换一律用 `file_id` 引用，客户端不再向服务端暴露本地路径。上传由 `feature-files/FileAssetService` 发起，均需要 Bearer 令牌。

| 方法与路径 | 用途 | 请求参数 | 响应与客户端处理 |
| --- | --- | --- | --- |
| `POST /v1/files` | multipart 上传用户选择的文件。成功返回的 `id` 会作为本地 `FileAsset.id`（见数据库节）。上传失败时 `attachFile` 改为使用 `local-` 前缀的本地 id，并在发送时回退到 `docling_document_read` 的 `local_path` 模式。 | `file`：multipart 文件部分（`filename`、`Content-Type`）；查询参数 `workspace_id?`、`conversation_id?`。 | `{ id, display_name, storage_path, ... }`，`ignoreUnknownKeys` 解析 `id` 与 `display_name`；非 2xx 抛 `FileUploadException`。 |
| `POST /v1/files/{file_id}/read` | 按 `file_id` 读取已上传文件并转 Markdown（服务端自行读存储，需鉴权）。由 `DoclingDocumentTool` 的 `file_id` 模式调用。 | 无请求体；路径参数 `file_id`。 | `{ displayName, markdown }`；错误 `{ code, message }`。 |
| `GET /v1/files/{file_id}` | 按 `file_id` 下载已上传文件的原始字节（附件下载功能）。三端统一：客户端先经 `FileAssetService.download` 拉取字节，再通过 FileKit 弹系统保存对话框（Android SAF / JVM 文件对话框 / iOS 文档选择器）。`local-` 前缀的本地附件未上传服务端，下载时直接复制 `localPath` 文件。图片附件渲染也走该接口（Coil 带 Bearer 头直载，利用内存/磁盘缓存）。 | 无请求体；路径参数 `file_id`，需 Bearer 令牌。 | 原始文件字节（`Content-Type` 为上传时的 MIME）；非 2xx 抛异常。 |

### FatAI 服务同步接口

下表请求均由 `FatAiServerSync` 后台发送，均需要 Bearer 令牌。客户端本地写入不会阻塞等待网络；远程回写直接更新本地缓存，不会重新进入 outbox。

| 方法与路径 | 用途 | JSON 请求参数 |
| --- | --- | --- |
| `POST /v1/workspaces` | 新建或更新工作空间镜像。由工作空间创建/更新及初始化 Inbox 时调用。 | `id`、`name`、`system_prompt`。 |
| `POST /v1/conversations` | 新建或更新会话镜像。创建会话和发送消息前调用。 | `id`、`workspace_id`、`title`、`provider_type`、`model`。 |
| `POST /v1/conversations/{conversationId}/messages` | 上传一条已持久化的用户或助手消息。路径参数 `conversationId` 必须与消息归属一致。 | `id`、`role`（`user` 或 `assistant`）、`content`、`reasoning_content`、`content_type`。 |
| `POST /v1/memories` | 通过通用同步协议上传记忆新增、更新和归档状态。 | `id`、`scope`（`GLOBAL`/`WORKSPACE`/`CONVERSATION`）、`content`、`workspace_id`（可空）、`conversation_id`（可空）、`kind`（`FACT`/`SUMMARY`）、`is_archived`。 |
| `POST /v1/prompt-templates` | 通过通用同步协议上传提示词模板新增、更新和删除。 | `id`、`name`、`content`、`workspace_id`（可空）、`priority`、`is_enabled`。 |
| `POST /v1/model-configurations` | 上传模型配置和 API Key。API Key 不在本地长期保存，添加/导入旧配置或迁移旧本地密钥时调用。 | `id`、`name`、`provider_type`、`api_key`、`base_url`、`model`、`is_active`。 |
| `POST /v1/model-configurations/{id}/activate` | 将指定模型配置设为服务端活动配置。 | 空对象 `{}`；路径参数 `id` 为本地模型配置 ID。 |
| `DELETE /v1/model-configurations/{id}` | 删除服务端模型配置。 | 无请求体；路径参数 `id` 为配置 ID。服务端返回 404 被视为成功。 |

### 双向同步接口

| 方法与路径 | 用途 | 关键参数 |
| --- | --- | --- |
| `POST /v1/sync/operations` | 上传一个持久化 outbox 任务。 | `operation_id`、`entity_type`、`entity_id`、`operation`、`sequence`、`schema_version`、`payload`。重复 `operation_id` 幂等，旧 `sequence` 不覆盖新状态。 |
| `GET /v1/sync/snapshot` | 本地 cursor 为 `0` 且无待上传任务时，重建完整本地缓存。 | Bearer 令牌；返回 `entities` 和当前 `cursor`。 |
| `GET /v1/sync/changes?cursor=&limit=` | 按 cursor 拉取其他设备或服务端产生的增量变更。 | `cursor`、`limit`；客户端按返回顺序落库后再更新 cursor。 |

服务端 REST 写接口（workspaces、conversations、messages、memories、prompt-templates、model-configurations、settings）也会以服务端自增 sequence 写入同一条变更流，因此通过 REST 或服务端生成产生的数据同样能被增量拉取到所有设备。客户端收到的 `401` 会作废缓存令牌并重新获取；启动时 outbox 中 `FAILED` 任务会转为 `RETRYING` 重试一次。同步协议中 `DELETE` 操作由服务端无条件应用（删即为胜），避免聊天直存的服务端 sequence 导致客户端删除被误拒。

### 本地工具服务接口

下列接口默认使用同一个 `http://127.0.0.1:8080` 服务地址，但各 Tool 可在构造时替换。工具执行失败会转为 `ToolResult.Failure`，不抛出到聊天 UI。

| 方法与路径 | 用途 | JSON 请求参数 | 响应与约束 |
| --- | --- | --- | --- |
| `POST /v1/tools/search` | 公共 Web 搜索，结果会作为可引用的工具上下文。 | `query`：非空查询词；`maxResults`：1 到 10，默认 5。 | `{ query, results: [{ title, snippet, url, source }] }`。空结果会返回“未找到”文本。 |
| `POST /v1/tools/weather` | 查询指定地点的天气或预报资料。 | `location`：非空城市/地区/国家；`maxResults`：1 到 5，默认 3。 | `{ location, results: [{ title, snippet, url, source }] }`。空结果会返回“未找到”文本。 |
| `POST /v1/tools/document-read` | 通过 Docling 抽取用户主动选择的文件或图片为 Markdown。该 Tool 不暴露给模型，因此模型不能传入任意本地路径。两种模式：`file_id`（已上传文件，带 Bearer 令牌调 `POST /v1/files/{file_id}/read`，见"文件上传"节）与 `local_path`（旧桌面迁移模式，仅服务端 `ALLOW_LOCAL_DOCUMENT_PATHS=true` 时可用）。 | `file_id`：服务端存储的文件 id（与 `local_path` 二选一）；`localPath`：用户选择的本地路径；`displayName`：文件名；`mimeType`：MIME 类型。后两者与二者之一不能为空。 | 成功：`{ displayName, markdown }`；错误：`{ code, message }`。抽取 Markdown 会作为附件上下文进入后续聊天。 |

### OpenAI 兼容模型直连接口

`OpenAICompatibleProvider` 仍保留，但默认依赖注入不使用它；只有显式改为 `ChatProviderModelGateway` 时才会直接调用模型厂商。目标 URL 为 `{baseUrl}/chat/completions`，`baseUrl` 去除尾随 `/` 后拼接。默认配置见 `ProviderType`：OpenAI、DeepSeek、Gemini、Claude、OpenRouter、Ollama 和 Custom；其中类本身按 OpenAI Chat Completions 协议编码，接入方需确认其 `baseUrl` 兼容该协议。

| 方法与路径 | 用途 | 请求头 | JSON 请求参数 | 响应处理 |
| --- | --- | --- | --- | --- |
| `POST {baseUrl}/chat/completions` | 流式模型对话与工具调用。 | `Authorization: Bearer <apiKey>`、`Content-Type: application/json; charset=UTF-8`、`Accept: text/event-stream`。连接超时 30 秒；请求/套接字超时 120 秒。 | `model`；`messages`：`[{role, content}]`；`stream: true`；`max_tokens`；`temperature`；`top_p`；可选 `tools`（OpenAI function 格式）；工具非空时 `tool_choice: "auto"`。 | 支持 SSE 与普通 JSON。SSE 读取 `data:` 行：`[DONE]` 结束；增量支持 `content`/`text` 与 `reasoning_content`/`reasoning`；工具调用按 `index` 合并。 |
| `POST {baseUrl}/chat/completions` | 同步模型调用，仅用于 `chatSync`。 | `Authorization: Bearer <apiKey>`、`Content-Type: application/json; charset=UTF-8`。 | 与上行相同，但 `stream: false`。 | 读取 JSON `choices[0].message.content`；异常包装为 `Result.failure`。 |

`thinking` 字段定义在请求模型中，但当前客户端不会为它赋值。直连响应中的 token usage 已有数据类定义，但当前没有映射进 `ChatStreamChunk`。

### HTTP 客户端与未实现接口

- Android 使用 Ktor OkHttp，JVM Desktop 使用 CIO，iOS 使用 Darwin。
- `feature-model/.../ApiService.kt` 只有持有 `HttpClient` 的空类，没有任何网络调用，故没有可记录的接口。
- 本仓库当前不含 `server/` 目录；本文的服务端响应结构仅记录客户端实际解析或依赖的字段。

## 本地数据库调用

客户端本地数据由 SQLDelight 管理，查询定义唯一来源为 `database/src/commonMain/sqldelight/ai/fatai/database/sqldelight/Watson.sq`，生成接口为 `WatsonQueries`。Android、JVM 与 iOS 分别创建平台 SQLite 驱动。除 `UserAccount` 查询外，所有 Repository 都从 `CurrentUserProvider.currentUserId` 自动传入 `userId`；当前固定为 `local-default`。

时间字段都是 Unix epoch 毫秒。ID 多由客户端 UUID 生成，`isActive`、`isArchived`、`isEnabled` 等布尔值以 `0`/`1` 的 `Long` 传给 SQLDelight。

### 数据表

| 表 | 用途与主要字段 |
| --- | --- |
| `UserAccount` | 本地数据归属用户：`id`（PK）、`name`、`createdAt`、`updatedAt`。初始化时确保默认用户存在。 |
| `ChatItem` | 聊天消息：`id`（PK）、`userId`、`conversationId`、`content`、`markdownDocument`、`sources`（来源 JSON：`[{label, url?}]`，用于消息来源 UI）、`type`（`Question`/`Answer`）、`contentType`、`createdAt`。`conversationId` 外键删除时级联删除。 |
| `Conversation` | 会话：`id`（PK）、`userId`、`title`、`workspaceId`、`providerType`、`model`、创建/更新时间、`isPinned`、`isArchived`。 |
| `ApiKey` | 模型配置的非秘密元数据：`id`、`userId`、`providerType`、`name`、`apiKey`、`baseUrl`、`model`、`isActive`、`createdAt`。新代码写入空 `apiKey`，密钥上传至 FatAI 服务。 |
| `Workspace` | 工作空间：`id`、`userId`、`name`、`systemPrompt`、创建/更新时间、`isArchived`。默认 Inbox ID 为 `inbox`。 |
| `MemoryEntry` | 长短期记忆：`id`、`userId`、`scope`、`workspaceId?`、`conversationId?`、`kind`、`content`、时间、`isArchived`。 |
| `PromptTemplate` | 提示词模板：`id`、`userId`、`name`、`content`、`workspaceId?`、`priority`、`isEnabled`、时间。 |
| `FileAsset` | 已选附件的本地元数据：`id`、`userId`、`workspaceId?`、`conversationId?`、`messageId?`、文件名、MIME、`localPath`、`sizeBytes`、`createdAt`。文件内容不写入数据库。`id` 在附件上传成功后为服务端返回的资产 id（用于 `file_id` 读取）；上传失败时使用 `local-` 前缀的本地 UUID，发送时回退到 `local_path` 模式。 |
| `AppSetting` | 按用户保存的键值设置：`userId` + `key` 为联合主键，另有 `value`、`updatedAt`。主题和 FatAI 设备 ID 使用此表。 |
| `SyncSequence` | 每个实体的本地单调序号，避免乱序操作覆盖。 |
| `SyncOutbox` | 持久化同步任务：状态包括 `PENDING`、`SENDING`、`RETRYING`、`FAILED`，并记录重试次数及错误分类；同一实体的未发送任务会合并为最新状态，避免队列膨胀。 |

### 用户与应用设置

| 命名查询 | Repository 调用 / 用途 | 参数 | 数据库效果 |
| --- | --- | --- | --- |
| `selectUserById` | `UserRepository.currentUser`、默认用户存在性检查。 | `id`。 | 读取一条 `UserAccount`。 |
| `insertUser` | `UserRepository` 首次初始化默认用户。 | `id`、`name`、`createdAt`、`updatedAt`。 | 新增一条 `UserAccount`。 |
| `selectAppSetting` | `SettingsRepository.getValue`、启动时读取主题、取得 FatAI 设备 ID。 | `userId`、`key`。 | 读取一个设置值。 |
| `upsertAppSetting`、`deleteRemoteSetting` | 设置本地值及应用服务端下行删除。 | `userId`、`key`、`value`、`updatedAt`。 | 幂等写入或删除 `AppSetting`。 |
| `upsertAppSetting` | `SettingsRepository.putValue`、`setThemeMode`、创建设备 ID。 | `userId`、`key`、`value`、`updatedAt`。 | `INSERT OR REPLACE` 写入设置。 |

### 会话与消息

| 命名查询 | Repository 调用 / 用途 | 参数 | 数据库效果 |
| --- | --- | --- | --- |
| `selectAllOrderedByTime` | `ChatRepository.getMessages`、`getMessageCount`。 | `conversationId`、`userId`。 | 按 `createdAt ASC` 读取该会话消息。读取 Markdown 消息时，若 `markdownDocument` 缺失，会立即调用下述 `updateMarkdownDocumentById` 回填持久化 AST。 |
| `selectById` | 已生成但当前 Repository 未调用。 | `id`、`userId`。 | 读取一条 `ChatItem`。 |
| `insertItem` | `ChatRepository.insertMessage`。 | `id`、`userId`、`conversationId`、`content`、`markdownDocument`、`type`、`contentType`、`createdAt`。 | 插入用户或助手消息；Markdown 会先在客户端解析并编码保存。随后更新会话时间。 |
| `updateContentById` | `ChatRepository.updateMessageContent`。 | `content`、`id`、`userId`。 | 更新消息原始文本。当前调用方很少使用，且该查询不更新 `markdownDocument`。 |
| `updateMarkdownDocumentById` | `ChatRepository.getMessages` 的旧数据迁移。 | `markdownDocument`、`id`、`userId`。 | 保存从 `content` 解析出的 Markdown 文档编码。 |
| `deleteById` | `ChatRepository.deleteMessage`。 | `id`、`userId`。 | 删除单条消息。 |
| `deleteByConversationId` | `ChatRepository.deleteConversation` 的第一步。 | `conversationId`、`userId`。 | 显式删除会话下全部消息。 |
| `clearAll` | 已生成，当前 Repository 未调用。 | `userId`。 | 删除该用户全部 `ChatItem`。 |
| `selectConversations` | `ChatRepository.getConversations`。 | `userId`。 | 读取未归档会话，按 `updatedAt DESC` 排序。 |
| `selectConversationsForWorkspace` | `ChatRepository.getConversations(workspaceId)`。 | `userId`、`workspaceId`。 | 读取指定工作空间的未归档会话，按更新时间倒序。 |
| `selectArchivedConversations` | `ChatRepository.getArchivedConversations`。 | `userId`。 | 读取已归档会话，按更新时间倒序。 |
| `searchConversations` | `ChatRepository.searchConversations`。 | `query`、`userId`。 | 对未归档会话标题执行 `LIKE '%query%'` 搜索。 |
| `selectConversationById` | `ChatRepository.getConversationById`。 | `id`、`userId`。 | 读取一条会话。 |
| `insertConversation` | `ChatRepository.createConversation`。 | `id`、`userId`、`title`、`workspaceId`、`providerType`、`model`、`createdAt`、`updatedAt`、`isPinned`、`isArchived`。 | 新建会话。 |
| `updateConversationTitle` | `ChatRepository.updateConversationTitle`（用户手动重命名）。新会话标题由服务端在首轮聊天后异步用模型生成并经变更流同步。 | `title`、`updatedAt`、`id`、`userId`。 | 更新标题和会话时间。 |
| `updateConversationPin` | `ChatRepository.toggleConversationPin`。 | `isPinned`、`updatedAt`、`id`、`userId`。 | 更新置顶状态和会话时间。 |
| `updateConversationArchive` | `ChatRepository.toggleConversationArchive`。 | `isArchived`、`updatedAt`、`id`、`userId`。 | 归档或恢复会话并更新时间。 |
| `updateConversationUpdatedAt` | `ChatRepository.insertMessage` 后调用。 | `updatedAt`、`id`、`userId`。 | 仅更新时间，用于排序。 |
| `deleteConversation` | `ChatRepository.deleteConversation` 的第二步。 | `id`、`userId`。 | 删除会话记录。 |

### 模型配置

| 命名查询 | Repository 调用 / 用途 | 参数 | 数据库效果 |
| --- | --- | --- | --- |
| `selectAllApiKeys` | `ApiKeyRepository.getAllKeys`、`getActiveKey`。 | `userId`。 | 按创建时间倒序读取配置。读取旧的非空 `apiKey` 会上传至服务端并清空本地密钥。 |
| `selectApiKeyById` | `ApiKeyRepository.importKeys`。 | `id`、`userId`。 | 检查导入配置是否已存在。 |
| `selectApiKeysByProvider` | `ApiKeyRepository.getKeysByProvider`。 | `userId`、`providerType`。 | 读取指定厂商的配置。 |
| `selectActiveApiKey` | 已生成，当前 `getActiveKey` 改为从全量结果筛选。 | `userId`。 | 读取一条活动配置。 |
| `insertApiKey` | `ApiKeyRepository.addKey`、`importKeys`。 | `id`、`userId`、`providerType`、`name`、`apiKey`、`baseUrl`、`model`、`isActive`、`createdAt`。 | 新增配置；常规新增将 `apiKey` 写为空字符串。 |
| `updateApiKeyActive` | `ApiKeyRepository.setActiveKey`。 | `id`、`isActive`、`userId`。 | 激活指定配置。调用前会先停用全部配置。 |
| `deactivateAllApiKeys` | `addKey(setActive=true)`、`setActiveKey`。 | `userId`。 | 将该用户所有配置设为非活动。 |
| `clearApiKeySecret` | 旧本地密钥迁移完成后。 | `id`、`userId`。 | 擦除本地 `apiKey` 文本。 |
| `deleteApiKey` | `ApiKeyRepository.deleteKey`。 | `id`、`userId`。 | 删除本地配置；另触发服务端删除。 |

### 工作空间、记忆与提示词

| 命名查询 | Repository 调用 / 用途 | 参数 | 数据库效果 |
| --- | --- | --- | --- |
| `selectWorkspaces` | `WorkspaceRepository.getAll`。 | `userId`。 | 读取未归档工作空间，按更新时间倒序。 |
| `selectWorkspaceById` | `WorkspaceRepository.getById`、`ensureInbox`。 | `id`、`userId`。 | 读取一个工作空间。 |
| `insertWorkspace` | `ensureInbox`、`WorkspaceRepository.create`。 | `id`、`userId`、`name`、`systemPrompt`、`createdAt`、`updatedAt`、`isArchived`。 | 新增工作空间。 |
| `updateWorkspace` | `WorkspaceRepository.update`。 | `name`、`systemPrompt`、`updatedAt`、`id`、`userId`。 | 更新名称、系统提示词与更新时间。 |
| `archiveWorkspace` | `WorkspaceRepository.archive`。 | `isArchived`、`updatedAt`、`id`、`userId`。 | 归档或恢复工作空间；Inbox 不允许归档。 |
| `selectMemoriesForContext` | `MemoryRepository.recall`，由上下文组装调用。 | `userId`、`workspaceId?`、`conversationId?`、`limit`（默认 20）。 | 读取未归档的全局、匹配工作空间或匹配会话记忆，按更新时间倒序。 |
| `insertMemory` | `MemoryRepository.save`、`upsertGlobalFact`。 | `id`、`userId`、`scope`、`workspaceId?`、`conversationId?`、`kind`、`content`、`createdAt`、`updatedAt`、`isArchived`。 | 插入记忆；正常保存后异步同步到服务端。 |
| `archiveMemory` | `MemoryRepository.archive`。 | `isArchived`、`updatedAt`、`id`、`userId`。 | 软删除一条记忆，并进入同步 outbox。 |
| `selectActiveGlobalFactByContent` | `MemoryRepository.upsertGlobalFact`。 | `userId`、`content`。 | 检查相同未归档全局事实是否已存在。 |
| `archiveGlobalFactsByPrefix` | `MemoryRepository.upsertGlobalFact`。 | `updatedAt`、`userId`、`prefix`。 | 软删除相同逻辑键（`"key: "` 前缀）的旧事实，并逐条进入同步 outbox。 |
| `selectMemoryById`、`selectGlobalFactsByPrefix` | `MemoryRepository.archive`、`upsertGlobalFact`。 | `id`/`userId` 或 `prefix`/`userId`。 | 读取归档前实体，构造服务端同步 payload。 |
| `selectEnabledPromptTemplates` | `PromptTemplateRepository.enabledFor`，由上下文组装调用。 | `userId`、`workspaceId?`。 | 读取启用的全局模板或匹配工作空间模板，按 `priority DESC, updatedAt DESC` 排序。 |
| `insertPromptTemplate` | `PromptTemplateRepository.create`。 | `id`、`userId`、`name`、`content`、`workspaceId?`、`priority`、`isEnabled`、`createdAt`、`updatedAt`。 | 新增启用模板，并进入同步 outbox。 |
| `selectPromptTemplateById` | `PromptTemplateRepository.update`。 | `id`、`userId`。 | 读取更新后的模板归属字段。 |
| `updatePromptTemplate` | `PromptTemplateRepository.update`。 | `name`、`content`、`priority`、`isEnabled`、`updatedAt`、`id`、`userId`。 | 更新模板并进入同步 outbox。 |
| `deletePromptTemplate` | `PromptTemplateRepository.delete`。 | `id`、`userId`。 | 删除模板并进入同步 outbox。 |

同步回写使用以下幂等 SQLDelight 查询：`upsertRemoteWorkspace`、`deleteRemoteWorkspace`、
`upsertRemoteConversation`、`deleteRemoteConversation`、`upsertRemoteMessage`、
`deleteRemoteMessage`、`upsertRemoteMemory`、`deleteRemoteMemory`、
`upsertRemotePromptTemplate`、`deleteRemotePromptTemplate`、`upsertRemoteApiKey`、
`deleteRemoteApiKey` 和 `upsertSyncSequence`。这些查询由 `SyncRemoteStore` 调用，不会触发
本地业务 Repository 的再次同步。

### 数据库删除恢复验收

桌面端数据库路径为 `%USERPROFILE%\\.fatai\\app.db`。可按以下步骤验证恢复能力：

1. 启动服务端：在 `C:\\Users\\wang2\\fat-ai-server` 执行 `python main.py`。
2. 启动客户端，创建一个工作空间、会话和消息，等待同步状态中的 `pendingCount` 回到 `0`。
3. 完全退出客户端后删除 `%USERPROFILE%\\.fatai\\app.db`，不要删除服务端数据库。
4. 再次运行 `.\\gradlew.bat :composeApp:run`。启动同步会使用稳定设备 ID，先请求
   `/v1/sync/snapshot`，再请求 `/v1/sync/changes`，本地应重新出现刚才的工作空间、会话和消息。
5. 修改恢复后的消息并重启客户端，确认修改仍可上传，证明 snapshot 携带的实体 sequence 已被保留。

Android/iOS 使用各自平台 SQLite 沙盒路径，验收步骤相同：清除应用数据后重新启动并检查相同实体。

### 文件附件

| 命名查询 | Repository 调用 / 用途 | 参数 | 数据库效果 |
| --- | --- | --- | --- |
| `selectFilesForConversation` | `FileAssetRepository.forConversation`。 | `conversationId`、`userId`。 | 按创建时间正序读取会话全部附件。 |
| `selectPendingFilesForConversation` | `FileAssetRepository.pendingForConversation`。 | `conversationId`、`userId`。 | 读取尚未关联消息（`messageId IS NULL`）的待发送附件。 |
| `insertFileAsset` | `FileAssetRepository.attach`。`attach` 新增 `id` 参数：默认生成 UUID；上传成功后传入服务端资产 id，失败时传入 `local-` 前缀 id。 | `id`、`userId`、`workspaceId?`、`conversationId?`、`messageId?`、`displayName`、`mimeType`、`localPath`、`sizeBytes`、`createdAt`。 | 保存本地附件元数据。 |
| `assignPendingFilesToMessage` | `FileAssetRepository.assignPendingToMessage`，发送用户消息后调用。 | `messageId`、`conversationId`、`userId`。 | 将该会话所有待发送附件关联到新消息。 |
| `deleteFileAsset` | `FileAssetRepository.delete`。 | `id`、`userId`。 | 删除附件元数据；不会删除 `localPath` 指向的物理文件。 |
