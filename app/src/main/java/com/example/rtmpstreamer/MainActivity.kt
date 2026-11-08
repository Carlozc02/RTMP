package com.example.rtmpstreamer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

class MainActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {

    private lateinit var camera: RtmpCamera2
    private lateinit var glView: OpenGlView
    private lateinit var etUrl: EditText
    private lateinit var btnStart: Button
    private lateinit var btnDual: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvBitrate: TextView
    private lateinit var spRes: Spinner
    private lateinit var spFps: Spinner
    private lateinit var sbBitrate: SeekBar

    // --- Chat overlay + voz ---
    private lateinit var chat: ChatManager
    private lateinit var tvChat: TextView
    private lateinit var cbChat: CheckBox
    private lateinit var cbTts: CheckBox
    private lateinit var etChats: EditText
    private lateinit var btnChats: Button
    private var chatOn = false
    private val chatLines = ArrayDeque<String>()

    private val resolutions = listOf("1920x1080", "1280x720", "854x480", "640x360")
    private val fpsOptions = listOf("30", "60", "24", "15")
    private val prefs by lazy { getSharedPreferences("rtmp", MODE_PRIVATE) }
    private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    // --- Doble cámara (picture-in-picture) ---
    private var dualMode = false
    private var mainIsBack = true            // cámara principal actual (la grande)
    private var pipFilter: SurfaceFilterRender? = null
    private var pipTexture: SurfaceTexture? = null
    private var pipDevice: CameraDevice? = null
    private var pipSession: CameraCaptureSession? = null
    private val pipThread = HandlerThread("pip-camera").also { it.start() }
    private val pipHandler = Handler(pipThread.looper)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.openGlView)
        etUrl = findViewById(R.id.etUrl)
        btnStart = findViewById(R.id.btnStart)
        btnDual = findViewById(R.id.btnDual)
        tvStatus = findViewById(R.id.tvStatus)
        tvBitrate = findViewById(R.id.tvBitrate)
        spRes = findViewById(R.id.spRes)
        spFps = findViewById(R.id.spFps)
        sbBitrate = findViewById(R.id.sbBitrate)

        spRes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutions)
        spFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
        spRes.setSelection(1)

        etUrl.setText(prefs.getString("url", ""))
        sbBitrate.progress = prefs.getInt("bitrate", 2500)
        updateBitrateLabel()

        sbBitrate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (p < 300) sbBitrate.progress = 300
                updateBitrateLabel()
                if (camera.isStreaming) camera.setVideoBitrateOnFly(sbBitrate.progress * 1000)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        StreamHolder.checker = this
        camera = StreamHolder.get(this)
        glView.holder.addCallback(this)

        if (camera.isStreaming) {   // volvimos a la app mientras seguía transmitiendo
            btnStart.text = "Detener transmisión"
            tvStatus.text = "EN VIVO"
        }
        btnStart.setOnClickListener { if (camera.isStreaming) stopStream() else startStream() }
        btnDual.setOnClickListener { if (dualMode) disableDual() else enableDual() }
        findViewById<Button>(R.id.btnSwitch).setOnClickListener { switchCameras() }

        setupChat()
        setupDrawers()

        if (!hasPermissions()) requestAll()
    }

    // Menús laterales: se abren deslizando desde el borde o con los botones ☰ / 💬,
    // y se ocultan deslizando de vuelta, tocando fuera o con el botón Atrás.
    private fun setupDrawers() {
        val drawer = findViewById<DrawerLayout>(R.id.drawerLayout)
        drawer.setScrimColor(Color.TRANSPARENT)   // no oscurece el preview
        findViewById<Button>(R.id.btnMenu).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<Button>(R.id.btnChatMenu).setOnClickListener { drawer.openDrawer(GravityCompat.END) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START) || drawer.isDrawerOpen(GravityCompat.END)) {
                    drawer.closeDrawers()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupChat() {
        tvChat = findViewById(R.id.tvChat)
        cbChat = findViewById(R.id.cbChat)
        cbTts = findViewById(R.id.cbTts)
        etChats = findViewById(R.id.etChats)
        btnChats = findViewById(R.id.btnChats)

        etChats.setText(prefs.getString("chats", ""))
        cbChat.isChecked = prefs.getBoolean("showChat", true)
        cbTts.isChecked = prefs.getBoolean("tts", false)

        chat = ChatManager(this) { line -> addChatLine(line) }
        chat.speak = cbTts.isChecked

        cbChat.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("showChat", checked).apply()
            tvChat.visibility = if (checked && chatLines.isNotEmpty()) View.VISIBLE else View.GONE
        }
        cbTts.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("tts", checked).apply()
            chat.speak = checked
        }
        btnChats.setOnClickListener {
            if (chatOn) {
                chat.disconnect()
                chatOn = false
                btnChats.text = "Conectar chats"
            } else {
                prefs.edit().putString("chats", etChats.text.toString()).apply()
                chat.connect(etChats.text.toString().lines())
                chatOn = true
                btnChats.text = "Desconectar chats"
            }
        }
    }

    private fun addChatLine(line: String) {
        chatLines.addLast(line)
        while (chatLines.size > 10) chatLines.removeFirst()
        tvChat.text = chatLines.joinToString("\n")
        if (cbChat.isChecked) tvChat.visibility = View.VISIBLE
    }

    private fun requestAll() {
        val list = permissions.toMutableList()
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, list.toTypedArray(), 1)
    }

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateBitrateLabel() {
        tvBitrate.text = "Bitrate: ${sbBitrate.progress} kbps"
    }

    // ------------------------------------------------------------------
    // Transmisión
    // ------------------------------------------------------------------
    private fun startStream() {
        val url = etUrl.text.toString().trim()
        if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) {
            Toast.makeText(this, "La URL debe empezar con rtmp:// o rtmps://", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasPermissions()) {
            requestAll()
            return
        }
        prefs.edit().putString("url", url).putInt("bitrate", sbBitrate.progress).apply()

        val (w, h) = resolutions[spRes.selectedItemPosition].split("x").map { it.toInt() }
        val fps = fpsOptions[spFps.selectedItemPosition].toInt()

        val videoOk = camera.prepareVideo(w, h, fps, sbBitrate.progress * 1000, 0)
        val audioOk = camera.prepareAudio(128 * 1000, 44100, true)
        if (videoOk && audioOk) {
            ContextCompat.startForegroundService(this, Intent(this, StreamService::class.java))
            camera.startStream(url)
            btnStart.text = "Detener transmisión"
            tvStatus.text = "Conectando..."
        } else {
            Toast.makeText(this, "Tu dispositivo no soporta esa configuración", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopStream() {
        camera.stopStream()
        stopService(Intent(this, StreamService::class.java))
        btnStart.text = "Iniciar transmisión"
        tvStatus.text = "Detenido"
    }

    // ------------------------------------------------------------------
    // Doble cámara: la principal la maneja RootEncoder; la secundaria se abre
    // con Camera2 y se dibuja como recuadro (esquina inferior derecha) dentro
    // del mismo video que se transmite.
    // ------------------------------------------------------------------
    private fun enableDual() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "Doble cámara requiere Android 11 o superior", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasPermissions()) {
            requestAll()
            return
        }
        if (!supportsConcurrentFrontBack()) {
            Toast.makeText(this, "Este teléfono no permite usar las dos cámaras al mismo tiempo", Toast.LENGTH_LONG).show()
            return
        }
        dualMode = true
        btnDual.text = "Modo: doble cámara"

        val filter = SurfaceFilterRender { texture ->
            // Se llama cuando el recuadro ya está listo para recibir video
            pipTexture = texture
            openPip(texture)
        }
        pipFilter = filter
        camera.getGlInterface().setFilter(filter)
        filter.setScale(30f, 30f)                       // % del tamaño del video
        filter.setPosition(TranslateTo.BOTTOM_RIGHT)
    }

    private fun disableDual() {
        dualMode = false
        btnDual.text = "Modo: 1 cámara"
        closePip()
        pipFilter?.let { camera.getGlInterface().removeFilter(it) }
        pipFilter = null
        pipTexture = null
    }

    private fun switchCameras() {
        if (!dualMode) {
            camera.switchCamera()
            mainIsBack = !mainIsBack
            return
        }
        // En modo doble: intercambia cuál es la grande y cuál el recuadro
        closePip()
        camera.switchCamera()
        mainIsBack = !mainIsBack
        pipTexture?.let { openPip(it) }
    }

    private fun supportsConcurrentFrontBack(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val back = cameraIdFor(cm, CameraCharacteristics.LENS_FACING_BACK) ?: return false
        val front = cameraIdFor(cm, CameraCharacteristics.LENS_FACING_FRONT) ?: return false
        return cm.concurrentCameraIds.any { it.contains(back) && it.contains(front) }
    }

    private fun cameraIdFor(cm: CameraManager, facing: Int): String? =
        cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        }

    @SuppressLint("MissingPermission")
    private fun openPip(texture: SurfaceTexture) {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val pipFacing = if (mainIsBack) CameraCharacteristics.LENS_FACING_FRONT
                        else CameraCharacteristics.LENS_FACING_BACK
        val id = cameraIdFor(cm, pipFacing) ?: return
        texture.setDefaultBufferSize(640, 480)
        val surface = Surface(texture)

        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                pipDevice = device
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        pipSession = session
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        req.addTarget(surface)
                        session.setRepeatingRequest(req.build(), null, pipHandler)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "No se pudo iniciar la segunda cámara", Toast.LENGTH_LONG).show() }
                    }
                }, pipHandler)
            }
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                runOnUiThread { Toast.makeText(this@MainActivity, "Error en la segunda cámara ($error)", Toast.LENGTH_LONG).show() }
            }
        }, pipHandler)
    }

    private fun closePip() {
        try { pipSession?.close() } catch (_: Exception) {}
        try { pipDevice?.close() } catch (_: Exception) {}
        pipSession = null
        pipDevice = null
    }

    // ------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        camera.replaceView(glView)   // (re)conecta la vista, p. ej. al volver de minimizar
        if (hasPermissions() && !camera.isOnPreview) camera.startPreview()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (hasPermissions() && !camera.isOnPreview) camera.startPreview()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (dualMode) {
            disableDual()
            Toast.makeText(this, "Doble cámara desactivada al minimizar; vuelve a activarla", Toast.LENGTH_LONG).show()
        }
        if (camera.isStreaming) {
            camera.replaceView(applicationContext)   // sigue transmitiendo sin pantalla
        } else {
            camera.stopPreview()
        }
    }

    override fun onDestroy() {
        chat.shutdown()
        StreamHolder.checker = null
        if (!camera.isStreaming) StreamHolder.release()
        closePip()
        pipThread.quitSafely()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasPermissions() && !camera.isOnPreview) camera.startPreview()
    }

    // ------------------------------------------------------------------
    // ConnectChecker (hilos de red → pasar al hilo UI)
    // ------------------------------------------------------------------
    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        runOnUiThread { tvStatus.text = "EN VIVO" }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Falló la conexión: $reason", Toast.LENGTH_LONG).show()
            stopStream()
        }
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        runOnUiThread { tvStatus.text = "Desconectado" }
    }

    override fun onAuthError() {
        runOnUiThread { Toast.makeText(this, "Error de autenticación", Toast.LENGTH_LONG).show() }
    }

    override fun onAuthSuccess() {}
}
