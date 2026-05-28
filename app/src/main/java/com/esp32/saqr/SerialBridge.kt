package com.esp32.saqr

import android.hardware.usb.UsbManager
import android.webkit.JavascriptInterface
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.util.concurrent.Executors

class SerialBridge(
    private val usbManager: UsbManager,
    private val onStatus: (String) -> Unit,
    private val onData: (String) -> Unit
) {
    private var port: UsbSerialPort? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var running = false

    @JavascriptInterface
    fun connect() {
        if (port != null) { onStatus("already_connected"); return }

        val available = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (available.isEmpty()) {
            onStatus("no_device")
            return
        }

        try {
            val driver = available[0]
            val device = driver.device
            if (!usbManager.hasPermission(device)) {
                onStatus("need_permission")
                return
            }

            val usbConnection = usbManager.openDevice(device)
            if (usbConnection == null) {
                onStatus("error:failed to open connection")
                return
            }

            val serialPort = driver.ports[0]
            serialPort.open(usbConnection)
            serialPort.setParameters(115200, 8, 1, 0)

            port = serialPort
            running = true
            onStatus("connected")

            executor.execute {
                val buffer = ByteArray(1024)
                val sb = StringBuilder()
                while (running && port != null) {
                    try {
                        val len = port!!.read(buffer, 100)
                        if (len > 0) {
                            val text = String(buffer, 0, len)
                            sb.append(text)
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
            port = null
        }
    }

    @JavascriptInterface
    fun send(data: String) {
        executor.execute {
            try {
                port?.write((data + "\n").toByteArray(), 1000)
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun disconnect() {
        running = false
        try {
            port?.close()
        } catch (_: Exception) {}
        port = null
        onStatus("disconnected")
    }
}
