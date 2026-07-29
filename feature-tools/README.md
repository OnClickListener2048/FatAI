# Tool module

`feature-tools` provides the application's platform-neutral tool boundary. It exposes a registry,
an allow-list policy, argument validation, bounded output, and offline built-ins:

- `calculator`
- `text_transform`
- `json`
- `current_time`
- `uuid`

Each future integration (MCP, web search, workspace file access, or a user-authorized HTTP API)
implements `Tool` and is registered through Koin. Keep external or side-effecting tools opt-in and
apply a restrictive `ToolExecutionPolicy` at their call site.

`ToolProviderAdapter` translates the neutral definitions into the provider wire schema. The module
includes adapters for OpenAI-compatible APIs (OpenAI, DeepSeek, OpenRouter, Ollama, Custom), Gemini,
and Claude. The current OpenAI-compatible gateway now accepts tool definitions and emits its native
`tools` payload automatically. Gemini and Claude adapters are ready for their respective API clients.
