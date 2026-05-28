package com.esp32.saqr

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.webkit.JavascriptInterface
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialDriver
import java.io.IOException
import java.util.concurrent.Executors

class SerialBridge(
    private val usbManager: UsbManager,
    private val onStatus: (String) -> Unit,
    private val onData: (String) -> Unit
) {
    private var driver: UsbSerialDriver? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var running = false

    @JavascriptInterface
    fun connect() {
        if (driver != null) { onStatus("already_connected"); return }

        val available = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (available.isEmpty()) {
            onStatus("no_device")
            return
        }

        try {
            val device = available[0].device
            if (!usbManager.hasPermission(device)) {
                onStatus("need_permission")
                return
            }

            driver = available[0].apply {
                usbManager.openDevice(device)?.let { usbSerial ->
                    port = ports[0]
                    port.open(usbSerial)
                    port.setParameters(115200, 8, 1, 0) // baud=115200, data=8, stop=1, parity=none
                }
            }

            running = true
            onStatus("connected")

            executor.execute {
                val buffer = ByteArray(1024)
                val sb = StringBuilder()
                while (running && driver != null) {
                    try {
                        val len = driver!!.port.read(buffer, 100)
                        if (len > 0) {
                            val text = String(buffer, 0, len)
                            sb.append(text)
                            // Send line by line
                            val str = sb.toString()
                            var idx: Int
                            while (str.indexOf('\n').also { idx = it } >= 0) {
                                val line = str.substring(0, idx).trim()
                                if (line.isNotEmpty()) onData(line)
                                sb.delete(0, idx + 1)
                            }
                        }
                    } catch (_: IOException) {
                        break
                    }
                }
            }

        } catch (e: Exception) {
            onStatus("error:${e.message}")
            driver = null
        }
    }

    @JavascriptInterface
    fun send(data: String) {
        executor.execute {
            try {
                driver?.port?.write((data + "\n").toByteArray(), 1000)
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun disconnect() {
        running = false
        try {
            driver?.port?.close()
            driver?.close()
        } catch (_: Exception) {}
        driver = null
        onStatus("disconnected")
    }
}
