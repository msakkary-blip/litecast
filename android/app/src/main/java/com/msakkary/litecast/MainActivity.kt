package com.msakkary.litecast

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {

    companion object {
        const val HOME = "https://msakkary-blip.github.io/litecast/"
        const val HOST = "msakkary-blip.github.io"
    }

    data class ScreenArgs(
        val w: Int, val h: Int, val fps: Int,
        val bubble: Boolean, val shape: String, val mirror: Boolean
    )

    private lateinit var web: WebView
    private var pendingWebPerm: PermissionRequest? = null
    private var afterPerms: (() -> Unit)? = null
    private var pendingArgs: ScreenArgs? = null

    // ---- permission / activity-result launchers ----
    private val webPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val req = pendingWebPerm ?: return@registerForActivityResult
            pendingWebPerm = null
            val ok = ArrayList<String>()
            for (r in req.resources) {
                if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && has(Manifest.permission.CAMERA)) ok.add(r)
                if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && has(Manifest.permission.RECORD_AUDIO)) ok.add(r)
            }
            if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
        }

    private val runtimeLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val next = afterPerms
            afterPerms = null
            next?.invoke()
        }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val a = pendingArgs ?: return@registerForActivityResult
            askProjection(a.copy(bubble = a.bubble && Settings.canDrawOverlays(this)))
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val a = pendingArgs
            pendingArgs = null
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null && a != null) {
                CaptureService.listener = { evt, d -> emit(evt, d) }
                val i = Intent(this, CaptureService::class.java)
                    .putExtra("code", res.resultCode)
                    .putExtra("data", data)
                    .putExtra("w", a.w).putExtra("h", a.h).putExtra("fps", a.fps)
                    .putExtra("bubble", a.bubble && has(Manifest.permission.CAMERA))
                    .putExtra("shape", a.shape).putExtra("mirror", a.mirror)
                startForegroundService(i)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                emit("screen", "denied")
            }
        }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun ensurePerms(perms: List<String>, then: () -> Unit) {
        val need = perms.filter { !has(it) }
        if (need.isEmpty()) then() else {
            afterPerms = then
            runtimeLauncher.launch(need.toTypedArray())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString LiteCastAndroid/1.0"
        }
        web.addJavascriptInterface(Bridge(), "LiteCastNative")
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermission(request) }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST) return false
                openExternal(u.toString())
                return true
            }
        }
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
            }
        })

        web.loadUrl(linkFrom(intent) ?: HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { web.loadUrl(it) }
    }

    private fun linkFrom(i: Intent?): String? {
        val d = i?.data ?: return null
        return if (d.host == HOST) d.toString() else null
    }

    override fun onDestroy() {
        CaptureService.listener = null
        super.onDestroy()
    }

    // NOTE: we intentionally do NOT pause the WebView in onPause/onStop, so a live class
    // and recording keep running while the teacher shows other apps.

    private fun handleWebPermission(req: PermissionRequest) {
        val perms = ArrayList<String>()
        for (r in req.resources) {
            if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE) perms.add(Manifest.permission.CAMERA)
            if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE) perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (perms.isEmpty()) { req.deny(); return }
        pendingWebPerm?.deny()
        pendingWebPerm = req
        webPermLauncher.launch(perms.toTypedArray())
    }

    private fun beginScreen(a: ScreenArgs) {
        val perms = ArrayList<String>()
        perms.add(Manifest.permission.RECORD_AUDIO)
        if (a.bubble) perms.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ensurePerms(perms) {
            if (a.bubble && has(Manifest.permission.CAMERA) && !Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Show your camera over other apps")
                    .setMessage("To show your face in a floating bubble while you present other apps, allow LiteCast to \"display over other apps\" on the next screen, then press Back.")
                    .setPositiveButton("Open settings") { _, _ ->
                        pendingArgs = a
                        overlayLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    }
                    .setNegativeButton("Continue without camera") { _, _ -> askProjection(a.copy(bubble = false)) }
                    .setCancelable(false)
                    .show()
            } else {
                askProjection(a)
            }
        }
    }

    private fun askProjection(a: ScreenArgs) {
        pendingArgs = a
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    fun emit(evt: String, data: String) {
        runOnUiThread {
            if (evt == "stopped" || (evt == "screen" && data != "started")) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            web.evaluateJavascript(
                "window.__litecastNative&&window.__litecastNative(${JSONObject.quote(evt)},${JSONObject.quote(data)})",
                null
            )
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- JavaScript bridge: window.LiteCastNative ----------------
    inner class Bridge {
        private var lastSeq = -1
        private val saves = ConcurrentHashMap<String, Pair<Uri, OutputStream>>()

        @JavascriptInterface
        fun isAndroid(): Boolean = true

        @JavascriptInterface
        fun startScreen(w: Int, h: Int, fps: Int, bubble: Boolean, shape: String, mirror: Boolean) {
            runOnUiThread { beginScreen(ScreenArgs(w, h, fps, bubble, shape, mirror)) }
        }

        @JavascriptInterface
        fun stopScreen() {
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                CaptureService.instance?.stopAll(false)
            }
        }

        @JavascriptInterface
        fun frame(): String {
            val s = CaptureService.frameSeq
            if (s == lastSeq) return ""
            lastSeq = s
            return CaptureService.frame.get() ?: ""
        }

        @JavascriptInterface
        fun bubbleVisible(v: Boolean) {
            runOnUiThread { CaptureService.instance?.setBubbleVisible(v) }
        }

        @JavascriptInterface
        fun saveBegin(name: String, mime: String): String {
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LiteCast")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return ""
                val os = contentResolver.openOutputStream(uri) ?: return ""
                val id = UUID.randomUUID().toString()
                saves[id] = Pair(uri, os)
                id
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun saveChunk(id: String, b64: String): Boolean {
            val s = saves[id] ?: return false
            return try {
                s.second.write(Base64.decode(b64, Base64.DEFAULT))
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun saveEnd(id: String): String {
            val s = saves.remove(id) ?: return ""
            return try {
                s.second.flush()
                s.second.close()
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(s.first, values, null, null)
                s.first.toString()
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun share(uri: String, mime: String, text: String) {
            runOnUiThread {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = if (mime.isBlank()) "video/*" else mime
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                        putExtra(Intent.EXTRA_TEXT, text)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Share recording"))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Sharing failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                val u = Uri.parse(url)
                if (u.host == HOST) web.loadUrl(url) else openExternal(url)
            }
        }
    }
}
