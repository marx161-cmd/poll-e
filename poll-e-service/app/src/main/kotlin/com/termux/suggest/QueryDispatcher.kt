package com.termux.suggest

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class QueryDispatcher(
    private val worker: WorkerConnection,
    private val context: Context
) {

    suspend fun handleQuery(query: String) {
        Log.i(TAG, "Query: ${query.take(100)}")
        val answer = shellLoop(query)
        if (answer != null) {
            Log.i(TAG, "Answer: ${answer.take(200)}")
            SuggestionNotifier.postAnswer(context, answer)
        } else {
            Log.w(TAG, "No answer produced for: ${query.take(80)}")
        }
    }

    private suspend fun shellLoop(query: String): String? {
        val history = StringBuilder()

        for (round in 1..MAX_ROUNDS) {
            val forceAnswer = round == MAX_ROUNDS
            val prompt = buildPrompt(query, history.toString(), round, forceAnswer)
            val response = worker.request(prompt) ?: return null
            Log.d(TAG, "Round $round: ${response.take(200)}")

            val answer = extractAnswer(response)
            if (answer != null) return answer

            if (forceAnswer) {
                return response.trim().takeIf { it.isNotBlank() }
            }

            val command = response.trim()
            if (command.isBlank()) return null

            val output = runAllowlistedCommand(command)
            if (output == null) {
                history.append("\n[Rejected — not in allowlist: $command]")
                history.append("\nRun a valid command or reply: ANSWER: {your answer}")
                continue
            }

            history.append("\n$ ").append(command)
            history.append("\n").append(output.take(MAX_OUTPUT_CHARS))

            if (history.length > MAX_HISTORY_CHARS) {
                val truncated = history.substring(history.length - MAX_HISTORY_CHARS)
                history.clear()
                history.append("[...truncated...]\n").append(truncated)
            }
        }
        return null
    }

    private fun extractAnswer(response: String): String? {
        val match = ANSWER_REGEX.find(response.trim()) ?: return null
        val answer = match.groupValues[1].trim()
        return answer.takeIf { it.isNotBlank() }
    }

    private fun buildPrompt(query: String, history: String, round: Int, forceAnswer: Boolean): String {
        val historySection = if (history.isNotBlank()) "\n\nHistory:$history" else ""
        val instruction = if (forceAnswer) {
            "Round $round/$MAX_ROUNDS (final). You must answer now. Reply with exactly: ANSWER: {your answer}"
        } else {
            "Round $round/$MAX_ROUNDS. Reply with ONE shell command, or to answer: ANSWER: {your answer}"
        }
        return "Shell assistant on rooted Android device.\n" +
            "Query: $query$historySection\n\n" +
            "Available commands:\n" +
            "  ls [path]  cat [path]  rg [pattern] [path]  find [path] [args]  semantic_search [query]\n\n" +
            instruction
    }

    private suspend fun runAllowlistedCommand(command: String): String? {
        val binary = command.trim().split(Regex("\\s+")).firstOrNull() ?: return null
        if (binary == "semantic_search") return semanticSearch(command)
        if (binary !in ALLOWED_BINARIES) {
            Log.w(TAG, "Rejected binary: $binary")
            return null
        }

        val sanitized = command.replace(SHELL_UNSAFE_REGEX, "")
        val safeCommand = shellQuoteCommand(sanitized)

        return withTimeoutOrNull(CMD_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                try {
                    val su = resolveSu()
                    val proc = ProcessBuilder(
                        su, "-c",
                        "PATH=$TERMUX_PATH $safeCommand"
                    ).redirectErrorStream(true).start()
                    val output = BufferedReader(InputStreamReader(proc.inputStream))
                        .readText().take(MAX_OUTPUT_CHARS)
                    proc.waitFor()
                    output.ifBlank { "(no output)" }
                } catch (e: Exception) {
                    Log.e(TAG, "Exec failed: $sanitized", e)
                    "Error: ${e.message}"
                }
            }
        } ?: "Command timed out after ${CMD_TIMEOUT_MS / 1000}s"
    }

    private fun shellQuoteCommand(sanitized: String): String {
        val parts = sanitized.split(Regex("\\s+"))
        return parts.joinToString(" ") { arg ->
            if (arg.isNotEmpty() && arg.any { it in SHELL_SPECIAL }) "'$arg'" else arg
        }
    }

    private suspend fun semanticSearch(command: String): String {
        val query = command.removePrefix("semantic_search").trim()
        if (query.isBlank()) return "semantic_search requires a query argument, e.g. semantic_search how to set up NVMe"

        return withTimeoutOrNull(SEMANTIC_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                try {
                    val url = URL(SEMANTIC_URL)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 3_000
                    conn.readTimeout = 5_000

                    val payload = """{"query":${JSONObject.quote(query)},"top_k":5,"min_score":0.1}"""
                    conn.outputStream.use { it.write(payload.toByteArray()) }

                    if (conn.responseCode != 200) {
                        val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                        return@withContext "semantic_search error: $err"
                    }

                    val response = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    if (!json.optBoolean("ok", false)) {
                        return@withContext "semantic_search error: ${json.optString("error", "unknown")}"
                    }

                    val results = json.optJSONArray("results")
                        ?: return@withContext "semantic_search: no results"
                    if (results.length() == 0) return@withContext "semantic_search: no results matched"

                    val sb = StringBuilder()
                    for (i in 0 until minOf(results.length(), 5)) {
                        val item = results.getJSONObject(i)
                        val score = "%.3f".format(item.optDouble("score", 0.0))
                        val text = item.optString("text", "").take(300)
                        val meta = item.optJSONObject("metadata")
                        val source = meta?.optString("source", "") ?: ""
                        if (source.isNotBlank()) sb.append("[$score] $source\n")
                        else sb.append("[$score]\n")
                        sb.append(text)
                        if (i < results.length() - 1) sb.append("\n\n")
                    }
                    sb.toString()
                } catch (e: Exception) {
                    Log.e(TAG, "semantic_search failed", e)
                    "semantic_search error: ${e.message}"
                }
            }
        } ?: "semantic_search timed out after ${SEMANTIC_TIMEOUT_MS / 1000}s"
    }

    private fun resolveSu(): String = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su"
    ).firstOrNull { File(it).exists() } ?: "su"

    companion object {
        private const val TAG = "PollE/Dispatcher"
        private const val MAX_ROUNDS = 10
        private const val MAX_OUTPUT_CHARS = 2_000
        private const val MAX_HISTORY_CHARS = 6_000
        private const val CMD_TIMEOUT_MS = 5_000L
        private const val SEMANTIC_TIMEOUT_MS = 8_000L
        private const val TERMUX_PATH =
            "/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin"
        private const val SEMANTIC_URL = "http://127.0.0.1:8791/query"

        private val SHELL_UNSAFE_REGEX = Regex("[;&|`\$()><\n#\"']")
        private val SHELL_SPECIAL = setOf('\\', '*', '?', '[', ']', '{', '}', '~', '!', ' ')
        private val ANSWER_REGEX = Regex(
            """(?i)^\s*ANSWER\s*:\s*(.+)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val ALLOWED_BINARIES = setOf("ls", "cat", "rg", "find")
    }
}
