# Tool module

`feature-tools` provides the application's platform-neutral tool boundary. It exposes a registry,
an allow-list policy, argument validation, bounded output, and offline built-ins:

- `calculator`
- `text_transform`
- `json`
- `current_time`
- `uuid`

When a user attaches a supported file or image, the application also invokes the internal
`docling_document_read` tool before requesting a model response. It is intentionally not exposed
to model function calling: only an attachment selected in the UI supplies its local path. The
local FatAI server forwards that file to Docling Serve (`http://127.0.0.1:5001` by default) and
passes the returned Markdown to the model. Set `DOCLING_SERVER_URL` when Docling listens elsewhere.

Each future integration (MCP, web search, workspace file access, or a user-authorized HTTP API)
implements `Tool` and is registered through Koin. Keep external or side-effecting tools opt-in and
apply a restrictive `ToolExecutionPolicy` at their call site.

`ToolProviderAdapter` translates the neutral definitions into the provider wire schema. The module
includes adapters for OpenAI-compatible APIs (OpenAI, DeepSeek, OpenRouter, Ollama, Custom), Gemini,
and Claude. The current OpenAI-compatible gateway now accepts tool definitions and emits its native
`tools` payload automatically. Gemini and Claude adapters are ready for their respective API clients.
