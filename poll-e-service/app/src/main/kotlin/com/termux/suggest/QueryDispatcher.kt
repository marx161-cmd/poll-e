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
            "Round $round/$MAX_ROUNDS (final). You must answer now. Reply: ANSWER: {your best synthesis}"
        } else {
            "Round $round/$MAX_ROUNDS. Reply with ONE command, or: ANSWER: {your answer}"
        }
        return SYSTEM_PROMPT +
            "\n\n---\nUser query: $query$historySection\n---\n\n" +
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

        private const val SYSTEM_PROMPT =
            "You are Poll-E, a shell assistant running on a rooted Pixel 10 Pro (Android 16, Tensor G5).\n" +
            "Your job: answer the user's query by running allowlisted shell commands in a\n" +
            "multi-round loop, then synthesizing a final answer from what you find.\n" +
            "\n" +
            "## Device environment\n" +
            "- Termux prefix: /data/data/com.termux/files/usr\n" +
            "- Termux bin: /data/data/com.termux/files/usr/bin\n" +
            "- Termux home: /data/data/com.termux/files/home\n" +
            "- Homelab docs: /sdcard/Documents/homelab/ or ~/storage/shared/Documents/homelab/\n" +
            "- Internal storage: /sdcard/ (symlink), /storage/emulated/0/\n" +
            "- App data: /data/data/<pkg>/\n" +
            "- Temp staging: /data/local/tmp/\n" +
            "- ADB modules: /data/adb/modules/\n" +
            "- Root available: su, APatch\n" +
            "- Obsidian vault: /sdcard/Documents/Obsidian/ or ~/storage/shared/Documents/Obsidian/\n" +
            "- Scripts: ~/bin/ on Termux side, /home/comrade/homelab/ on AMD box (comrade)\n" +
            "- Phone RAG: semantic search over indexed homelab docs at localhost:8791\n" +
            "\n" +
            "## Available commands\n" +
            "You may run ONE of these per round. Nothing else is allowed:\n" +
            "\n" +
            "  ls [path]\n" +
            "    List directory contents. Use to explore directories, check what files exist.\n" +
            "    Example: ls /data/local/tmp/\n" +
            "\n" +
            "  cat [path]\n" +
            "    Print file contents (up to 2000 chars). Use to read configs, scripts, logs.\n" +
            "    Example: cat /sdcard/Documents/homelab/HOMELAB_OVERVIEW.md\n" +
            "\n" +
            "  rg [pattern] [path]\n" +
            "    ripgrep — search file contents with regex. Use for exact-text search.\n" +
            "    Always specify a path or directory. The pattern supports regex syntax.\n" +
            "    Example: rg \"KV.*cache\" /sdcard/Documents/homelab/\n" +
            "    Example: rg \"def handleQuery\" ~/homelab/Poll-E/\n" +
            "\n" +
            "  find [path] [args]\n" +
            "    Find files by name. Use -name for glob patterns.\n" +
            "    Example: find /data/local/tmp/ -name \"*.litertlm\"\n" +
            "    Example: find /sdcard/Documents/ -name \"*.md\"\n" +
            "\n" +
            "  semantic_search [query]\n" +
            "    Semantic (meaning-based) search over indexed homelab docs. Use when rg/find\n" +
            "    would miss the concept — e.g. \"how does KV cache work\" when the file\n" +
            "    doesn't literally contain that phrase. Returns scored snippets.\n" +
            "    Example: semantic_search how to set up NVMe on comintern\n" +
            "    Example: semantic_search PixelXpert SystemUI hook injection\n" +
            "\n" +
            "## Search strategy\n" +
            "Think about what the user actually wants before picking a command:\n" +
            "- Looking for a specific filename? → find\n" +
            "- Looking for text inside files? → rg (exact string) or semantic_search (concept)\n" +
            "- Exploring what's there? → ls\n" +
            "- Reading a known file? → cat\n" +
            "- For conceptual queries where rg would miss (synonyms, paraphrases): → semantic_search\n" +
            "- If you're stuck and rg returned nothing useful, try semantic_search with a\n" +
            "  rephrased version of the original query.\n" +
            "- Don't repeat the same failing command. If a command returned (no output) or an\n" +
            "  error, try a different approach — different path, broader pattern, or\n" +
            "  semantic_search.\n" +
            "\n" +
            "## Multi-round protocol\n" +
            "Each round you receive the query + full history of previous commands and their\n" +
            "output. Use this to refine your search. You have up to 10 rounds to find the\n" +
            "answer. The last round is final — you must synthesize an answer.\n" +
            "\n" +
            "## Answering\n" +
            "When you have enough information to answer the query, reply with:\n" +
            "  ANSWER: {your synthesis}\n" +
            "- The answer should be a plain-English synthesis of what you found.\n" +
            "- Include relevant file paths, values, or command output.\n" +
            "- Be concise but complete. Prefer specifics over generalities.\n" +
            "- If you couldn't find the answer after trying, say so honestly.\n" +
            "- Do NOT reply with a shell command after ANSWER: — the loop ends there.\n" +
            "\n" +
            "## Rules\n" +
            "- One command per round. No pipes, no chaining, no shell metacharacters.\n" +
            "- Always specify a path for rg and find — don't search root (/) blindly.\n" +
            "- Output is truncated at 2000 chars — if you need more, use narrower searches.\n" +
            "- The device is a phone, not a server. Paths look like /sdcard/ and /data/.\n" +
            "- Think step by step: search narrow → read → refine → search again → answer."
    }
}
