package com.termux.suggest

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Poll-E v2 AccessibilityService.
 *
 * Stays resident so findFocus() works on-demand. The only active trigger is
 * ACTION_QUERY (sent by Keymapper on vol-down hold): reads the focused text
 * field as a query and dispatches through QueryDispatcher's shell loop.
 */
class PollEAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var worker: WorkerConnection
    private lateinit var dispatcher: QueryDispatcher

    private val queryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_QUERY) return
            val query = readQueryFromFocusedField() ?: run {
                Log.d(TAG, "ACTION_QUERY: no focused input field")
                return
            }
            if (query.isBlank()) {
                Log.d(TAG, "ACTION_QUERY: focused field is empty")
                return
            }
            Log.i(TAG, "Mode B query: ${query.take(100)}")
            scope.launch { dispatcher.handleQuery(query) }
        }
    }

    override fun onServiceConnected() {
        worker = WorkerConnection(this)
        SuggestionNotifier.init(this)
        dispatcher = QueryDispatcher(worker, this)
        registerReceiver(queryReceiver, IntentFilter(ACTION_QUERY), RECEIVER_EXPORTED)
        scope.launch { worker.start() }
        Log.i(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    private fun readQueryFromFocusedField(): String? {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return try {
            node.text?.toString()?.takeIf { it.isNotBlank() }
        } finally {
            node.recycle()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(queryReceiver) }
        SuggestionNotifier.clear(this)
        scope.cancel()
        worker.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PollE"
        const val ACTION_QUERY = "com.termux.suggest.QUERY"
    }
}
