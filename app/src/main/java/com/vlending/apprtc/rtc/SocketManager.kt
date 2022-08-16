package com.vlending.apprtc.rtc

import android.util.Log
import com.vlending.apprtc.model.SocketState
import de.tavendo.autobahn.WebSocket
import de.tavendo.autobahn.WebSocketConnection
import org.json.JSONObject
import java.net.URI

class SocketManager(private val observer: WebSocket.WebSocketConnectionObserver) {

    var state = SocketState.NEW

    private val socket = WebSocketConnection()
    private val sendQueue = mutableListOf<String>()
    private var wssServerUrl = ""
    private var wssPostServerUrl = ""
    private var roomId = ""
    private var clientId = ""
    private var isInitiator = false

    fun connect(wssUrl: String, wssPostUrl: String, initiator: Boolean) {
        wssServerUrl = wssUrl
        wssPostServerUrl = wssPostUrl
        isInitiator = initiator

        if (state != SocketState.NEW) {
            Log.d(TAG, "Socket is already connected.")
            return
        }

        socket.connect(URI(wssUrl), observer)
    }

    fun register(room: String, client: String) {
        roomId = room
        clientId = client

        if (state != SocketState.CONNECTED) {
            Log.d(TAG, "Socket is already connected")
            return
        }

        val json = JSONObject().apply {
            put("cmd", "register")
            put("roomid", room)
            put("clientid", client)
        }

        socket.sendTextMessage(json.toString())
        state = SocketState.REGISTERED

        for (message in sendQueue) {
            send(message)
        }

        sendQueue.clear()
    }

    fun disconnect() {
        state = SocketState.CLOSED
        val json = JSONObject().put("type", "bye")
        send(json.toString())
    }

    fun send(message: String) {
        Log.d(TAG, "send() called with: message = [$message]")
        when (state) {
            SocketState.CONNECTED -> {
                sendQueue.add(message)
            }
            SocketState.REGISTERED -> {
                val json = JSONObject().apply {
                    put("cmd", "send")
                    put("msg", message)
                }
                socket.sendTextMessage(json.toString())
            }
            SocketState.CLOSED -> {
                socket.sendTextMessage(message)
                socket.disconnect()
            }
            else -> Unit
        }
    }


    companion object {
        private const val TAG = "SocketManager"
    }
}