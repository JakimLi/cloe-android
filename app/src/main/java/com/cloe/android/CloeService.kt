package com.cloe.android

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.JsonReader
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.media.MediaPlayer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.net.URI
import kotlin.math.abs

class CloeService : Service() {

    companion object {
        var isRunning = false
        private const val TAG = "CloeService"
        private const val NOTIFICATION_ID = 1001
        private const val WS_PORT = 19850

        /** Built-in asset mapping (same logical names as Electron / bridge) */
        private val DEFAULT_ACTION_MAP = mapOf(
            "smile" to "smile.gif",
            "blink" to "blink.gif",
            "kiss" to "kiss.gif",
            "nod" to "nod.gif",
            "wave" to "wave.gif",
            "think" to "think.gif",
            "tease" to "tease.gif",
            "speak" to "speak.gif",
            "shake_head" to "shake_head.gif",
            "working" to "working.gif",
            "clap" to "clap.gif",
            "shy" to "shy.gif",
            "yawn" to "yawn.gif",
            "laugh" to "laugh.gif"
        )

        private val DEFAULT_IDLE_ACTIONS = listOf(
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
    private var isSpeaking = false
    private var mediaPlayer: MediaPlayer? = null
    private var lastAction: String = ""
    private var host: String = ""
    private var sessionStarted = false

    /** Which synced set folder to use (follows PC WebSocket set-config.setId). */
    private var displaySetId: String = "default"

    /** action name → absolute path (bundled copy and/or remote GIF) */
    private val defaultPathByAction = linkedMapOf<String, String>()
    private val pathByAction = linkedMapOf<String, String>()
    private val idleActions = mutableListOf<String>()

    // === Service lifecycle ===

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bootstrapActionPaths()
    }

    private fun bootstrapActionPaths() {
        defaultPathByAction.clear()
        for ((action, assetName) in DEFAULT_ACTION_MAP) {
            val file = copyAssetToFile("gifs/$assetName")
            if (file != null) defaultPathByAction[action] = file
        }
        displaySetId = ActionSync.readFullMeta(this)?.activeSetIdAtSync ?: "default"
        rebuildPathAndIdleMaps()
    }

    private fun rebuildPathAndIdleMaps() {
        pathByAction.clear()
        pathByAction.putAll(defaultPathByAction)
        pathByAction.putAll(ActionSync.loadRemoteActionPathsForSet(this, displaySetId))
        idleActions.clear()
        val full = ActionSync.readFullMeta(this)
        val idleFromMeta = full?.initialIdlePlaylist.orEmpty()
        if (idleFromMeta.isNotEmpty() && idleFromMeta.all { pathByAction.containsKey(it) }) {
            idleActions.addAll(idleFromMeta)
        } else {
            idleActions.addAll(DEFAULT_IDLE_ACTIONS)
        }
    }

    fun reloadActionPaths() {
        ActionSync.readFullMeta(this)?.activeSetIdAtSync?.let { displaySetId = it }
        rebuildPathAndIdleMaps()
        Log.i(TAG, "reloadActionPaths set=$displaySetId actions=${pathByAction.size} idle=${idleActions.size}")
    }

    /**
     * Copy asset to internal cache dir so Glide can load it via file://
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
        if (intent?.getBooleanExtra("reload_actions", false) == true) {
            reloadActionPaths()
            if (sessionStarted) return START_STICKY
        }

        val h = intent?.getStringExtra("host")?.trim().orEmpty()
        if (h.isNotBlank()) host = h

        if (host.isBlank()) return START_NOT_STICKY

        if (sessionStarted) return START_STICKY

        sessionStarted = true
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
        sessionStarted = false
        idleJob?.cancel()
        releaseMediaPlayer()
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

        layout.tag = gifView

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
        isWorking = true
        expandedView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        collapsedView?.let { try { windowManager.addView(it, paramsCollapsed) } catch (_: Exception) {} }
    }

    private fun getGifView(): ImageView? = expandedView?.tag as? ImageView

    private fun loadGif(action: String) {
        val filePath = pathByAction[action] ?: return
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
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) {
                val o = JSONObject(trimmed)
                if (o.optString("type") == "set-config") {
                    applySetConfig(o)
                    return
                }
                val action = o.optString("action", "")
                if (action.isNotEmpty()) {
                    dispatchAction(action, o)
                    return
                }
            }
            // Legacy: minimal JSON with only action (parse without full JSONObject)
            val actionOnly = parseLegacyAction(raw)
            if (actionOnly != null) dispatchAction(actionOnly, null)
        } catch (_: Exception) {
        }
    }

    private fun parseLegacyAction(raw: String): String? {
        return try {
            var action = ""
            JsonReader(StringReader(raw)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "action" -> action = reader.nextString()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            action.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun applySetConfig(o: JSONObject) {
        val full = ActionSync.readFullMeta(this)
        val sid = o.optString("setId", "")
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        if (sid.isNotEmpty()) {
            if (full != null && !full.hasSet(sid)) {
                mainHandler.post {
                    Toast.makeText(
                        this,
                        "PC 当前套装未在本地缓存，请重新「从 PC 拉取动作」",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            displaySetId = sid
            rebuildPathAndIdleMaps()
        }

        val idleJa = o.optJSONArray("idlePlaylist") ?: return
        val candidates = buildList {
            for (i in 0 until idleJa.length()) add(idleJa.getString(i))
        }
        if (candidates.isEmpty()) return
        if (candidates.all { pathByAction.containsKey(it) }) {
            idleActions.clear()
            idleActions.addAll(candidates)
        }
    }

    private fun dispatchAction(action: String, full: JSONObject?) {
        when (action) {
            "idle" -> {
                if (isSpeaking) return // don't interrupt speaking
                isWorking = false
                startIdleLoop()
            }
            "working" -> {
                stopSpeaking()
                isWorking = true
                idleJob?.cancel()
                playAction("working")
            }
            "wave" -> {
                stopSpeaking()
                showExpanded()
                playAction("wave")
            }
            "speak" -> {
                val audioName = full?.optString("audio", "") ?: ""
                val audioUrl = full?.optString("audio_url", "") ?: ""
                if (audioName.isNotEmpty() || audioUrl.isNotEmpty()) {
                    playSpeakWithAudio(audioName, audioUrl)
                } else {
                    playAction("speak")
                }
            }
            else -> {
                stopSpeaking()
                playAction(action)
            }
        }
    }

    private fun playAction(action: String, isReaction: Boolean = true) {
        if (!pathByAction.containsKey(action)) {
            Log.w(TAG, "No local GIF for action: $action (pull from PC or check套装)")
            return
        }
        // Skip only if same as last AND it's an idle-triggered action
        if (action == lastAction && !isReaction) return
        lastAction = action
        loadGif(action)

        if (action != "working") {
            idleJob?.cancel()
            idleJob = scope.launch {
                // Reactions from Hermes/curl get extra display time before idle resumes
                val cooldownMs = if (isReaction) 4000L else 3000L
                delay(cooldownMs)
                if (!isWorking) scheduleNextIdle()
            }
        }
    }

    private fun startIdleLoop() {
        idleJob?.cancel()
        scheduleNextIdle()
    }

    private fun scheduleNextIdle() {
        idleJob?.cancel()
        if (idleActions.isEmpty()) return
        idleJob = scope.launch {
            delay((8000..15000).random().toLong())
            if (!isWorking) {
                var next = idleActions.random()
                var guard = 0
                while (next == lastAction && idleActions.size > 1 && guard++ < 8) {
                    next = idleActions.random()
                }
                lastAction = next
                loadGif(next)
                // Continue idle loop (isReaction=false: don't force-replay same action)
                idleJob?.cancel()
                idleJob = scope.launch {
                    delay(3000)
                    if (!isWorking) scheduleNextIdle()
                }
            }
        }
    }

    // === Speak with audio ===

    private fun playSpeakWithAudio(audioName: String, audioUrl: String) {
        idleJob?.cancel()
        isSpeaking = true

        // Show a transition animation while downloading (not speaking yet!)
        lastAction = "smile"
        loadGif("smile")

        // Build download URL
        val url = when {
            audioUrl.isNotEmpty() -> {
                // Replace localhost/127.0.0.1 with the connected host
                audioUrl
                    .replace("localhost", host)
                    .replace("127.0.0.1", host)
            }
            audioName.isNotEmpty() -> "http://$host:19851/audio/$audioName.mp3"
            else -> {
                isSpeaking = false
                return
            }
        }

        scope.launch {
            try {
                val audioFile = downloadAudio(url)
                if (!isSpeaking) return@launch // cancelled while downloading
                withContext(Dispatchers.Main) {
                    playAudioWithSpeakGif(audioFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download/play audio: ${e.message}")
                isSpeaking = false
                if (!isWorking) scheduleNextIdle()
            }
        }
    }

    private suspend fun downloadAudio(urlString: String): File {
        val fileName = urlString.substringAfterLast("/").substringBefore("?")
        val cacheDir = File(cacheDir, "audio")
        val cacheFile = File(cacheDir, fileName)
        if (cacheFile.exists()) return cacheFile

        cacheDir.mkdirs()

        // Download with timeout
        val connection = java.net.URL(urlString).openConnection()
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.connect()

        cacheFile.outputStream().use { output ->
            connection.getInputStream().use { input ->
                input.copyTo(output)
            }
        }
        Log.i(TAG, "Audio downloaded: ${cacheFile.name} (${cacheFile.length()} bytes)")
        return cacheFile
    }

    private fun playAudioWithSpeakGif(file: File) {
        releaseMediaPlayer()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    // Audio ready → switch to speak GIF and play simultaneously
                    lastAction = "speak"
                    loadGif("speak")
                    mp.start()
                    Log.i(TAG, "Audio playing: ${file.name}")
                }
                setOnCompletionListener {
                    Log.i(TAG, "Audio completed: ${file.name}")
                    isSpeaking = false
                    if (!isWorking) scheduleNextIdle()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Audio error: what=$what extra=$extra")
                    isSpeaking = false
                    if (!isWorking) scheduleNextIdle()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer error: ${e.message}")
            isSpeaking = false
            if (!isWorking) scheduleNextIdle()
        }
    }

    private fun stopSpeaking() {
        if (isSpeaking) {
            releaseMediaPlayer()
            isSpeaking = false
        }
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}
