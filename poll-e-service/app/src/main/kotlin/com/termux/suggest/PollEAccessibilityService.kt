package com.termux.suggest

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Poll-E AccessibilityService — the sensor layer.
 *
 * Lifecycle:
 *   onServiceConnected → spawn worker → wait for POLL_E_READY
 *   onAccessibilityEvent (doorbell) → debounce 300 ms → tree-walk → dedup hash
 *     → [worker].request(snapshot) → onSuggestion(text)
 *
 * Output (skeleton): broadcasts ACTION_SUGGESTION with EXTRA_TEXT.
 * Production IPC to PixelXpert fork is a TODO (signature-protected content provider).
 *
 * The worker binary needs root. Grant this APK root access in APatch before use.
 */
class PollEAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val worker = WorkerConnection()
    private var debounceJob: Job? = null
    private var requestJob: Job? = null
    private var pendingSnapshot: String? = null
    private val requestLock = Any()
    private var lastSnapshotHash = 0

    override fun onServiceConnected() {
        Log.i(TAG, "Service connected — starting worker")
        scope.launch { worker.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> scheduleSnapshot()
        }
    }

    private fun scheduleSnapshot() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            val root = rootInActiveWindow ?: return@launch
            val snapshot = try {
                SnapshotBuilder.build(this@PollEAccessibilityService, root)
            } finally {
                root.recycle()
            }
            val hash = snapshot.hashCode()
            if (hash == lastSnapshotHash) return@launch
            lastSnapshotHash = hash

            Log.d(TAG, "Snapshot: ${snapshot.take(120)}…")
            enqueueSnapshot(snapshot)
        }
    }

    private fun enqueueSnapshot(snapshot: String) {
        synchronized(requestLock) {
            if (requestJob?.isActive == true) {
                pendingSnapshot = snapshot
                Log.d(TAG, "Worker busy; keeping latest pending snapshot")
                return
            }

            requestJob = scope.launch {
                var current = snapshot
                while (true) {
                    val suggestion = worker.request(current)
                    if (suggestion != null) onSuggestion(suggestion)

                    synchronized(requestLock) {
                        val next = pendingSnapshot
                        pendingSnapshot = null
                        if (next == null) {
                            requestJob = null
                            return@launch
                        }
                        current = next
                    }
                }
            }
        }
    }

    private fun onSuggestion(text: String) {
        if (text == "NONE" || text.isBlank()) {
            Log.d(TAG, "Gate: NONE")
            return
        }
        Log.i(TAG, "Suggestion: $text")

        // Send to PixelXpert's SystemUI hook. Permission enforces only same-signed apps receive it.
        sendBroadcast(
            Intent(ACTION_SUGGESTION).apply { putExtra(EXTRA_TEXT, text) },
            PERMISSION_POLL_E_IPC
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "Interrupted")
    }

    override fun onDestroy() {
        scope.cancel()
        worker.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PollE"
        private const val DEBOUNCE_MS = 300L

        const val ACTION_SUGGESTION = "com.termux.suggest.SUGGESTION"
        const val EXTRA_TEXT = "text"
        const val PERMISSION_POLL_E_IPC = "com.termux.suggest.permission.POLL_E_IPC"
    }
}
