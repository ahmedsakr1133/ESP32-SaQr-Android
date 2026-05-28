package com.esp32.saqr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var usbManager: UsbManager
    private var serialBridge: SerialBridge? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { serialBridge?.connect() }
                        webView.evaluateJavascript("AndroidUSB.onPermission(true)", null)
                    } else {
                        webView.evaluateJavascript("AndroidUSB.onPermission(false)", null)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))

        setupWebView()
        setupSerialBridge()
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)

        // Load HTML from assets via localhost (bypass CORS)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return false
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun setupSerialBridge() {
        serialBridge = SerialBridge(
            usbManager = usbManager,
            onStatus = { status ->
                runOnUiThread {
                    webView.evaluateJavascript("AndroidUSB.onStatus('$status')", null)
                }
            },
            onData = { data ->
                runOnUiThread {
                    val safe = data.replace("'", "\\'").replace("\n", "\\n")
                    webView.evaluateJavascript("AndroidUSB.onData('$safe')", null)
                }
            }
        )

        webView.addJavascriptInterface(serialBridge!!, "AndroidSerial")
    }

    override fun onDestroy() {
        serialBridge?.disconnect()
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.esp32.saqr.USB_PERMISSION"
    }
}
