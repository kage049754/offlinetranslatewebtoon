package com.rj.webtoontranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Runs the floating scan button and the translation overlay for as long as the
 * user wants live translation active, independent of MainActivity's lifecycle.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.rj.webtoontranslate.action.START"
        const val ACTION_STOP = "com.rj.webtoontranslate.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TARGET_LANGUAGE = "extra_target_language"

        private const val CHANNEL_ID = "live_translation_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAP_SLOP_PX = 16
        private const val LONG_PRESS_MS = 400L
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var windowManager: WindowManager
    private var floatingButton: ImageView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var translationView: TranslationOverlayView? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val engine = TranslationEngine()
    private var targetLanguage: String = LanguageOptions.DEFAULT_TARGET_CODE
    private var isProcessing = false
    private var isOverlayShowing = false

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                targetLanguage = intent.getStringExtra(EXTRA_TARGET_LANGUAGE) ?: targetLanguage
                startForeground(NOTIFICATION_ID, buildNotification())
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    setUpMediaProjection(resultCode, resultData)
                    setUpFloatingButton()
                    setUpTranslationLayer()
                }
            }
        }
        return START_STICKY
    }

    // ---- MediaProjection + capture pipeline ----------------------------------

    private fun setUpMediaProjection(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = manager.getMediaProjection(resultCode, resultData)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "WebtoonTranslateCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }

    /** Grabs whatever frame is currently buffered -- no continuous processing happens. */
    private fun captureLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } finally {
            image.close()
        }
    }

    // ---- Floating scan button --------------------------------------------

    private fun setUpFloatingButton() {
        if (floatingButton != null) return
        val button = ImageView(this).apply {
            setImageResource(R.drawable.ic_float_bubble)
            setBackgroundResource(R.drawable.floating_button_bg)
            alpha = 0.95f
        }
        val params = WindowManager.LayoutParams(
            140, 140,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L
        var moved = false

        button.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    downTime = System.currentTimeMillis()
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > TAP_SLOP_PX || abs(dy) > TAP_SLOP_PX) {
                        moved = true
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val heldMs = System.currentTimeMillis() - downTime
                    if (!moved) {
                        if (heldMs >= LONG_PRESS_MS) onButtonLongPress() else onButtonTap()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(button, params)
        floatingButton = button
        floatingParams = params
    }

    private fun setUpTranslationLayer() {
        if (translationView != null) return
        val view = TranslationOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            // NOT_TOUCHABLE is the key fix: this layer never claims touches, so the
            // webtoon app underneath keeps scrolling/tapping normally even while a
            // translation is being displayed on top of it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(view, params)
        translationView = view
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ---- Button actions -----------------------------------------------------

    private fun onButtonTap() {
        if (isProcessing) return
        isProcessing = true
        setButtonBusy(true)
        serviceScope.launch {
            try {
                val bitmap = captureLatestBitmap()
                if (bitmap == null) {
                    showToast("Couldn't read the screen yet -- try again")
                    return@launch
                }
                val (blocks, scriptHint) = engine.recognizeText(bitmap)
                if (blocks.isEmpty()) {
                    showToast("No text detected")
                    translationView?.clear()
                    isOverlayShowing = false
                    return@launch
                }
                val joined = blocks.joinToString(" ") { it.text }
                val sourceCode = engine.identifyLanguage(joined) ?: scriptHint ?: "en"
                val translated = engine.translateBlocks(blocks, sourceCode, targetLanguage, bitmap)
                translationView?.show(translated)
                isOverlayShowing = true
            } catch (t: Throwable) {
                showToast("Translation failed: ${t.message ?: "unknown error"}")
            } finally {
                isProcessing = false
                setButtonBusy(false)
            }
        }
    }

    /** Long-press clears the current overlay so scrolling feels exactly like before scanning. */
    private fun onButtonLongPress() {
        translationView?.clear()
        isOverlayShowing = false
        showToast("Translation cleared")
    }

    private fun setButtonBusy(busy: Boolean) {
        floatingButton?.post {
            floatingButton?.alpha = if (busy) 0.5f else 0.95f
        }
    }

    private fun showToast(message: String) {
        floatingButton?.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ---- Notification / lifecycle -------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running))
            .setSmallIcon(R.drawable.ic_notif_translate)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(stopReceiver) }
        floatingButton?.let { runCatching { windowManager.removeView(it) } }
        translationView?.let { runCatching { windowManager.removeView(it) } }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        engine.close()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
