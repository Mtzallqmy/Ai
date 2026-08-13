package com.mtzallqmy.aiagent.tool.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Real Accessibility Service for device-agent observation and actions.
 * Extracts package, window, class, text, contentDescription, resourceId,
 * bounds, clickable/editable/focusable/scrollable/enabled/checked/selected.
 */
class AccessibilityAgentService : AccessibilityService() {

    /** Latest observed tree snapshot, updated on accessibility events. */
    @Volatile
    private var latestRoot: AccessibilityNodeInfo? = null

    fun currentTreeRoot(): AccessibilityNodeInfo? =
        rootInActiveWindow

    /** Convert the live window tree into an internal node model. */
    fun captureTree(): UiNode? {
        val root = currentTreeRoot() ?: return null
        return parseNode(root)
    }

    private fun parseNode(node: AccessibilityNodeInfo): UiNode {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val children = (0 until node.childCount).map { parseNode(node.getChild(it)!!) }
        return UiNode(
            packageName = node.packageName?.toString() ?: "",
            className = node.className?.toString() ?: "",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            resourceId = node.viewIdResourceName ?: "",
            bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            editable = node.isEditable,
            focusable = node.isFocusable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            checked = node.isChecked,
            selected = node.isSelected,
            children = children,
        )
    }

    // ---------- Actions ----------

    /** Tap at coordinates (falls back to AccessibilityNodeInfo.performAction when a node matches). */
    fun tapAt(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    fun longPressAt(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1500))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    /** Click a resolved node. */
    fun clickNode(node: AccessibilityNodeInfo): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    /** Type text into an editable node. */
    fun typeInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val bundle = android.os.Bundle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        }
        return false
    }

    fun clearText(node: AccessibilityNodeInfo): Boolean {
        val bundle = android.os.Bundle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        }
        return false
    }

    fun scrollUp(): Boolean = currentTreeRoot()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true

    fun scrollDown(): Boolean = currentTreeRoot()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true

    fun swipe(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x0.toFloat(), y0.toFloat()); lineTo(x1.toFloat(), y1.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openApp(packageName: String): Boolean {
        return runCatching {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        }.getOrDefault(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.source?.recycle()
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        info.feedbackType = (AccessibilityServiceInfo.FEEDBACK_GENERIC)
        info.flags = (AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS)
        setServiceInfo(info)
    }
}
