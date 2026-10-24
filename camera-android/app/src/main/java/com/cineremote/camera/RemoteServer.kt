package com.cineremote.camera

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The phone's side of CineRemote's sign-in (see cineremote/signaling.py for the controller side):
 *  - advertises `_bmd-cam-control._tcp` on port 2222 over mDNS (the service CineRemote browses for)
 *  - runs the WebSocket JSON handshake: hello_request -> hello_response (nonce), conn_request (password proof)
 *    -> conn_response authorized
 *  - after sign-in carries the WebRTC offer/answer/ICE until the data channel is up ([Listener]).
 * One controller at a time: a second one is told "busy".
 */
class RemoteServer(private val context: Context, private val prefs: Prefs, private val listener: Listener) {

    interface Listener {
        /** A controller signed in. Return false to refuse (we're busy). */
        fun onControllerAuthorized(link: SignalingLink, controllerInfo: JSONObject): Boolean
        fun onSignalingMessage(link: SignalingLink, type: String, data: JSONObject)
        fun onSignalingClosed(link: SignalingLink)
    }

    /** One WebSocket from a controller. */
    inner class SignalingLink(val ws: WebSocket) {
        var nonce: ByteArray? = null
        var authorized = false
        var controllerInfo: JSONObject = JSONObject()
        fun send(type: String, data: JSONObject) {
            if (ws.isOpen) ws.send(JSONObject().put("type", type).put("data", data).toString())
        }
        fun close() = runCatching { ws.close() }
    }

    private val links = HashMap<WebSocket, SignalingLink>()
    private var server: WebSocketServer? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val random = SecureRandom()

    fun start() {
        val s = object : WebSocketServer(InetSocketAddress(PORT)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                synchronized(links) { links[conn] = SignalingLink(conn) }
                Log.i(TAG, "controller connected from ${conn.remoteSocketAddress}")
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
                val link = synchronized(links) { links.remove(conn) } ?: return
                if (link.authorized) listener.onSignalingClosed(link)
            }

            override fun onMessage(conn: WebSocket, message: String) {
                val link = synchronized(links) { links[conn] } ?: return
                val m = runCatching { JSONObject(message) }.getOrNull() ?: return
                val type = m.optString("type")
                val data = m.optJSONObject("data") ?: JSONObject()
                try {
                    handle(link, type, data)
                } catch (e: Exception) {
                    Log.e(TAG, "signaling $type", e)
                }
            }

            override fun onError(conn: WebSocket?, ex: Exception) { Log.e(TAG, "websocket", ex) }
            override fun onStart() { Log.i(TAG, "listening on :$PORT") }
        }
        s.isReuseAddr = true
        s.connectionLostTimeout = 0          // the protocol has its own heartbeats once connected
        s.start()
        server = s
        register()
    }

    fun stop() {
        unregister()
        runCatching { server?.stop(500) }
        server = null
    }

    /** Re-advertise after the name or slate letter changed. */
    fun refreshAdvertisement() {
        unregister()
        register()
    }

    fun participantInfo(): JSONObject = JSONObject()
        .put("uuid", prefs.uuid)
        .put("model_name", Build.MODEL)
        .put("device_name", prefs.deviceName)
        .put("slate_name", prefs.slate)
        .put("proto_major", PROTO_MAJOR)
        .put("proto_minor", PROTO_MINOR)

    private fun handle(link: SignalingLink, type: String, data: JSONObject) {
        when (type) {
            "hello_request" -> {
                val clientNonce = hex(data.optString("nonce"))
                val serverNonce = ByteArray(32).also { random.nextBytes(it) }
                link.nonce = clientNonce + serverNonce
                link.controllerInfo = data.optJSONObject("info") ?: JSONObject()
                link.send("hello_response", JSONObject()
                    .put("info", participantInfo())
                    .put("controller_info", link.controllerInfo)
                    .put("status", if (busyFor(link)) "busy" else "ready")
                    .put("nonce", hex(link.nonce!!)))
            }
            "conn_request" -> {
                val nonce = link.nonce
                val ok = nonce != null && nonce.size == 64 &&
                        MessageDigest.isEqual(hex(passwordHash(prefs.password, nonce)).toByteArray(),
                                              data.optString("password").lowercase().toByteArray())
                when {
                    !ok -> {
                        link.send("conn_response", JSONObject().put("status", "auth_error"))
                        Log.w(TAG, "wrong password from ${link.ws.remoteSocketAddress}")
                    }
                    busyFor(link) || !listener.onControllerAuthorized(link, link.controllerInfo) ->
                        link.send("conn_response", JSONObject().put("status", "busy"))
                    else -> {
                        link.authorized = true
                        link.send("conn_response", JSONObject().put("status", "authorized"))
                        Log.i(TAG, "authorized ${link.controllerInfo.optString("device_name")}")
                    }
                }
            }
            else -> if (link.authorized) listener.onSignalingMessage(link, type, data)
        }
    }

    private fun busyFor(link: SignalingLink) = synchronized(links) { links.values.any { it !== link && it.authorized } }

    // ------------------------------------------------------------------ mDNS
    private fun register() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("cineremote-mdns").apply { setReferenceCounted(false); acquire() }
        val info = NsdServiceInfo().apply {
            serviceName = prefs.uuid                 // controllers key cameras by this instance name
            serviceType = SERVICE_TYPE
            port = PORT
            setAttribute("name", prefs.deviceName)
            setAttribute("model_name", Build.MODEL)
            setAttribute("slate_name", prefs.slate)
            setAttribute("proto", "$PROTO_MAJOR.$PROTO_MINOR")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "advertised as ${s.serviceName}") }
            override fun onRegistrationFailed(s: NsdServiceInfo, err: Int) { Log.e(TAG, "mDNS registration failed $err") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, err: Int) {}
        }
        nsd = (context.getSystemService(Context.NSD_SERVICE) as NsdManager).also {
            it.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        }
        nsdListener = l
    }

    private fun unregister() {
        nsdListener?.let { l -> runCatching { nsd?.unregisterService(l) } }
        nsdListener = null
        runCatching { multicastLock?.release() }
    }

    companion object {
        const val TAG = "CRCam.Server"
        const val PORT = 2222
        const val SERVICE_TYPE = "_bmd-cam-control._tcp"
        const val PROTO_MAJOR = 5
        const val PROTO_MINOR = 0
        private val SALT1 = hex("0e7b97a67bd5d0ed")
        private val SALT2 = hex("55813b6755c037b1")

        fun passwordHash(password: String, nonce: ByteArray): ByteArray {
            val sha = { b: ByteArray -> MessageDigest.getInstance("SHA-256").digest(b) }
            return sha(nonce + sha(password.toByteArray(Charsets.UTF_8) + SALT1) + SALT2)
        }

        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    }
}
