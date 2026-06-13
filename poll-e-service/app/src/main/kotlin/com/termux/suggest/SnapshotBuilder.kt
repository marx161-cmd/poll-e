package com.termux.suggest

import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo

object SnapshotBuilder {

    fun build(context: Context, root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()

        val pkgName = root.packageName?.toString() ?: ""
        val appLabel = try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(pkgName, 0)
            ).toString()
        } catch (_: PackageManager.NameNotFoundException) { pkgName }

        sb.append("App: ").append(appLabel)

        val focused = findFocusedEditable(root)
        if (focused != null) {
            val text = focused.text?.toString() ?: ""
            val hint = focused.hintText?.toString() ?: ""
            sb.append(". Focused input")
            if (hint.isNotBlank()) sb.append(" (").append(hint.take(60)).append(")")
            sb.append(": ").append(text.takeLast(300))
        } else {
            sb.append(". No focused text field")
        }

        val ctx = collectVisibleText(root, skip = focused, maxChars = 400)
        if (ctx.isNotBlank()) sb.append(". Screen: ").append(ctx)

        return sb.toString()
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

    private fun collectVisibleText(
        root: AccessibilityNodeInfo,
        skip: AccessibilityNodeInfo?,
        maxChars: Int
    ): String {
        val parts = mutableListOf<String>()
        gatherText(root, skip, parts, maxChars)
        return parts.joinToString(" ").take(maxChars)
    }

    private fun gatherText(
        node: AccessibilityNodeInfo,
        skip: AccessibilityNodeInfo?,
        out: MutableList<String>,
        maxChars: Int
    ) {
        if (out.sumOf { it.length } >= maxChars) return
        if (node == skip) return
        val t = node.text?.toString()
        if (!t.isNullOrBlank() && t.length < 200) out.add(t)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            gatherText(child, skip, out, maxChars)
            child.recycle()
        }
    }
}
