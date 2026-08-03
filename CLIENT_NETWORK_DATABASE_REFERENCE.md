# 客户端网络与本地数据库调用参考

本文依据客户端源码整理。范围为 `composeApp`、`feature-*`、`core`、`database` 与 `shared` 中由客户端发起的 HTTP 调用和 SQLDelight 调用；不把服务端实现当作客户端接口，也不包含没有请求实现的空 `ApiService`。

## 网络调用

### 通用约定

- 当前 Koin 默认注入 `FatAiServerModelGateway`，因此正常聊天经 FatAI 服务，而不是直接访问模型厂商。
- FatAI 服务默认地址为 `http://127.0.0.1:8080`。构造函数允许替换 `serverUrl`。
- 除设备登录外，FatAI 服务请求都带 `Authorization: Bearer <access_token>`；客户端在每次请求前调用设备登录接口获取令牌。
- 与 FatAI 服务交互的请求体均为 JSON。同步请求会在后台 FIFO 队列执行；失败只写入 `FatAiServerSync.lastError`，不会自动重试。
- 工具请求不带认证头。模型厂商直连使用其配置的 `baseUrl` 与 `Authorization: Bearer <apiKey>`。

### FatAI 服务认证与聊天

| 方法与路径 | 用途 | 请求参数 | 响应与客户端处理 |
| --- | --- | --- | --- |
| `POST /v1/auth/device` | 为当前本地设备取得 FatAI 服务访问令牌。首次调用会生成 UUID 并保存到本地设置 `fat_ai_server_device_id`。 | `device_id`：持久设备 UUID；`display_name`：`FatAI <currentUserId>`。 | JSON `{ "access_token": "..." }`。失败会终止后续依赖该令牌的请求。 |
| `POST /v1/chat/stream` | 主聊天模型调用，采用 SSE 接收回答和工具调用。发送前会等待该 `model_configuration_id` 的上传完成。 | `messages`：`[{ role, content }]`；`model`：可空，优先使用当前配置模型；`model_configuration_id`：可空本地模型配置 ID；`temperature`：浮点采样温度；`tools`：模型可调用工具定义数组。每个工具包含 `name`、`description` 和 `parameters`；每个参数包含 `name`、`description`、`required`、`allowedValues`。 | 接收 `text/event-stream`：`message` 事件含 `{content}`，追加回答；`tool_call` 含 `{id?, name, arguments}`，暂存后由客户端执行；`done` 表示结束并输出已收集的工具调用。非 2xx 抛出异常。 |

`/v1/chat/stream` 只发送上述字段。`ProviderConfig.maxTokens`、`topP`、`systemPrompt` 不会传给 FatAI 服务；系统提示词已由上下文组装进 `messages`。

### FatAI 服务同步接口

下表请求均由 `FatAiServerSync` 后台顺序发送，均需要 Bearer 令牌。它们将客户端本地缓存镜像到服务端；本地写入不会等待同步成功。

| 方法与路径 | 用途 | JSON 请求参数 |
| --- | --- | --- |
| `POST /v1/workspaces` | 新建或更新工作空间镜像。由工作空间创建/更新及初始化 Inbox 时调用。 | `id`、`name`、`system_prompt`。 |
| `POST /v1/conversations` | 新建或更新会话镜像。创建会话和发送消息前调用。 | `id`、`workspace_id`、`title`、`provider_type`、`model`。 |
| `POST /v1/conversations/{conversationId}/messages` | 上传一条已持久化的用户或助手消息。路径参数 `conversationId` 必须与消息归属一致。 | `id`、`role`（`user` 或 `assistant`）、`content`、`reasoning_content`、`content_type`。 |
| `POST /v1/memories` | 上传新保存的记忆。归档操作当前只写本地数据库，不会调用此接口。 | `id`、`scope`（`GLOBAL`/`WORKSPACE`/`CONVERSATION`）、`content`、`workspace_id`（可空）、`conversation_id`（可空）、`kind`（`FACT`/`SUMMARY`）。 |
| `POST /v1/prompt-templates` | 上传新建的提示词模板。模板更新与删除当前只写本地数据库，不会同步。 | `id`、`name`、`content`、`workspace_id`（可空）、`priority`、`is_enabled`。 |
| `POST /v1/model-configurations` | 上传模型配置和 API Key。API Key 不在本地长期保存，添加/导入旧配置或迁移旧本地密钥时调用。 | `id`、`name`、`provider_type`、`api_key`、`base_url`、`model`、`is_active`。 |
| `POST /v1/model-configurations/{id}/activate` | 将指定模型配置设为服务端活动配置。 | 空对象 `{}`；路径参数 `id` 为本地模型配置 ID。 |
| `DELETE /v1/model-configurations/{id}` | 删除服务端模型配置。 | 无请求体；路径参数 `id` 为配置 ID。服务端返回 404 被视为成功。 |

### 本地工具服务接口

下列接口默认使用同一个 `http://127.0.0.1:8080` 服务地址，但各 Tool 可在构造时替换。工具执行失败会转为 `ToolResult.Failure`，不抛出到聊天 UI。

| 方法与路径 | 用途 | JSON 请求参数 | 响应与约束 |
| --- | --- | --- | --- |
| `POST /v1/tools/search` | 公共 Web 搜索，结果会作为可引用的工具上下文。 | `query`：非空查询词；`maxResults`：1 到 10，默认 5。 | `{ query, results: [{ title, snippet, url, source }] }`。空结果会返回“未找到”文本。 |
| `POST /v1/tools/weather` | 查询指定地点的天气或预报资料。 | `location`：非空城市/地区/国家；`maxResults`：1 到 5，默认 3。 | `{ location, results: [{ title, snippet, url, source }] }`。空结果会返回“未找到”文本。 |
| `POST /v1/tools/document-read` | 通过 Docling 抽取用户主动选择的文件或图片为 Markdown。该 Tool 不暴露给模型，因此模型不能传入任意本地路径。 | `localPath`：用户选择的本地路径；`displayName`：文件名；`mimeType`：MIME 类型。三者都不能为空。 | 成功：`{ displayName, markdown }`；错误：`{ code, message }`。抽取 Markdown 会作为附件上下文进入后续聊天。 |

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
| `ChatItem` | 聊天消息：`id`（PK）、`userId`、`conversationId`、`content`、`markdownDocument`、`type`（`Question`/`Answer`）、`contentType`、`createdAt`。`conversationId` 外键删除时级联删除。 |
| `Conversation` | 会话：`id`（PK）、`userId`、`title`、`workspaceId`、`providerType`、`model`、创建/更新时间、`isPinned`、`isArchived`。 |
| `ApiKey` | 模型配置的非秘密元数据：`id`、`userId`、`providerType`、`name`、`apiKey`、`baseUrl`、`model`、`isActive`、`createdAt`。新代码写入空 `apiKey`，密钥上传至 FatAI 服务。 |
| `Workspace` | 工作空间：`id`、`userId`、`name`、`systemPrompt`、创建/更新时间、`isArchived`。默认 Inbox ID 为 `inbox`。 |
| `MemoryEntry` | 长短期记忆：`id`、`userId`、`scope`、`workspaceId?`、`conversationId?`、`kind`、`content`、时间、`isArchived`。 |
| `PromptTemplate` | 提示词模板：`id`、`userId`、`name`、`content`、`workspaceId?`、`priority`、`isEnabled`、时间。 |
| `FileAsset` | 已选附件的本地元数据：`id`、`userId`、`workspaceId?`、`conversationId?`、`messageId?`、文件名、MIME、`localPath`、`sizeBytes`、`createdAt`。文件内容不写入数据库。 |
| `AppSetting` | 按用户保存的键值设置：`userId` + `key` 为联合主键，另有 `value`、`updatedAt`。主题和 FatAI 设备 ID 使用此表。 |

### 用户与应用设置

| 命名查询 | Repository 调用 / 用途 | 参数 | 数据库效果 |
| --- | --- | --- | --- |
| `selectUserById` | `UserRepository.currentUser`、默认用户存在性检查。 | `id`。 | 读取一条 `UserAccount`。 |
| `insertUser` | `UserRepository` 首次初始化默认用户。 | `id`、`name`、`createdAt`、`updatedAt`。 | 新增一条 `UserAccount`。 |
| `selectAppSetting` | `SettingsRepository.getValue`、启动时读取主题、取得 FatAI 设备 ID。 | `userId`、`key`。 | 读取一个设置值。 |
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
| `updateConversationTitle` | `ChatRepository.updateConversationTitle`；首轮聊天后自动生成标题时也会调用。 | `title`、`updatedAt`、`id`、`userId`。 | 更新标题和会话时间。 |
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
| `archiveMemory` | `MemoryRepository.archive`。 | `isArchived`、`updatedAt`、`id`、`userId`。 | 软删除一条记忆。 |
| `selectActiveGlobalFactByContent` | `MemoryRepository.upsertGlobalFact`。 | `userId`、`content`。 | 检查相同未归档全局事实是否已存在。 |
| `archiveGlobalFactsByPrefix` | `MemoryRepository.upsertGlobalFact`。 | `updatedAt`、`userId`、`prefix`。 | 软删除相同逻辑键（`"key: "` 前缀）的旧事实。 |
| `selectEnabledPromptTemplates` | `PromptTemplateRepository.enabledFor`，由上下文组装调用。 | `userId`、`workspaceId?`。 | 读取启用的全局模板或匹配工作空间模板，按 `priority DESC, updatedAt DESC` 排序。 |
| `insertPromptTemplate` | `PromptTemplateRepository.create`。 | `id`、`userId`、`name`、`content`、`workspaceId?`、`priority`、`isEnabled`、`createdAt`、`updatedAt`。 | 新增启用模板，并异步同步到服务端。 |
| `updatePromptTemplate` | `PromptTemplateRepository.update`。 | `name`、`content`、`priority`、`isEnabled`、`updatedAt`、`id`、`userId`。 | 更新模板；当前不触发服务端同步。 |
| `deletePromptTemplate` | `PromptTemplateRepository.delete`。 | `id`、`userId`。 | 删除模板；当前不触发服务端同步。 |

### 文件附件

| 命名查询 | Repository 调用 / 用途 | 参数 | 数据库效果 |
| --- | --- | --- | --- |
| `selectFilesForConversation` | `FileAssetRepository.forConversation`。 | `conversationId`、`userId`。 | 按创建时间正序读取会话全部附件。 |
| `selectPendingFilesForConversation` | `FileAssetRepository.pendingForConversation`。 | `conversationId`、`userId`。 | 读取尚未关联消息（`messageId IS NULL`）的待发送附件。 |
| `insertFileAsset` | `FileAssetRepository.attach`。 | `id`、`userId`、`workspaceId?`、`conversationId?`、`messageId?`、`displayName`、`mimeType`、`localPath`、`sizeBytes`、`createdAt`。 | 保存本地附件元数据。 |
| `assignPendingFilesToMessage` | `FileAssetRepository.assignPendingToMessage`，发送用户消息后调用。 | `messageId`、`conversationId`、`userId`。 | 将该会话所有待发送附件关联到新消息。 |
| `deleteFileAsset` | `FileAssetRepository.delete`。 | `id`、`userId`。 | 删除附件元数据；不会删除 `localPath` 指向的物理文件。 |
