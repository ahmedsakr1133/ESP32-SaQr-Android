package com.esp32.saqr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.io.BufferedReader
import java.io.InputStreamReader

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

    private val htmlPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadCustomHtml(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.title = "ESP32-SaQr"

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))

        setupWebView()
        setupSerialBridge()

        // Load saved custom HTML or default
        val savedUri = getSavedHtmlUri()
        if (savedUri != null) {
            loadCustomHtml(savedUri)
        } else {
            loadDefaultHtml()
        }
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }
    }

    private fun loadDefaultHtml() {
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun loadCustomHtml(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            reader.close()
            webView.loadDataWithBaseURL(null, sb.toString(), "text/html", "UTF-8", null)
            saveHtmlUri(uri)
            supportActionBar?.subtitle = uri.lastPathSegment ?: "مخصص"
        } catch (e: Exception) {
            webView.evaluateJavascript("AndroidUSB.onStatus('error:${e.message}')", null)
        }
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_html -> {
                htmlPicker.launch(arrayOf("text/html", "text/*"))
                true
            }
            R.id.action_reset_html -> {
                clearSavedHtmlUri()
                supportActionBar?.subtitle = null
                loadDefaultHtml()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveHtmlUri(uri: Uri) {
        getPreferences(Context.MODE_PRIVATE).edit()
            .putString("html_uri", uri.toString())
            .apply()
    }

    private fun getSavedHtmlUri(): Uri? {
        val uriStr = getPreferences(Context.MODE_PRIVATE)
            .getString("html_uri", null) ?: return null
        return Uri.parse(uriStr)
    }

    private fun clearSavedHtmlUri() {
        getPreferences(Context.MODE_PRIVATE).edit()
            .remove("html_uri")
            .apply()
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
