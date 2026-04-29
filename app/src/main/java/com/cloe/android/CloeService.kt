package com.cloe.android

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.JsonReader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.io.StringReader
import java.net.URI
import kotlin.math.abs

class CloeService : Service() {

    companion object {
        var isRunning = false
        private const val NOTIFICATION_ID = 1001
        private const val WS_PORT = 19850

        // GIF action -> filename mapping (same as Electron renderer)
        private val ACTION_MAP = mapOf(
            "smile" to "smile.gif",
            "blink" to "blink.gif",
            "kiss" to "kiss.gif",
            "nod" to "nod.gif",
            "wave" to "wave.gif",
            "think" to "think.gif",
            "tease" to "tease.gif",
            "speak" to "speak.gif",
            "shake_head" to "shake_head.gif",
            "working" to "working.gif"
        )

        // Idle weights: blink×2, smile×2, kiss×1, think×1, nod×1, shake_head×1
        private val IDLE_ACTIONS = listOf(
            "blink", "blink", "smile", "smile",
            "kiss", "think", "nod", "shake_head"
        )
    }

    private lateinit var windowManager: WindowManager
    private var expandedView: View? = null
    private var collapsedView: View? = null
    private var paramsExpanded: WindowManager.LayoutParams? = null
    private var paramsCollapsed: WindowManager.LayoutParams? = null

    private var wsClient: WebSocketClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var idleJob: Job? = null
    private var isWorking = false
    private var lastAction: String = ""
    private var host: String = ""

    // GIF assets path prefix
    private val gifCache = mutableMapOf<String, String>()

    // === Service lifecycle ===

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Pre-cache asset file paths
        ACTION_MAP.values.forEach { name ->
            val file = copyAssetToFile("gifs/$name")
            if (file != null) gifCache[name] = file
        }
    }

    /**
     * Copy asset to internal cache dir so Glide can load it via file://
     * Returns absolute file path, or null on failure.
     */
    private fun copyAssetToFile(assetPath: String): String? {
        return try {
            val cacheFile = File(cacheDir, assetPath.replace("/", "_"))
            if (cacheFile.exists()) return cacheFile.absolutePath
            cacheFile.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            cacheFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        host = intent?.getStringExtra("host") ?: return START_NOT_STICKY

        startForegroundNotification()
        isRunning = true

        createExpandedView()
        createCollapsedView()
        showExpanded()
        connectWebSocket()

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        idleJob?.cancel()
        scope.cancel()
        wsClient?.close()
        expandedView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        collapsedView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }

    // === Notification ===

    private fun startForegroundNotification() {
        val channel = NotificationChannel(
            "cloe_service", "Cloe Service", NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, "cloe_service")
            .setContentTitle("Cloe")
            .setContentText("正在桌面上等待你")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // === Overlay views ===

    private val layoutParamsType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    @SuppressLint("ClickableViewAccessibility")
    private fun createExpandedView() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val gifView = ImageView(this).apply {
            val dp = resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(
                (200 * dp).toInt(), (280 * dp).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        layout.addView(gifView)
        expandedView = layout

        paramsExpanded = WindowManager.LayoutParams(
            (200 * resources.displayMetrics.density).toInt(),
            (280 * resources.displayMetrics.density).toInt(),
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        // Store ImageView reference for GIF updates
        layout.tag = gifView

        // Drag (Gravity.END: x is offset from right edge, so dx is inverted)
        var lastX = 0; var lastY = 0; var moved = false
        gifView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX.toInt(); lastY = event.rawY.toInt(); moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - lastX
                    val dy = event.rawY.toInt() - lastY
                    if (abs(dx) > 5 || abs(dy) > 5) moved = true
                    paramsExpanded?.let { p ->
                        p.x -= dx; p.y += dy
                        windowManager.updateViewLayout(expandedView, p)
                    }
                    lastX = event.rawX.toInt(); lastY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) showCollapsed(); true }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createCollapsedView() {
        val dot = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#FF69B4"))
        }
        dot.setOnClickListener { showExpanded() }

        collapsedView = dot
        paramsCollapsed = WindowManager.LayoutParams(
            (50 * resources.displayMetrics.density).toInt(),
            (50 * resources.displayMetrics.density).toInt(),
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16; y = 200
        }

        var lastX = 0; var lastY = 0
        dot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { lastX = event.rawX.toInt(); lastY = event.rawY.toInt(); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - lastX
                    val dy = event.rawY.toInt() - lastY
                    paramsCollapsed?.let { p ->
                        p.x -= dx; p.y += dy
                        windowManager.updateViewLayout(collapsedView, p)
                    }
                    lastX = event.rawX.toInt(); lastY = event.rawY.toInt()
                    true
                }
                else -> false
            }
        }
    }

    private fun showExpanded() {
        collapsedView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        expandedView?.let { try { windowManager.addView(it, paramsExpanded) } catch (_: Exception) {} }
        if (lastAction.isBlank()) loadGif("smile")
    }

    private fun showCollapsed() {
        isWorking = true // pause idle
        expandedView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        collapsedView?.let { try { windowManager.addView(it, paramsCollapsed) } catch (_: Exception) {} }
    }

    // === GIF loading (from local assets, no network) ===

    private fun getGifView(): ImageView? = expandedView?.tag as? ImageView

    private fun loadGif(action: String) {
        val filename = ACTION_MAP[action] ?: return
        val filePath = gifCache[filename] ?: return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            Glide.with(this@CloeService)
                .asGif()
                .load(filePath)
                .into(getGifView() ?: return@post)
        }
    }

    // === WebSocket ===

    private fun connectWebSocket() {
        val uri = URI("ws://$host:$WS_PORT")

        wsClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(this@CloeService, "已连接 ✅", Toast.LENGTH_SHORT).show()
                }
                if (!isWorking) startIdleLoop()
            }

            override fun onMessage(message: String?) {
                message ?: return
                handleMessage(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                scope.launch { delay(3000); connectWebSocket() }
            }

            override fun onError(ex: Exception?) {
                ex?.printStackTrace()
            }
        }
        wsClient?.connect()
    }

    private fun handleMessage(raw: String) {
        try {
            val data = JsonReader(StringReader(raw)).use { reader ->
                var action = ""
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "action" -> action = reader.nextString()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                action
            }

            when (data) {
                "idle" -> { isWorking = false; startIdleLoop() }
                "working" -> { isWorking = true; idleJob?.cancel(); playAction("working") }
                "wave" -> { showExpanded(); playAction("wave") }
                "kiss" -> playAction("kiss")
                else -> playAction(data)
            }
        } catch (_: Exception) {}
    }

    private fun playAction(action: String) {
        if (action == lastAction) return
        lastAction = action
        loadGif(action)

        if (action != "working") {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(3000)
                if (!isWorking) scheduleNextIdle()
            }
        }
    }

    // === Idle loop ===

    private fun startIdleLoop() { idleJob?.cancel(); scheduleNextIdle() }

    private fun scheduleNextIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay((8000..15000).random().toLong())
            if (!isWorking) {
                var next = IDLE_ACTIONS.random()
                while (next == lastAction) next = IDLE_ACTIONS.random()
                lastAction = next
                loadGif(next)
                scheduleNextIdle()
            }
        }
    }
}
