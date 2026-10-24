package com.cineremote.camera

import android.content.Context
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/**
 * WebRTC for one controller session, camera side (cineremote/session.py is the other end):
 *  1. we offer a data channel "signaling-channel" (the controller only ever answers), sent over the WebSocket
 *  2. on StartPreview we add sendonly H.264 video + Opus audio and re-offer over the data channel
 * Video is hardware H.264 Constrained Baseline - what CineRemote's aiortc stack decodes.
 * Camera frames arrive through [textureHelper] (the camera renders into its SurfaceTexture).
 */
class RtcLink(context: Context) {

    interface SessionListener {
        fun onLocalOffer(sdp: String)
        fun onIceCandidate(candidate: String)
        fun onDataChannelOpen()
        fun onDataChannelMessage(data: ByteArray)
        fun onSessionClosed(reason: String)
    }

    val egl: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    val textureHelper: SurfaceTextureHelper
    /** The camera renders into this; frames are forwarded to the stream only while previewing. */
    val cameraInputSurface: android.view.Surface
    private val videoSource: VideoSource
    private val videoTrack: org.webrtc.VideoTrack
    private val audioTrack: org.webrtc.AudioTrack

    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private var listener: SessionListener? = null
    @Volatile private var streaming = false
    private var mediaAdded = false
    private var pendingOffer: ((String) -> Unit)? = null
    private var gatheringComplete = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(HardwareVideoEncoderFactory(egl.eglBaseContext, false, false))  // no H.264 High
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        videoSource = factory.createVideoSource(false)
        videoTrack = factory.createVideoTrack("video0", videoSource)
        val ac = MediaConstraints().apply {
            // a camera mic for a film set: no voice processing
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
        }
        audioTrack = factory.createAudioTrack("audio0", factory.createAudioSource(ac))
        textureHelper = SurfaceTextureHelper.create("camera-frames", egl.eglBaseContext)
        textureHelper.setTextureSize(1920, 1080)
        // Always drain the SurfaceTexture (an unconsumed one stalls the camera); forward only while streaming.
        textureHelper.startListening { frame: VideoFrame ->
            if (streaming) videoSource.capturerObserver.onFrameCaptured(frame)
        }
        videoSource.capturerObserver.onCapturerStarted(true)
        cameraInputSurface = android.view.Surface(textureHelper.surfaceTexture)
    }

    // ------------------------------------------------------------------ session
    fun open(l: SessionListener) {
        close()
        listener = l
        mediaAdded = false
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val p = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) { listener?.onIceCandidate(c.sdp) }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "peer connection $s")
                if (s == PeerConnection.PeerConnectionState.FAILED || s == PeerConnection.PeerConnectionState.CLOSED)
                    listener?.onSessionClosed("peer connection $s")
            }
            override fun onDataChannel(d: DataChannel) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) {
                    gatheringComplete = true
                    // Non-trickle: send the full SDP (candidates included), which CineRemote's aiortc expects.
                    pendingOffer?.let { cb -> pendingOffer = null; pc?.localDescription?.let { cb(it.description) } }
                }
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: org.webrtc.MediaStream) {}
            override fun onRemoveStream(s: org.webrtc.MediaStream) {}
            override fun onRenegotiationNeeded() {}
        }) ?: run { l.onSessionClosed("no peer connection"); return }
        pc = p
        val d = p.createDataChannel("signaling-channel", DataChannel.Init().apply { ordered = true })
        d.registerObserver(object : DataChannel.Observer {
            override fun onStateChange() {
                when (d.state()) {
                    DataChannel.State.OPEN -> listener?.onDataChannelOpen()
                    DataChannel.State.CLOSED -> listener?.onSessionClosed("data channel closed")
                    else -> {}
                }
            }
            override fun onMessage(b: DataChannel.Buffer) {
                if (!b.binary) return
                val bytes = ByteArray(b.data.remaining()).also { b.data.get(it) }
                listener?.onDataChannelMessage(bytes)
            }
            override fun onBufferedAmountChange(previous: Long) {}
        })
        dc = d
        createOffer { sdp -> listener?.onLocalOffer(sdp) }
    }

    fun setRemoteAnswer(sdp: String, then: () -> Unit = {}) {
        pc?.setRemoteDescription(object : SdpAdapter("setRemoteAnswer") {
            override fun onSetSuccess() = then()
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun addRemoteCandidate(candidate: String) {
        val sdp = if (candidate.startsWith("candidate:")) candidate.removePrefix("candidate:") else candidate
        pc?.addIceCandidate(IceCandidate("0", 0, sdp))
    }

    fun setStreamSize(w: Int, h: Int) = textureHelper.setTextureSize(w, h)

    fun send(bytes: ByteArray): Boolean {
        val d = dc ?: return false
        if (d.state() != DataChannel.State.OPEN) return false
        return d.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    /** Add sendonly H.264 + Opus (once per session) and produce the media re-offer for the data channel. */
    fun startMedia(onOffer: (String) -> Unit) {
        val p = pc ?: return
        if (!mediaAdded) {
            val sendOnly = RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("cineremote"))
            val videoTx = p.addTransceiver(videoTrack, sendOnly)
            p.addTransceiver(audioTrack, sendOnly)
            // Keep 1080p rather than dropping resolution under load; allow a high bitrate on a LAN.
            runCatching {
                val params = videoTx.sender.parameters
                params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                params.encodings.firstOrNull()?.let { it.maxBitrateBps = 12_000_000; it.minBitrateBps = 2_000_000 }
                videoTx.sender.parameters = params
            }
            mediaAdded = true
        }
        streaming = true
        createOffer { sdp -> onOffer(sdp) }
    }

    fun stopMedia() { streaming = false }

    private fun createOffer(onSdp: (String) -> Unit) {
        val p = pc ?: return
        p.createOffer(object : SdpAdapter("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                p.setLocalDescription(object : SdpAdapter("setLocalOffer") {
                    override fun onSetSuccess() {
                        // Media re-offers reuse the transport, so ICE is already complete: send at once.
                        if (gatheringComplete) onSdp(p.localDescription?.description ?: desc.description)
                        else pendingOffer = onSdp
                    }
                }, desc)
            }
        }, MediaConstraints())
    }

    fun close() {
        streaming = false
        runCatching { dc?.close() }
        dc = null
        runCatching { pc?.close() }
        pc = null
        listener = null
    }

    fun release() {
        close()
        runCatching { cameraInputSurface.release() }
        runCatching { textureHelper.stopListening() }
        runCatching { textureHelper.dispose() }
        runCatching { videoSource.dispose() }
        runCatching { factory.dispose() }
        runCatching { egl.release() }
    }

    companion object { const val TAG = "CRCam.Rtc" }
}

/** SdpObserver with empty defaults, so each use overrides only the one callback it cares about. */
open class SdpAdapter(private val what: String) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) { Log.e(RtcLink.TAG, "$what create failed: $error") }
    override fun onSetFailure(error: String?) { Log.e(RtcLink.TAG, "$what set failed: $error") }
}
