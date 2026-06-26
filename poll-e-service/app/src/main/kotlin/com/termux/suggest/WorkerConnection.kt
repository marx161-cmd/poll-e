package com.termux.suggest

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * Manages the lifecycle of the native litert_lm_main worker process.
 *
 * Protocol: write one encoded prompt line to stdin; read until POLL_E_END.
 * Newlines in prompts are encoded as literal \n before sending, decoded by
 * BuildPollEPrompt on the binary side.
 *
 * The worker binary and NPU dispatch libraries are packaged as jniLibs and
 * extracted to the app's nativeLibraryDir by the package manager.  This keeps
 * execution in the app's own SELinux/linker namespace (same approach PhoneRAG
 * uses for EmbeddingGemma).
 */
class WorkerConnection(private val context: Context) {

    private var process: Process? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val requestMutex = Mutex()

    @Volatile var isReady = false
        private set

    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val binary = "$nativeLibDir/libpoll_e_worker.so"

            Log.i(TAG, "Spawning worker from $binary")
            val pb = ProcessBuilder(
                binary,
                "--backend=npu",
                "--litert_dispatch_lib_dir=$nativeLibDir",
                "--model_path=$MODEL_PATH",
                "--poll_e_worker",
                "--poll_e_max_output_tokens=$MAX_TOKENS"
            )
            pb.environment()["LD_LIBRARY_PATH"] = "$nativeLibDir:/vendor/lib64"
            pb.redirectErrorStream(false)
            process = pb.start()

            val errStream = process!!.errorStream
            Thread {
                BufferedReader(InputStreamReader(errStream)).forEachLine { line ->
                    Log.v(TAG_ERR, line)
                }
            }.also { it.isDaemon = true }.start()

            writer = PrintWriter(process!!.outputStream.bufferedWriter())
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            var line: String?
            while (reader!!.readLine().also { line = it } != null) {
                Log.d(TAG, "startup: $line")
                if (line!!.contains("POLL_E_READY")) {
                    isReady = true
                    Log.i(TAG, "Worker ready")
                    break
                }
            }
            if (!isReady) Log.e(TAG, "Process exited before POLL_E_READY")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start worker", e)
        }
    }

    suspend fun request(prompt: String, timeoutMs: Long = 15_000L): String? = requestMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isReady || process?.isAlive != true) {
                Log.w(TAG, "Worker not ready")
                return@withContext null
            }
            try {
                val encoded = prompt.take(MAX_PROMPT_CHARS).replace("\n", "\\n")
                writer!!.println(encoded)
                writer!!.flush()

                try {
                    withTimeout(timeoutMs) {
                        val sb = StringBuilder()
                        var inBlock = false
                        var line: String?
                        while (reader!!.readLine().also { line = it } != null) {
                            when {
                                line!!.contains("POLL_E_BEGIN") -> { inBlock = true; sb.clear() }
                                line!!.contains("POLL_E_END") && inBlock -> return@withTimeout sb.toString().trim()
                                inBlock -> { if (sb.isNotEmpty()) sb.append('\n'); sb.append(line) }
                            }
                        }
                        Log.w(TAG, "Stdout closed before POLL_E_END")
                        null
                    }
                } catch (_: TimeoutCancellationException) {
                    Log.e(TAG, "Request timed out after ${timeoutMs}ms")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Request failed", e)
                isReady = false
                null
            }
        }
    }

    fun stop() {
        isReady = false
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        process?.destroyForcibly()
        process = null
    }

    companion object {
        private const val TAG = "PollE/Worker"
        private const val TAG_ERR = "PollE/Worker-err"

        private const val MODEL_PATH = "/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm"
        private const val MAX_TOKENS = 200
        private const val MAX_PROMPT_CHARS = 10_000
    }
}
