package com.msakkary.litecast

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that owns the screen capture (MediaProjection) and the
 * floating camera bubble. Frames are JPEG-encoded and handed to the WebView
 * through MainActivity.Bridge.frame().
 */
class CaptureService : LifecycleService() {

    companion object {
        const val CHANNEL = "litecast_capture"
        const val NOTIF_ID = 7
        const val ACTION_STOP = "com.msakkary.litecast.STOP"

        @Volatile var instance: CaptureService? = null
        @Volatile var listener: ((String, String) -> Unit)? = null
        val frame = AtomicReference<String?>(null)
        @Volatile var frameSeq = 0
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    private var maxW = 1280
    private var maxH = 720
    private var fps = 12
    private var lastFrameAt = 0L
    private var curW = 0
    private var curH = 0
    private var stopping = false

    private var bubble: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var bubbleSizes = intArrayOf(96, 128, 168)
    private var bubbleSizeIdx = 1

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) handler?.post { resizeIfRotated() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopAll(true)
            return START_NOT_STICKY
        }
        if (projection != null || intent == null) return START_NOT_STICKY
        instance = this
        stopping = false

        maxW = intent.getIntExtra("w", 1280)
        maxH = intent.getIntExtra("h", 720)
        fps = intent.getIntExtra("fps", 12).coerceIn(5, 30)
        val wantBubble = intent.getBooleanExtra("bubble", false)
        val shape = intent.getStringExtra("shape") ?: "circle"
        val mirror = intent.getBooleanExtra("mirror", true)
        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("data")

        createChannel()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 30) {
            if (wantBubble && granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), types)
        } catch (e: Exception) {
            listener?.invoke("screen", "error:" + e.message)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data!!)
            projection = p
            val t = HandlerThread("LiteCastCapture")
            t.start()
            thread = t
            handler = Handler(t.looper)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopAll(true) }
                }
            }, handler)
            startDisplay()
            getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        } catch (e: Exception) {
            listener?.invoke("screen", "error:" + e.message)
            stopAll(false)
            return START_NOT_STICKY
        }

        if (wantBubble) showBubble(shape, mirror)
        listener?.invoke("screen", "started")
        return START_NOT_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------------- screen capture ----------------
    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        return dm
    }

    private fun targetSize(sw: Int, sh: Int): Pair<Int, Int> {
        val big = max(maxW, maxH)
        val small = min(maxW, maxH)
        val bw = if (sh > sw) small else big
        val bh = if (sh > sw) big else small
        val sc = min(1f, min(bw.toFloat() / sw, bh.toFloat() / sh))
        val w = max(2, (sw * sc / 2).toInt() * 2)
        val h = max(2, (sh * sc / 2).toInt() * 2)
        return Pair(w, h)
    }

    @SuppressLint("WrongConstant")
    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        return r
    }

    private fun startDisplay() {
        val dm = realMetrics()
        val (w, h) = targetSize(dm.widthPixels, dm.heightPixels)
        curW = w; curH = h
        val r = newReader(w, h)
        reader = r
        display = projection!!.createVirtualDisplay(
            "LiteCast", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    private fun resizeIfRotated() {
        val d = display ?: return
        val dm = realMetrics()
        val (w, h) = targetSize(dm.widthPixels, dm.heightPixels)
        if (w == curW && h == curH) return
        curW = w; curH = h
        val old = reader
        val r = newReader(w, h)
        reader = r
        d.resize(w, h, dm.densityDpi)
        d.surface = r.surface
        old?.close()
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 1000L / fps) return
            lastFrameAt = now
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPad = plane.rowStride - pixelStride * img.width
            val full = Bitmap.createBitmap(img.width + rowPad / pixelStride, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (rowPad > 0) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
            val out = ByteArrayOutputStream(64 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, 72, out)
            if (bmp !== full) bmp.recycle()
            full.recycle()
            frame.set(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            frameSeq++
        } catch (e: Exception) {
            // skip this frame
        } finally {
            img.close()
        }
    }

    // ---------------- floating camera bubble ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble(shape: String, mirror: Boolean) {
        if (!Settings.canDrawOverlays(this) || !granted(Manifest.permission.CAMERA)) return
        val wm = getSystemService(WindowManager::class.java)
        val size = dp(bubbleSizes[bubbleSizeIdx])
        val radius = if (shape == "rounded") size * 0.16f else 0f

        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            if (!mirror) scaleX = -1f
        }
        val box = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    if (shape == "circle") o.setOval(0, 0, v.width, v.height)
                    else o.setRoundRect(0, 0, v.width, v.height, if (shape == "rounded") v.width * 0.16f else 0f)
                }
            }
            foreground = GradientDrawable().apply {
                this.shape = if (shape == "circle") GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), Color.WHITE)
            }
        }
        val dm = realMetrics()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - size - dp(16)
            y = dm.heightPixels - size - dp(140)
        }

        // drag to move, tap to change size
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        box.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    bubbleSizeIdx = (bubbleSizeIdx + 1) % bubbleSizes.size
                    val s = dp(bubbleSizes[bubbleSizeIdx])
                    lp.width = s; lp.height = s
                    wm.updateViewLayout(v, lp)
                    v.invalidateOutline()
                }
            }
            true
        }

        try {
            wm.addView(box, lp)
        } catch (e: Exception) {
            return
        }
        bubble = box
        bubbleParams = lp

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val p = Preview.Builder().build()
                p.setSurfaceProvider(preview.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, p)
                cameraProvider = provider
            } catch (e: Exception) {
                // no front camera: keep the bubble black
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun setBubbleVisible(v: Boolean) {
        bubble?.visibility = if (v) View.VISIBLE else View.GONE
    }

    private fun removeBubble() {
        try { cameraProvider?.unbindAll() } catch (e: Exception) {}
        cameraProvider = null
        bubble?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (e: Exception) {} }
        bubble = null
    }

    // ---------------- notification & shutdown ----------------
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("LiteCast is sharing your screen")
            .setContentText("Tap to return to LiteCast · Stop ends recording and live class")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    /** @param notifyPage true when the stop came from Android (notification, system), so the page must finish recording/live. */
    fun stopAll(notifyPage: Boolean) {
        if (stopping) return
        stopping = true
        if (notifyPage) listener?.invoke("stopped", "")
        removeBubble()
        try { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) } catch (e: Exception) {}
        try { display?.release() } catch (e: Exception) {}
        display = null
        try { reader?.close() } catch (e: Exception) {}
        reader = null
        val p = projection
        projection = null
        try { p?.stop() } catch (e: Exception) {}
        thread?.quitSafely()
        thread = null
        frame.set(null)
        instance = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) stopAll(true)
        super.onDestroy()
    }
}
