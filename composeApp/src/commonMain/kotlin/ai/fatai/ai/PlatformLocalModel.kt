package ai.fatai.ai

/**
 * Whether this platform ships an embedded local model engine (the needle2 runtime is
 * Android-only). Used by the settings screen to show the embedded/http engine-mode
 * dropdown only where embedded mode can actually run.
 */
expect fun supportsEmbeddedLocalModel(): Boolean
