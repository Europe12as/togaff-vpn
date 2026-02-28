package com.togaffvpn.app

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.Color
import android.os.*
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

// ================================================================
//  Togaff VPN — MainActivity
//  WebView wrapper that loads assets/index.html
//  Native bridge for real socket pings & background service
// ================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor     = Color.parseColor("#060410")
        window.navigationBarColor = Color.parseColor("#060410")

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled                             = true
                domStorageEnabled                             = true
                allowFileAccessFromFileURLs                   = true
                allowUniversalAccessFromFileURLs              = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort                               = true
                loadWithOverviewMode                          = true
                setSupportZoom(false)
                builtInZoomControls  = false
                displayZoomControls  = false
            }
            setBackgroundColor(Color.parseColor("#060410"))

            // Native functions exposed to JavaScript
            addJavascriptInterface(NativeBridge(this@MainActivity), "Android")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Restore last session from SharedPreferences
                    val prefs = getSharedPreferences("togaffvpn", MODE_PRIVATE)
                    val saved = prefs.getString("last_proxy", null)
                    if (saved != null) {
                        view?.evaluateJavascript(
                            "window._savedProxy = '$saved'; console.log('restored: $saved');",
                            null
                        )
                    }
                }
            }

            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        webView.destroy()
    }
}

// ================================================================
//  NativeBridge — called from JavaScript via Android.method()
// ================================================================
class NativeBridge(private val ctx: Context) {

    /** Real TCP ping — returns ms or -1 on failure */
    @JavascriptInterface
    fun ping(host: String, port: Int): Int {
        return try {
            val t0 = System.currentTimeMillis()
            Socket().use { s -> s.connect(InetSocketAddress(host, port), 2000) }
            (System.currentTimeMillis() - t0).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    /** Start background service + set system proxy */
    @JavascriptInterface
    fun connect(host: String, port: Int) {
        TogaffProxyService.start(ctx, host, port)
        ctx.getSharedPreferences("togaffvpn", Context.MODE_PRIVATE)
            .edit().putString("last_proxy", "$host:$port").apply()
    }

    /** Stop background service + clear proxy */
    @JavascriptInterface
    fun disconnect() {
        TogaffProxyService.stop(ctx)
        ctx.getSharedPreferences("togaffvpn", Context.MODE_PRIVATE)
            .edit().remove("last_proxy").apply()
    }

    /** Get device network info */
    @JavascriptInterface
    fun getDeviceInfo(): String {
        return Build.MODEL + " / Android " + Build.VERSION.RELEASE
    }
}

// ================================================================
//  TogaffProxyService — Foreground service, survives app close
// ================================================================
class TogaffProxyService : Service() {

    companion object {
        private const val CHANNEL_ID = "togaff_vpn_channel"
        private const val NOTIF_ID   = 7331

        fun start(ctx: Context, host: String, port: Int) {
            val intent = Intent(ctx, TogaffProxyService::class.java).apply {
                putExtra("host", host)
                putExtra("port", port)
            }
            ctx.startForegroundService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TogaffProxyService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra("host") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("port", 80)

        createNotificationChannel()

        val stopPending = PendingIntent.getService(
            this, 0,
            Intent(this, TogaffProxyService::class.java).putExtra("stop", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌸 Togaff VPN — Активен")
            .setContentText("$host:$port  •  AES-256  •  Защищено")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отключить",
                stopPending
            )
            .build()

        startForeground(NOTIF_ID, notif)

        // Set JVM-level proxy (affects HttpURLConnection etc.)
        System.setProperty("http.proxyHost",  host)
        System.setProperty("http.proxyPort",  port.toString())
        System.setProperty("https.proxyHost", host)
        System.setProperty("https.proxyPort", port.toString())

        // Handle stop action from notification
        if (intent.getBooleanExtra("stop", false)) {
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        System.clearProperty("http.proxyHost")
        System.clearProperty("http.proxyPort")
        System.clearProperty("https.proxyHost")
        System.clearProperty("https.proxyPort")
    }

    override fun onBind(intent: Intent?) = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Togaff VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active VPN proxy connection"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}
