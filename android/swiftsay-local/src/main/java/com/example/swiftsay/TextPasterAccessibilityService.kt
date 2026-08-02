package com.example.swiftsay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class TextPasterAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TextPasterA11yService"
        const val ACTION_PASTE_BROADCAST = "com.example.swiftsay.PASTE_TEXT"
        const val EXTRA_TEXT_TO_PASTE = "TEXT_TO_PASTE"
        @Volatile
        private var activeInstance: TextPasterAccessibilityService? = null

        fun getInstance(): TextPasterAccessibilityService? = activeInstance
    }

    private val pasteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "========== BROADCAST RECEIVED ==========")
            Log.d(TAG, "Broadcast received! Action: ${intent?.action}")

            if (intent?.action == ACTION_PASTE_BROADCAST) {
                val text = intent.getStringExtra(EXTRA_TEXT_TO_PASTE)
                Log.d(TAG, "Text extracted from intent: '$text'")
                Log.d(TAG, "Text length: ${text?.length}")
                Log.d(TAG, "Text hashcode: ${text?.hashCode()}")

                if (text.isNullOrBlank()) {
                    Log.w(TAG, "Received blank text")
                    return
                }

                pasteTextDirect(text)
            } else {
                Log.w(TAG, "Unexpected action: ${intent?.action}")
            }
            Log.d(TAG, "========================================")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected!")
        activeInstance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        this.serviceInfo = info

        // Register the broadcast receiver
        val filter = IntentFilter(ACTION_PASTE_BROADCAST)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pasteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(pasteReceiver, filter)
            }
            Log.d(TAG, "Broadcast receiver registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver", e)
        }

        Toast.makeText(this, "Text Paster Accessibility Service Active", Toast.LENGTH_LONG).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Log events for debugging
        if (event != null) {
            Log.v(TAG, "Event: ${event.eventType}, Class: ${event.className}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        if (activeInstance === this) {
            activeInstance = null
        }
        try {
            unregisterReceiver(pasteReceiver)
            Log.d(TAG, "Receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    fun pasteTextDirect(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "Direct paste received blank text")
            return
        }

        Log.d(TAG, "========== DIRECT PASTE ==========")
        Log.d(TAG, "Direct paste received: '${text.take(200)}'")
        android.os.Handler(mainLooper).post {
            pasteTextIntoFocusedField(text)
        }
    }

    private fun pasteTextIntoFocusedField(text: String) {
        Log.d(TAG, "========== PASTE REQUEST ==========")
        Log.d(TAG, "Attempting to paste text: '$text'")

        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "No active window root found")
            copyToClipboardAndNotify(text, "No active window. Copied to clipboard.")
            return
        }

        Log.d(TAG, "Root window found")

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null) {
            Log.w(TAG, "No focused input field")
            copyToClipboardAndNotify(text, "No focused input. Copied to clipboard.")
            return
        }

        Log.d(TAG, "Focused node found: ${focused.className}, editable: ${focused.isEditable}")

        if (!focused.isEditable) {
            Log.w(TAG, "Focused node is not editable")
            focused.recycle()
            copyToClipboardAndNotify(text, "Field not editable. Copied to clipboard.")
            return
        }

        val setTextArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setTextSuccess = try {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SET_TEXT failed", e)
            false
        }

        if (setTextSuccess) {
            Log.d(TAG, "ACTION_SET_TEXT result: true")
            Toast.makeText(this, "✅ Pasted: ${text.take(50)}", Toast.LENGTH_SHORT).show()
            focused.recycle()
            Log.d(TAG, "========== PASTE COMPLETE ==========")
            return
        }

        Log.w(TAG, "ACTION_SET_TEXT failed, falling back to clipboard paste")

        // Set clipboard to our text
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Transcribed text", text)
        cm.setPrimaryClip(clip)

        Log.d(TAG, "Clipboard set to: '$text'")

        // Wait a moment and verify clipboard multiple times
        android.os.Handler(mainLooper).postDelayed({
            // Double-check clipboard
            val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString()
            Log.d(TAG, "Clipboard verification (attempt 1): '$clipText'")

            if (clipText == text) {
                // Clipboard is correct, paste now
                val pasteSuccess = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d(TAG, "ACTION_PASTE result: $pasteSuccess")

                if (pasteSuccess) {
                    Toast.makeText(this, "✅ Pasted: ${text.take(50)}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "⚠️ Paste action failed - text in clipboard", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Clipboard still wrong, try setting again
                Log.w(TAG, "Clipboard mismatch! Expected '$text', got '$clipText'. Trying again...")
                cm.setPrimaryClip(clip)

                android.os.Handler(mainLooper).postDelayed({
                    val clipText2 = cm.primaryClip?.getItemAt(0)?.text?.toString()
                    Log.d(TAG, "Clipboard verification (attempt 2): '$clipText2'")

                    val pasteSuccess = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    Log.d(TAG, "ACTION_PASTE result: $pasteSuccess")

                    if (pasteSuccess) {
                        Toast.makeText(this, "✅ Pasted: ${text.take(50)}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "⚠️ Paste action failed", Toast.LENGTH_SHORT).show()
                    }

                    focused.recycle()
                }, 100)
                return@postDelayed
            }

            focused.recycle()
            Log.d(TAG, "========== PASTE COMPLETE ==========")
        }, 100) // Increased delay to 100ms
    }

    private fun copyToClipboardAndNotify(text: String, message: String) {
        copyToClipboard(text)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Transcribed text", text)
            cm.setPrimaryClip(clip)

            // Force the clipboard manager to acknowledge the change
            cm.addPrimaryClipChangedListener {
                Log.d(TAG, "Clipboard change listener fired")
            }

            Log.d(TAG, "Text copied to clipboard")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }
}
