package com.example.rtmpstreamer

import android.content.Context
import android.content.Intent
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2

/**
 * Guarda la cámara/encoder fuera de la Activity para que la transmisión siga viva
 * cuando la app se minimiza. La Activity se "conecta" y "desconecta" de aquí.
 */
object StreamHolder {
    private var appContext: Context? = null

    var camera: RtmpCamera2? = null
        private set

    /** Estado de micrófono y cámara (se conserva aunque la pantalla se recree). */
    @Volatile var micOn = true
    @Volatile var camOn = true

    /** Quién recibe los eventos de conexión (la Activity, si está abierta). */
    @Volatile
    var checker: ConnectChecker? = null

    private val forwarder = object : ConnectChecker {
        override fun onConnectionStarted(url: String) { checker?.onConnectionStarted(url) }
        override fun onConnectionSuccess() { checker?.onConnectionSuccess() }
        override fun onConnectionFailed(reason: String) {
            val c = checker
            if (c != null) c.onConnectionFailed(reason) else stopAll()   // sin pantalla: detener todo
        }
        override fun onNewBitrate(bitrate: Long) { checker?.onNewBitrate(bitrate) }
        override fun onDisconnect() { checker?.onDisconnect() }
        override fun onAuthError() { checker?.onAuthError() }
        override fun onAuthSuccess() { checker?.onAuthSuccess() }
    }

    fun get(ctx: Context): RtmpCamera2 {
        appContext = ctx.applicationContext
        return camera ?: RtmpCamera2(ctx.applicationContext, forwarder).also { camera = it }
    }

    fun stopAll() {
        camera?.let {
            if (it.isStreaming) it.stopStream()
            if (it.isOnPreview) it.stopPreview()
        }
        appContext?.let { it.stopService(Intent(it, StreamService::class.java)) }
    }

    fun release() {
        stopAll()
        micOn = true
        camOn = true
        camera = null
    }
}
