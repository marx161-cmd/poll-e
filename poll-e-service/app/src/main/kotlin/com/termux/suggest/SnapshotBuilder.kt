package com.termux.suggest

import android.view.accessibility.AccessibilityNodeInfo

object SnapshotBuilder {

    fun readFocusedText(root: AccessibilityNodeInfo): String? {
        val node = findFocusedEditable(root) ?: return null
        return try {
            node.text?.toString()?.takeIf { it.isNotBlank() }
        } finally {
            if (node !== root) node.recycle()
        }
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && (node.isFocused || node.isAccessibilityFocused)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child)
            if (result != null) {
                if (result !== child) child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }
}
