package com.vlending.apprtc.view

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.vlending.apprtc.databinding.ActivityCallBinding
import com.vlending.apprtc.di.Injector
import com.vlending.apprtc.model.RoomState
import com.vlending.apprtc.model.SocketState
import com.vlending.apprtc.rtc.PeerManager
import com.vlending.apprtc.rtc.SocketManager
import com.vlending.apprtc.util.PermissionUtil
import de.tavendo.autobahn.WebSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.PeerConnection.IceServer

class CallActivity : PeerManager.PeerConnectionEvents, PeerConnection.Observer, WebSocket.WebSocketConnectionObserver, BaseActivity<ActivityCallBinding>({ ActivityCallBinding.inflate(it) }) {

    private lateinit var peerManager: PeerManager
    private lateinit var socketManager: SocketManager

    private var roomState = RoomState.NEW

    private val service = Injector.getService()

    private val roomData = MutableStateFlow<JSONObject?>(null)

    private val iceServersFlow = MutableStateFlow<List<IceServer>?>(null)

    private lateinit var roomId: String
    private lateinit var clientId: String
    private lateinit var wssUrl: String
    private lateinit var wssPostUrl: String
    private var isInitiator = false

    private val iceCandidates = mutableListOf<IceCandidate>()
    private val iceServers = mutableListOf<IceServer>()

    private var offerSdp: SessionDescription? = null

    init {
        lifecycleScope.launchWhenResumed {
            roomData.filterNotNull().collect(::onRoomDataCollected)
        }
        lifecycleScope.launchWhenResumed {
            iceServersFlow.filterNotNull().collect(::onIceServersCollected)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!PermissionUtil.checkPermission(this)) {
            Toast.makeText(this, "PERMISSION IS NOT GRANTED", Toast.LENGTH_SHORT).show()
        }

        val room = intent.getStringExtra("room") ?: ""

        peerManager = PeerManager(this, this, this).apply {
            initSurfaceViewRenderer(binding.callingLocal, binding.callingRemote)
        }
        socketManager = SocketManager(this)

        getRoomData(room)
    }

    private fun getRoomData(room: String) {
        lifecycleScope.launch {
            val response = JSONObject(service.connectToRoom(room))

            if (response.getString("result") == "SUCCESS") {
                val params = JSONObject(response.getString("params"))
                roomData.emit(params)
            }
        }
    }

    private fun onRoomDataCollected(roomData: JSONObject) {
        roomState = RoomState.CONNECTED

        roomId = roomData.getString("room_id")
        clientId = roomData.getString("client_id")
        wssUrl = roomData.getString("wss_url")
        wssPostUrl = roomData.getString("wss_post_url")
        isInitiator = roomData.getBoolean("is_initiator")
        peerManager.isInitiator = isInitiator

        if (!isInitiator) {
            val messages = JSONArray(roomData.getString("messages"))

            for (i in 0 until messages.length()) {
                val message = JSONObject(messages.getString(i))
                when (val type = message.getString("type")) {
                    "offer" -> {
                        offerSdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type), message.getString("sdp")
                        )
                    }
                    "candidate" -> {
                        val iceCandidate = IceCandidate(
                            message.getString("id"), message.getInt("label"), message.getString("candidate")
                        )
                        iceCandidates.add(iceCandidate)
                    }
                    else -> {
                        Log.d(TAG, "Unknown message: $type")
                    }
                }
            }
        }

        iceServers.addAll(iceServersFromPCConfigJSON(roomData.getString("pc_config")))

        var isTurnPresent = false

        for (server in iceServers) {
            for (url in server.urls) {
                if (url.startsWith("turn:")) {
                    isTurnPresent = true
                    break
                }
            }
        }

        lifecycleScope.launch {
            if (!isTurnPresent && roomData.optString("ice_server_url").isNotEmpty()) {
                val url = roomData.getString("ice_server_url").split("iceconfig").first()
                requestIceServers(url)
            } else {
                iceServersFlow.emit(emptyList())
            }
        }
    }

    private fun onIceServersCollected(iceServerList: List<IceServer>) {
        iceServers.addAll(iceServerList)

        runOnUiThread {
            connectToRoomInternal()

            socketManager.connect(wssUrl, wssPostUrl, isInitiator)
            socketManager.register(roomId, clientId)
        }
    }

    private fun connectToRoomInternal() {
        peerManager.createPeerConnection(iceServers)

        if (isInitiator) {
            peerManager.createOffer()
        } else {
            offerSdp?.let { peerManager.setRemoteDescription(it) }
            peerManager.createAnswer()
        }

        for (iceCandidate in iceCandidates) {
            peerManager.addRemoteIceCandidate(iceCandidate)
        }
    }

    private suspend fun requestIceServers(url: String) {
        val service = Injector.getIceService(url)

        val response = JSONObject(service.getIceServer())
        val iceServers = response.getJSONArray("iceServers")

        val turnServers = mutableListOf<IceServer>()

        for (i in 0 until iceServers.length()) {
            val server = iceServers.getJSONObject(i)
            val turnUrls = server.getJSONArray("urls")
            val userName = if (server.has("username")) server.getString("username") else ""
            val credential = if (server.has("credential")) server.getString("credential") else ""

            for (j in 0 until turnUrls.length()) {
                val turnUrl = turnUrls.getString(j)
                val turnServer = IceServer
                    .builder(turnUrl)
                    .setUsername(userName)
                    .setPassword(credential)
                    .createIceServer()

                turnServers.add(turnServer)
            }
        }

        iceServersFlow.emit(turnServers)
    }

    private fun iceServersFromPCConfigJSON(pcConfig: String): List<IceServer> {
        val json = JSONObject(pcConfig)
        val servers = json.getJSONArray("iceServers")
        val ret: MutableList<IceServer> = ArrayList()
        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            val url = server.getString("urls")
            val credential = if (server.has("credential")) server.getString("credential") else ""
            val turnServer = IceServer.builder(url)
                .setPassword(credential)
                .createIceServer()
            ret.add(turnServer)
        }
        return ret
    }

    /* PeerConnection */
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
        Log.d(TAG, "onSignalingChange() called with: p0 = [$p0]")
    }

    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
        Log.d(TAG, "onIceConnectionChange() called with: p0 = [$p0]")
    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {
        Log.d(TAG, "onIceConnectionReceivingChange() called with: p0 = [$p0]")
    }

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
        Log.d(TAG, "onIceGatheringChange() called with: p0 = [$p0]")
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        Log.d(TAG, "onIceCandidate() called with: candidate = [$candidate]")

        runOnUiThread {
            val json = JSONObject().apply {
                put("type", "candidate")
                put("label", candidate.sdpMLineIndex)
                put("id", candidate.sdpMid)
                put("candidate", candidate.sdp)
            }
            if (isInitiator) {
                if (roomState != RoomState.CONNECTED) {
                    Log.d(TAG, "RoomState is not connected")
                    return@runOnUiThread
                }

                lifecycleScope.launch {
                    service.sendOffer(roomId, clientId, json.toString())
                }
            } else {
                socketManager.send(json.toString())
            }
        }
    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
        Log.d(TAG, "onIceCandidatesRemoved() called with: p0 = [$p0]")
    }

    override fun onAddStream(p0: MediaStream?) {
        Log.d(TAG, "onAddStream() called with: p0 = [$p0]")
    }

    override fun onRemoveStream(p0: MediaStream?) {
        Log.d(TAG, "onRemoveStream() called with: p0 = [$p0]")
    }

    override fun onDataChannel(p0: DataChannel?) {
        Log.d(TAG, "onDataChannel() called with: p0 = [$p0]")
    }

    override fun onRenegotiationNeeded() {
        Log.d(TAG, "onRenegotiationNeeded() called")
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
        Log.d(TAG, "onAddTrack() called with: p0 = [$p0], p1 = [$p1]")
    }

    /* PeerManager Events */
    override fun onLocalDescription(sdp: SessionDescription) {
        runOnUiThread {
            val json = JSONObject().apply {
                put("sdp", sdp.description)
            }
            if (isInitiator) {
                json.put("type", "offer")
            } else {
                json.put("type", "answer")
            }

            lifecycleScope.launch {
                service.sendOffer(roomId, clientId, json.toString())
            }
        }
    }

    /* WebSocket */
    override fun onOpen() {
        runOnUiThread {
            socketManager.state = SocketState.CONNECTED

            if (roomId.isNotEmpty() && clientId.isNotEmpty())
                socketManager.register(roomId, clientId)
        }
    }

    override fun onClose(p0: WebSocket.WebSocketConnectionObserver.WebSocketCloseNotification?, p1: String?) {
        Log.d(TAG, "onClose() called with: p0 = [$p0], p1 = [$p1]")
    }

    override fun onTextMessage(message: String) {
        Log.d(TAG, "onTextMessage() called with: message = [$message]")

        runOnUiThread {
            if (socketManager.state == SocketState.CONNECTED ||
                socketManager.state == SocketState.REGISTERED) {

                peerManager.handleMessage(message)
            }
        }
    }

    override fun onRawTextMessage(p0: ByteArray?) {
        Log.d(TAG, "onRawTextMessage() called with: p0 = [$p0]")
    }


    override fun onBinaryMessage(p0: ByteArray?) {
        Log.d(TAG, "onBinaryMessage() called with: p0 = [$p0]")
    }


    companion object {
        private const val TAG = "CallActivity"
    }
}