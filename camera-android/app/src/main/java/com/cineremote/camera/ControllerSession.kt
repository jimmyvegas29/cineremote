package com.cineremote.camera

import android.content.Context
import android.util.Log
import cineremote.camctl.v5.MainMessages.ControllerMessage
import cineremote.camctl.v5.MainMessages.ControllerMessages
import cineremote.camctl.v5.MainMessages.SubordinateMessage
import cineremote.camctl.v5.MainMessages.SubordinateMessages
import org.json.JSONObject

/**
 * One connected controller (CineRemote): binds its [RemoteServer.SignalingLink] to a fresh [RtcLink] and
 * [CameraController]. The WebSocket carries the initial data-channel negotiation; the data channel then
 * carries protobuf control + the media re-offer for the live stream.
 */
class ControllerSession(
    context: Context,
    private val link: RemoteServer.SignalingLink,
    private val engine: CameraEngine,
    private val recorder: Recorder,
    private val prefs: Prefs,
    batteryPct: () -> Float,
    private val onClosed: (ControllerSession) -> Unit,
) {
    private val rtc = RtcLink(context)
    private var closed = false
    val controller = CameraController(engine, recorder, prefs, batteryPct) { m -> sendSubordinate(m) }

    fun start() {
        controller.onStartPreview = {
            rtc.startMedia { offer ->
                sendSubordinate(SubordinateMessage.newBuilder().setMessageId(0)
                    .setWebrtcSdpRequest(cineremote.camctl.v5.MainMessages.WebRtcSdpRequest.newBuilder().setData(offer))
                    .build())
            }
        }
        controller.onDisconnect = { close("controller requested disconnect") }
        controller.onIdentify = { onIdentify?.invoke() }

        rtc.setStreamSize(engine.captureSize.width, engine.captureSize.height)
        engine.setStreamSurface(rtc.cameraInputSurface)
        rtc.open(object : RtcLink.SessionListener {
            override fun onLocalOffer(sdp: String) = link.send("sdp_offer", JSONObject().put("value", sdp))
            override fun onIceCandidate(candidate: String) =
                link.send("ice_candidate", JSONObject().put("value", "candidate:$candidate"))
            override fun onDataChannelOpen() { Log.i(TAG, "data channel open") }
            override fun onDataChannelMessage(data: ByteArray) = onControlData(data)
            override fun onSessionClosed(reason: String) = close(reason)
        })
    }

    var onIdentify: (() -> Unit)? = null

    /** A signaling message arrived on the WebSocket after authorization. */
    fun onSignaling(type: String, data: JSONObject) {
        when (type) {
            "sdp_answer" -> rtc.setRemoteAnswer(data.optString("value"))
            "ice_candidate" -> rtc.addRemoteCandidate(data.optString("value"))
            "bye_message" -> close("controller said bye")
        }
    }

    private fun onControlData(bytes: ByteArray) {
        val msgs = try {
            ControllerMessages.parseFrom(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "bad ControllerMessages", e); return
        }
        for (m in msgs.messagesList) {
            if (m.messageCase == ControllerMessage.MessageCase.WEBRTC_SDP_RESPONSE) {
                rtc.setRemoteAnswer(m.webrtcSdpResponse.data)
            } else {
                try {
                    controller.onControllerMessage(m)
                } catch (e: Exception) {
                    Log.e(TAG, "handling ${m.messageCase}", e)
                }
            }
        }
    }

    private fun sendSubordinate(m: SubordinateMessage) {
        val ok = rtc.send(SubordinateMessages.newBuilder().addMessages(m).build().toByteArray())
        if (!ok) Log.d(TAG, "drop ${m.messageCase} (data channel not open)")
    }

    fun setStreamSize(w: Int, h: Int) = rtc.setStreamSize(w, h)

    /** Push changed properties (camera switched, auto values moved) to the controller. */
    fun emitChanged(fields: Set<String>) = controller.emitChanged(fields)

    /** Periodic refresh so battery, storage runtime and recording state stay current on the controller. */
    fun emitHousekeeping() {
        controller.emitProperty("battery")
        controller.emitProperty("active_storage")
        controller.emitProperty("recording_state")
    }

    fun close(reason: String) {
        if (closed) return
        closed = true
        Log.i(TAG, "session closed: $reason")
        // Remove the stream surface, then release RTC on the camera thread so ordering is safe.
        engine.setStreamSurface(null)
        engine.handler.post { rtc.release() }
        link.close()
        onClosed(this)
    }

    companion object { const val TAG = "CRCam.Session" }
}
