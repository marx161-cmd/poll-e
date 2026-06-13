package com.termux.suggest

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * Manages the lifecycle of the native litert_lm_main worker process and the
 * line-based stdin/stdout protocol it speaks.
 *
 * Startup: spawns `su -c "LD_LIBRARY_PATH=... litert_lm_main --poll_e_worker ..."`
 * and blocks until "POLL_E_READY" appears on stdout.
 *
 * Per request: writes one snapshot line to stdin, reads until "POLL_E_END".
 *
 * The caller is responsible for calling [start] once and [stop] on teardown.
 * If the process dies mid-session [request] returns null; the caller should
 * drop that snapshot and let the next accessibility event trigger a new attempt.
 */
class WorkerConnection {

    private var process: Process? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    @Volatile var isReady = false
        private set

    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            val su = resolveSu()
            val cmd = "$su -c " +
                "\"LD_LIBRARY_PATH=$LIB_DIR $BINARY" +
                " --backend=npu" +
                " --model_path=$MODEL_PATH" +
                " --poll_e_worker" +
                " --poll_e_max_output_tokens=$MAX_TOKENS\""

            Log.i(TAG, "Spawning: $cmd")
            process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(false)
                .start()

            // Drain stderr to logcat so it doesn't block the pipe.
            val errStream = process!!.errorStream
            Thread {
                BufferedReader(InputStreamReader(errStream)).forEachLine { line ->
                    Log.v(TAG_ERR, line)
                }
            }.also { it.isDaemon = true }.start()

            writer = PrintWriter(process!!.outputStream.bufferedWriter())
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            // Block until POLL_E_READY (contains check handles the
            // "SouthBound...POLL_E_READY" merged line from libedgetpu).
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

    suspend fun request(snapshot: String): String? = withContext(Dispatchers.IO) {
        if (!isReady || process?.isAlive != true) {
            Log.w(TAG, "Worker not ready, dropping snapshot")
            return@withContext null
        }
        try {
            writer!!.println(snapshot)
            writer!!.flush()

            val sb = StringBuilder()
            var inBlock = false
            var line: String?
            while (reader!!.readLine().also { line = it } != null) {
                when {
                    line!!.contains("POLL_E_BEGIN") -> { inBlock = true; sb.clear() }
                    line!!.contains("POLL_E_END") && inBlock -> return@withContext sb.toString().trim()
                    inBlock -> { if (sb.isNotEmpty()) sb.append('\n'); sb.append(line) }
                }
            }
            Log.w(TAG, "Stdout closed before POLL_E_END")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Request failed", e)
            isReady = false
            null
        }
    }

    fun stop() {
        isReady = false
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        process?.destroyForcibly()
        process = null
    }

    private fun resolveSu(): String = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su"
    ).firstOrNull { File(it).exists() } ?: "su"

    companion object {
        private const val TAG = "PollE/Worker"
        private const val TAG_ERR = "PollE/Worker-err"

        // Paths on device — adjust if staged elsewhere.
        private const val LIB_DIR = "/data/local/tmp/poll-e-worker-test"
        private const val BINARY = "/data/local/tmp/poll-e-worker-test/litert_lm_main"
        private const val MODEL_PATH = "/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm"
        private const val MAX_TOKENS = 48
    }
}
