package com.dreamcoat.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dreamcoat.hid.HidDescriptor.REPORT_ID_KEYBOARD
import com.dreamcoat.hid.HidDescriptor.REPORT_ID_MOUSE
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Registers the phone as a composite Bluetooth HID keyboard + mouse and sends reports to the
 * connected host. All reports go through one single-threaded queue so a typed string and mouse
 * movement never interleave mid-keystroke.
 *
 * Callers must hold BLUETOOTH_CONNECT (checked by the dashboard before the service starts).
 */
@SuppressLint("MissingPermission")
class HidBridgeManager(private val context: Context) {

    enum class Status { UNAVAILABLE, REGISTERING, REGISTERED, CONNECTING, CONNECTED }

    data class State(val status: Status, val hostName: String? = null)

    var onStateChanged: ((State) -> Unit)? = null
    var state = State(Status.UNAVAILABLE)
        private set

    /** Delay between keystrokes; 20 ms keeps corporate Windows and browsers from dropping keys. */
    @Volatile var interKeyDelayMs = 20L

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = context.getSharedPreferences("hid", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "hid-sender") }

    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var mouseButtons = 0

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "appStatus registered=$registered plugged=${pluggedDevice?.address}")
            if (!registered) {
                host = null
                setState(State(Status.REGISTERING))
                return
            }
            setState(State(Status.REGISTERED))
            (pluggedDevice ?: lastHost())?.let { connect(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, newState: Int) {
            Log.i(TAG, "connection ${device.address} -> $newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    prefs.edit().putString(KEY_LAST_HOST, device.address).apply()
                    setState(State(Status.CONNECTED, device.name ?: device.address))
                }
                BluetoothProfile.STATE_CONNECTING ->
                    setState(State(Status.CONNECTING, device.name ?: device.address))
                else -> if (host == null || host == device) {
                    host = null
                    setState(State(Status.REGISTERED))
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val size = if (id.toInt() == REPORT_ID_MOUSE) 4 else 8
            hid?.replyReport(device, type, id, ByteArray(size))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // Keyboard LED (caps lock etc.) output reports; acknowledge and ignore.
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            hid = proxy as BluetoothHidDevice
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
            setState(State(Status.UNAVAILABLE))
        }
    }

    fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            setState(State(Status.UNAVAILABLE))
            return
        }
        setState(State(Status.REGISTERING))
        if (!a.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)) {
            Log.e(TAG, "HID Device profile not supported on this phone")
            setState(State(Status.UNAVAILABLE))
        }
    }

    fun stop() {
        hid?.let {
            it.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
        }
        hid = null
        host = null
        sender.shutdownNow()
    }

    private fun register() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Dreamcoat", "Jacquard sleeve bridge", "Dreamcoat",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.DESCRIPTOR,
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250,
            BluetoothHidDeviceAppQosSettings.MAX,
        )
        val ok = hid?.registerApp(sdp, null, qos, { it.run() }, callback) ?: false
        Log.i(TAG, "registerApp -> $ok")
    }

    private fun lastHost(): BluetoothDevice? =
        prefs.getString(KEY_LAST_HOST, null)?.let { addr ->
            adapter?.bondedDevices?.firstOrNull { it.address == addr }
        }

    /** Reconnects to the last laptop, if it is still paired. */
    fun reconnect() {
        lastHost()?.let { connect(it) }
    }

    private fun connect(device: BluetoothDevice) {
        if (host != null) return
        hid?.connect(device)
    }

    fun isConnected() = host != null

    // ---- Keyboard ----

    fun sendKeyTap(usageId: Int, modifier: Int = 0) {
        sender.execute { tap(modifier, usageId) }
    }

    fun typeTextString(text: String, pressEnterAtEnd: Boolean) {
        val strokes = KeyMap.normalize(text).mapNotNull { c ->
            KeyMap.strokeFor(c) ?: run { Log.w(TAG, "Skipping untypeable char '$c'"); null }
        }
        sender.execute {
            for (s in strokes) {
                tap(s.modifier, s.usage)
                Thread.sleep(interKeyDelayMs)
            }
            if (pressEnterAtEnd) tap(0, KeyMap.ENTER)
        }
    }

    private fun tap(modifier: Int, usage: Int) {
        keyboardReport(modifier, usage)
        Thread.sleep(KEY_HOLD_MS)
        keyboardReport(0, 0)
    }

    private fun keyboardReport(modifier: Int, usage: Int) {
        val report = ByteArray(8)
        report[0] = modifier.toByte()
        report[2] = usage.toByte()
        send(REPORT_ID_KEYBOARD, report)
    }

    // ---- Mouse ----

    fun sendMouseDelta(dx: Int, dy: Int, leftButton: Boolean, rightButton: Boolean, scroll: Int = 0) {
        mouseButtons = (if (leftButton) 1 else 0) or (if (rightButton) 2 else 0)
        moveMouse(dx, dy, scroll)
    }

    /** Moves the cursor, keeping whatever buttons are currently held (so drags work). */
    fun moveMouse(dx: Int, dy: Int, scroll: Int = 0) {
        val buttons = mouseButtons
        sender.execute {
            var rx = dx
            var ry = dy
            var rs = scroll
            do {
                val sx = rx.coerceIn(-127, 127)
                val sy = ry.coerceIn(-127, 127)
                val ss = rs.coerceIn(-127, 127)
                send(REPORT_ID_MOUSE, byteArrayOf(buttons.toByte(), sx.toByte(), sy.toByte(), ss.toByte()))
                rx -= sx; ry -= sy; rs -= ss
            } while (abs(rx) + abs(ry) + abs(rs) > 0)
        }
    }

    fun setMouseButton(button: Int, pressed: Boolean) {
        mouseButtons = if (pressed) mouseButtons or button else mouseButtons and button.inv()
        moveMouse(0, 0)
    }

    fun click(button: Int) {
        setMouseButton(button, true)
        sender.execute { Thread.sleep(KEY_HOLD_MS) }
        setMouseButton(button, false)
    }

    private fun send(id: Int, report: ByteArray) {
        val d = host ?: return
        hid?.sendReport(d, id, report)
    }

    private fun setState(s: State) {
        state = s
        main.post { onStateChanged?.invoke(s) }
    }

    companion object {
        private const val TAG = "HidBridge"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_HOLD_MS = 15L
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 2
        const val BUTTON_MIDDLE = 4
    }
}
