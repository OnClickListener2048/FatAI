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

本节将在下一次文档提交补充。
