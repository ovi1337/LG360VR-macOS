package com.lg360vr.tester.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Connection { DISCONNECTED, PERMISSION_PENDING, NO_DEVICE, CONNECTED, ERROR }

enum class LogSource { SENT, RECV, INFO, ERROR }

data class LogLine(
    val time: String,
    val source: LogSource,
    val text: String,
)

/**
 * Owns the USB host session with the LG 360 VR: permission handling, opening the
 * HID interface, sending framed commands and continuously reading the device's
 * ASCII debug stream from the interrupt IN endpoint.
 */
class UsbController(private val context: Context) {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow(Connection.DISCONNECTED)
    val state: StateFlow<Connection> = _state.asStateFlow()

    private val _descriptor = MutableStateFlow<DeviceDescriptor?>(null)
    val descriptor: StateFlow<DeviceDescriptor?> = _descriptor.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val _status = MutableStateFlow("Bereit")
    val status: StateFlow<String> = _status.asStateFlow()

    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var device: UsbDevice? = null

    @Volatile private var reading = false
    private var readThread: Thread? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val recvBuffer = StringBuilder()

    // ---- Permission handling ---------------------------------------------

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && dev != null) {
                open(dev)
            } else {
                addLog(LogSource.ERROR, "USB-Berechtigung verweigert")
                _state.value = Connection.DISCONNECTED
                _status.value = "Berechtigung verweigert"
            }
        }
    }

    fun register() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permissionReceiver, filter)
        }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(permissionReceiver) }
        close()
    }

    // ---- Device discovery / connect --------------------------------------

    fun findDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull {
        it.vendorId == Lg360Protocol.VENDOR_ID && it.productId == Lg360Protocol.PRODUCT_ID
    }

    /** Called from UI (button) or from an ATTACHED intent. */
    fun connect(explicit: UsbDevice? = null) {
        val dev = explicit ?: findDevice()
        if (dev == null) {
            _state.value = Connection.NO_DEVICE
            _status.value = "Keine LG 360 VR gefunden (VID 0x1004 / PID 0x6374)"
            return
        }
        if (usbManager.hasPermission(dev)) {
            open(dev)
        } else {
            _state.value = Connection.PERMISSION_PENDING
            _status.value = "Warte auf USB-Berechtigung…"
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
            )
            usbManager.requestPermission(dev, pi)
        }
    }

    private fun open(dev: UsbDevice) {
        close()
        val conn = usbManager.openDevice(dev)
        if (conn == null) {
            _state.value = Connection.ERROR
            _status.value = "openDevice() fehlgeschlagen"
            addLog(LogSource.ERROR, "Konnte Gerät nicht öffnen")
            return
        }

        // Pick the HID (or first vendor) interface that has interrupt IN + OUT.
        var chosen: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            var ci: UsbEndpoint? = null
            var co: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN && ci == null) ci = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && co == null) co = ep
            }
            if (ci != null && co != null) {
                chosen = itf; inEp = ci; outEp = co
                if (itf.interfaceClass == UsbConstants.USB_CLASS_HID) break
            }
        }
        if (chosen == null || inEp == null || outEp == null) {
            _state.value = Connection.ERROR
            _status.value = "Kein passendes Interface (IN+OUT) gefunden"
            conn.close()
            return
        }

        // force=true detaches Android's kernel HID driver so we own the interface.
        if (!conn.claimInterface(chosen, true)) {
            _state.value = Connection.ERROR
            _status.value = "claimInterface() fehlgeschlagen"
            conn.close()
            return
        }

        connection = conn
        iface = chosen
        epIn = inEp
        epOut = outEp
        device = dev
        _descriptor.value = UsbDescribe.describe(dev, conn.serial)
        _state.value = Connection.CONNECTED
        _status.value = "Verbunden – ${dev.productName ?: "LG 360 VR"}"
        addLog(LogSource.INFO, "Verbunden. IN=${hex(inEp.address)} OUT=${hex(outEp.address)} " +
                "iface=${chosen.id}/${UsbDescribe.className(chosen.interfaceClass)}")
        startReading()
    }

    fun close() {
        stopReading()
        iface?.let { runCatching { connection?.releaseInterface(it) } }
        runCatching { connection?.close() }
        connection = null; iface = null; epIn = null; epOut = null; device = null
        if (_state.value == Connection.CONNECTED) _state.value = Connection.DISCONNECTED
    }

    val isConnected: Boolean get() = connection != null && epOut != null

    // ---- Sending ---------------------------------------------------------

    /** Send a single framed command. Returns bytes written (<0 on failure). */
    fun send(command: String): Int {
        val conn = connection ?: return -1
        val out = epOut ?: return -1
        val report = Lg360Protocol.buildReport(command)
        var written = conn.bulkTransfer(out, report, report.size, TIMEOUT_MS)
        if (written < 0) {
            // Fallback: HID SET_REPORT control transfer (bmRequestType 0x21, SET_REPORT 0x09).
            val wValue = (0x02 shl 8) or (Lg360Protocol.REPORT_ID.toInt() and 0xFF)
            written = conn.controlTransfer(
                0x21, 0x09, wValue, iface?.id ?: 0, report, report.size, TIMEOUT_MS
            )
        }
        if (written < 0) {
            addLog(LogSource.ERROR, "Senden fehlgeschlagen: \"$command\"")
        } else {
            addLog(LogSource.SENT, "\"$command\"  (${written}B)")
        }
        return written
    }

    /** Send the activation sequence (Sleep Disable, VR App Start), [repeat] times. */
    fun activate(repeat: Int = 6, gapMs: Long = 120) {
        Thread {
            addLog(LogSource.INFO, "Aktivierung startet (${repeat}×)…")
            repeat(repeat) {
                for (cmd in Lg360Protocol.ACTIVATION) {
                    send(cmd)
                    Thread.sleep(gapMs)
                }
            }
            addLog(LogSource.INFO, "Aktivierungssequenz beendet")
        }.apply { isDaemon = true; name = "vr-activate" }.start()
    }

    // ---- Reading the debug stream ---------------------------------------

    private fun startReading() {
        val conn = connection ?: return
        val inEp = epIn ?: return
        reading = true
        readThread = Thread {
            val request = UsbRequest()
            if (!request.initialize(conn, inEp)) {
                addLog(LogSource.ERROR, "UsbRequest.initialize() fehlgeschlagen")
                return@Thread
            }
            val size = maxOf(inEp.maxPacketSize, 64)
            val buffer = ByteBuffer.allocate(size)
            while (reading) {
                buffer.clear()
                if (!request.queue(buffer)) { sleepQuiet(20); continue }
                val done = conn.requestWait()
                if (done !== request) { sleepQuiet(5); continue }
                val len = buffer.position()
                if (len > 0) handleIncoming(buffer.array(), len)
                else sleepQuiet(5)
            }
            runCatching { request.close() }
        }.apply { isDaemon = true; name = "vr-read" }
        readThread?.start()
    }

    private fun stopReading() {
        reading = false
        readThread?.let { runCatching { it.join(300) } }
        readThread = null
        flushRecv()
    }

    /** Device streams ASCII with a report-id first byte; strip it and split on newlines. */
    private fun handleIncoming(raw: ByteArray, len: Int) {
        var start = 0
        // First byte is often the report id (3/4/5) – skip it if non-printable.
        if (len > 0 && (raw[0].toInt() in 3..5)) start = 1
        for (i in start until len) {
            val b = raw[i].toInt() and 0xFF
            when {
                b == 0 -> { /* padding */ }
                b == 0x0A || b == 0x0D -> flushRecv()
                b in 0x20..0x7E -> recvBuffer.append(b.toChar())
            }
        }
        // Flush if buffer grows without newline (some firmware lines omit \n).
        if (recvBuffer.length > 120) flushRecv()
    }

    private fun flushRecv() {
        if (recvBuffer.isNotEmpty()) {
            val line = recvBuffer.toString().trim()
            recvBuffer.setLength(0)
            if (line.isNotEmpty()) addLog(LogSource.RECV, line)
        }
    }

    // ---- Logging ---------------------------------------------------------

    private fun addLog(source: LogSource, text: String) {
        val line = LogLine(timeFmt.format(Date()), source, text)
        val current = _log.value
        val next = if (current.size >= MAX_LOG) current.drop(current.size - MAX_LOG + 1) + line
        else current + line
        _log.value = next
        Log.d("LG360VR", "[$source] $text")
    }

    fun clearLog() { _log.value = emptyList() }

    private fun sleepQuiet(ms: Long) = runCatching { Thread.sleep(ms) }
    private fun hex(v: Int) = "0x%02X".format(v)

    companion object {
        private const val ACTION_USB_PERMISSION = "com.lg360vr.tester.USB_PERMISSION"
        private const val TIMEOUT_MS = 500
        private const val MAX_LOG = 1000
    }
}
