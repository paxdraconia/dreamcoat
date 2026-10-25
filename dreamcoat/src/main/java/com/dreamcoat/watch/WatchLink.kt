package com.dreamcoat.watch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

/**
 * Phone end of the Pixel Watch remote. The watch app sends gesture names over the Wearable
 * Data Layer; we push back "laptop|mode" so it can show status. Both apps share the package
 * name and signing key, which the Data Layer requires.
 */
class WatchLink(context: Context) : MessageClient.OnMessageReceivedListener {

    enum class Gesture { NEXT, BACK, SELECT, MODE }

    var onGesture: ((Gesture) -> Unit)? = null
    var onWatchChanged: ((String?) -> Unit)? = null
    /** Supplies the current "laptop|mode" status line. */
    var stateProvider: (() -> String)? = null

    /** Display name of the watch we last heard from, or null. */
    var watchName: String? = null
        private set

    private val messages = Wearable.getMessageClient(context)
    private val nodes = Wearable.getNodeClient(context)

    fun start() {
        messages.addListener(this)
        refreshNodes()
    }

    fun stop() {
        messages.removeListener(this)
    }

    override fun onMessageReceived(e: MessageEvent) {
        when (e.path) {
            PATH_HELLO -> {
                refreshNodes()
                sendState()
            }
            PATH_GESTURE -> {
                val g = runCatching { Gesture.valueOf(String(e.data)) }.getOrNull() ?: return
                Log.i(TAG, "watch gesture $g")
                onGesture?.invoke(g)
            }
        }
    }

    /** Pushes status to every connected watch. Cheap; call on every state change. */
    fun sendState() {
        val payload = (stateProvider?.invoke() ?: return).toByteArray()
        nodes.connectedNodes.addOnSuccessListener { list ->
            list.forEach { messages.sendMessage(it.id, PATH_STATE, payload) }
        }
    }

    /** Tells the watch app to close (phone Quit). */
    fun sendQuit() {
        nodes.connectedNodes.addOnSuccessListener { list ->
            list.forEach { messages.sendMessage(it.id, PATH_QUIT, ByteArray(0)) }
        }
    }

    private fun refreshNodes() {
        nodes.connectedNodes.addOnSuccessListener { list ->
            val name = list.firstOrNull()?.displayName
            if (name != watchName) {
                watchName = name
                onWatchChanged?.invoke(name)
            }
        }
    }

    companion object {
        private const val TAG = "WatchLink"
        const val PATH_HELLO = "/dreamcoat/hello"
        const val PATH_GESTURE = "/dreamcoat/gesture"
        const val PATH_STATE = "/dreamcoat/state"
        const val PATH_QUIT = "/dreamcoat/quit"
    }
}
