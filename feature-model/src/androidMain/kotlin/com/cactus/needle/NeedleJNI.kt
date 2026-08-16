package com.cactus.needle

/**
 * JNI bridge to the needle2 engine (`libneedle_jni.so`, backed by `libneedle.a`).
 *
 * The C API is synchronous and session-global (one engine per process), so all
 * calls must be serialized — [NeedleLocalModelEngine] guards them with a mutex.
 * Return codes are forwarded verbatim; the engine's result JSON is written into
 * the output buffer regardless of the code, so callers parse the buffer itself.
 */
object NeedleJNI {
    init {
        System.loadLibrary("needle_jni")
    }

    /** Loads the model from its `.cact` bytes. Returns 0 on success (defensive: parse nothing). */
    external fun nativeLoad(cact: ByteArray): Int

    /** Starts a session with the given system facts and tools JSON (may be null). Returns 0 on success. */
    external fun nativeInit(systemFacts: String?, toolsJson: String?): Int

    /** Runs one completion; writes the result JSON into [out]. Returns the engine's status code. */
    external fun nativeComplete(input: String, maxNewTokens: Int, out: ByteArray): Int

    /** Rewinds the session conversation, keeping the loaded model and tools. */
    external fun nativeReset()
}
