package ai.fatai.ai

/** Opens a user-selected attachment with the operating system's associated application. */
expect fun openFileWithSystemApplication(localPath: String, mimeType: String): Boolean
