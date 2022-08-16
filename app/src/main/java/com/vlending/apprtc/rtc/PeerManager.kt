package com.vlending.apprtc.rtc

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

class PeerManager(
    private val context: Context,
    private val observer: PeerConnection.Observer,
    private val events: PeerConnectionEvents
) {

    interface PeerConnectionEvents {
        fun onLocalDescription(sdp: SessionDescription)
    }

    var isInitiator = false

    private var peerConnection: PeerConnection? = null

    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val rootEglBase = EglBase.create()

    private val executor = Executors.newSingleThreadExecutor()

    private val peerConnectionFactory by lazy { buildPeerConnectionFactory(context) }

    private val localSink = ProxyVideoSink()
    private val remoteSink = ProxyVideoSink()

    private val constraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "true"))
        mandatory.add(MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"))
        mandatory.add(MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"))
        mandatory.add(MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "true"))
    }

    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var localAudioTrack: AudioTrack? = null

    private var localVideoSender: RtpSender? = null

    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    private var localDescription: SessionDescription? = null

    private val sdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            Log.d(TAG, "onCreateSuccess() called with: sdp = [$sdp]")

            localDescription = sdp
            executor.execute {
                peerConnection?.setLocalDescription(this, sdp)
            }
        }

        override fun onSetSuccess() {
            executor.execute {
                peerConnection?.let {
                    localDescription?.let { ldp -> events.onLocalDescription(ldp) }
                }
            }
        }

        override fun onCreateFailure(p0: String?) {
            Log.d(TAG, "onCreateFailure() called with: p0 = [$p0]")
        }

        override fun onSetFailure(p0: String?) {
            Log.d(TAG, "onSetFailure() called with: p0 = [$p0]")
        }

    }

    private val videoCapturer =
        if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(false)
        }.run {
            deviceNames.find { deviceName ->
                isFrontFacing(deviceName)
            }?.let {
                createCapturer(it, null)
            }
        }


    private fun buildPeerConnectionFactory(context: Context): PeerConnectionFactory {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions())
        return PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(
                rootEglBase.eglBaseContext,
                false,
                true
            ))
            .createPeerConnectionFactory()
    }

    fun initSurfaceViewRenderer(local: SurfaceViewRenderer, remote: SurfaceViewRenderer) {
        local.run {
            init(rootEglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
            localSink.target = this
        }

        remote.run {
            init(rootEglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
            remoteSink.target = this
        }
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val mediaStreamLabels = listOf("ARDAMS")
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)?.apply {
            addTrack(createVideoTrack(), mediaStreamLabels)
        }

        remoteVideoTrack = getRemoteVideoTrack()?.apply {
            setEnabled(true)
            addSink(remoteSink)
        }

        peerConnection?.addTrack(createAudioTrack(), mediaStreamLabels)

        findVideoSender()
    }

    private fun createVideoTrack(): VideoTrack {
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
        videoCapturer?.let { capturer ->
            videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
            capturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            capturer.startCapture(540, 513, 30)
        }

        return peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource).apply {
            setEnabled(true)
            addSink(localSink)
            localVideoTrack = this
        }
    }

    private fun getRemoteVideoTrack(): VideoTrack? {
        peerConnection?.let { pc ->
            for (transceiver in pc.transceivers) {
                val track = transceiver.receiver.track()

                if (track is VideoTrack) {
                    return track
                }
            }
        }
        return null
    }

    private fun createAudioTrack(): AudioTrack {
        audioSource = peerConnectionFactory.createAudioSource(constraints)
        return peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource).apply {
            setEnabled(true)
            localAudioTrack = this
        }
    }

    private fun findVideoSender() {
        peerConnection?.let { pc ->
            for (sender in pc.senders) {
                val trackType = sender.track()?.kind()
                if (trackType == "video") {
                    localVideoSender = sender
                }
            }
        }
    }

    fun createOffer() {
        executor.execute {
            peerConnection?.createOffer(sdpObserver, constraints)
        }
    }

    fun createAnswer() {
        executor.execute {
            peerConnection?.createAnswer(sdpObserver, constraints)
        }
    }

    fun setRemoteDescription(offerSdp: SessionDescription) {
        executor.execute {
            peerConnection?.setRemoteDescription(sdpObserver, offerSdp)
        }
    }

    fun addRemoteIceCandidate(iceCandidate: IceCandidate) {
        executor.execute {
            peerConnection?.addIceCandidate(iceCandidate)
        }
    }

    fun disconnect() {
        remoteSink.target = null
        localSink.target = null

        peerConnection?.close()
    }


    fun stopVideoSource() {
        executor.execute {
            videoCapturer?.stopCapture()
        }
    }

    fun handleMessage(message: String) {
        val msg = JSONObject(message).getString("msg")

        if (msg.isEmpty()) {
            Log.d(TAG, "Message body is empty: $message")
            return
        }

        val json = JSONObject(msg)

        when (val type = json.getString("type")) {
            "candidate" -> {
                peerConnection?.addIceCandidate(json.toCandidate())
            }
            "answer" -> {
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp")
                )
                peerConnection?.setRemoteDescription(sdpObserver, sdp)
                createAnswer()
            }
            "offer" -> {
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp")
                )
                peerConnection?.setRemoteDescription(sdpObserver, sdp)
            }
            "bye" -> {

            }
        }
    }


    private class ProxyVideoSink : VideoSink {

        var target: VideoSink? = null
        override fun onFrame(frame: VideoFrame?) {
            target?.onFrame(frame)
        }
    }

    private fun JSONObject.toCandidate(): IceCandidate {
        return IceCandidate(
            getString("id"), getInt("label"), getString("candidate")
        )
    }

    companion object {
        private const val TAG = "PeerManager"

        private const val AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation"
        private const val AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl"
        private const val AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter"
        private const val AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression"

        private const val LOCAL_STREAM_ID = "ARDAMSs0"
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val AUDIO_TRACK_ID = "ARDAMSa0"
    }
}