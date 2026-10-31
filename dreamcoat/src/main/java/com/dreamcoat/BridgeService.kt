package com.dreamcoat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dreamcoat.airmouse.AirMouse
import com.dreamcoat.hid.HidBridgeManager
import com.dreamcoat.hid.HidDescriptor.MOD_LEFT_CTRL
import com.dreamcoat.hid.HidDescriptor.MOD_LEFT_SHIFT
import com.dreamcoat.hid.KeyMap
import com.dreamcoat.watch.WatchLink
import com.google.android.jacquard.sdk.model.Gesture.GestureType
import com.dreamcoat.jacquard.GestureRouter
import com.dreamcoat.jacquard.JacquardLink
import com.dreamcoat.jacquard.Mode
import com.dreamcoat.presenter.VolumeClicker
import androidx.media.app.NotificationCompat.MediaStyle
import com.google.android.jacquard.sdk.model.TouchData
import com.google.android.jacquard.sdk.model.TouchMode

/**
 * Foreground service that owns all Bluetooth state (HID link to the laptop, the watch, and the BLE
 * link to the jacket), so everything keeps working with the screen off and the phone in a pocket.
 * Activities bind to it and observe [state].
 */
class BridgeService : Service() {

    inner class LocalBinder : Binder() {
        val service get() = this@BridgeService
    }

    data class State(
        val hid: HidBridgeManager.State,
        /** Null when the app is built without jacket support. */
        val tag: JacquardLink.Status?,
        val mode: Mode,
        val sleeveAirMouse: Boolean,
        val queue: List<String>,
        val watchName: String?,
    )

    private val binder = LocalBinder()

    lateinit var hid: HidBridgeManager
        private set
    lateinit var airMouse: AirMouse
        private set
    /** The jacket tag link; null when built without jacket support (BuildConfig.JACKET). */
    var link: JacquardLink? = null
        private set
    lateinit var router: GestureRouter
        private set
    private lateinit var watch: WatchLink

    var onState: ((State) -> Unit)? = null

    private val promptQueue = ArrayDeque<String>()
    private var sleeveAirMouse = false
    private var screenClutch = false
    private var sleeveClutch = false

    val state: State
        get() = State(hid.state, link?.status, router.mode, sleeveAirMouse, promptQueue.toList(),
            if (::watch.isInitialized) watch.watchName else null)

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hid = HidBridgeManager(this).apply {
            interKeyDelayMs = prefs.getLong(PREF_KEY_DELAY, 20L)
            onStateChanged = { publish() }
        }
        airMouse = AirMouse(this) { dx, dy -> hid.moveMouse(dx, dy) }.apply {
            sensitivity = prefs.getFloat(PREF_SENSITIVITY, AirMouse.DEFAULT_SENSITIVITY)
        }
        // Without jacket support the Jacquard SDK is never touched: no BLE scans, no SDK logger.
        link = if (BuildConfig.JACKET) JacquardLink(this) else null
        router = GestureRouter(prefs, hid, link, ::sendNextPrompt, ::resumeShow) { publish() }
        link?.apply {
            onStatus = { publish() }
            onGesture = { g -> if (!sleeveAirMouse) router.onGesture(g) }
            onTouch = ::onSleeveTouch
        }

        // The watch stands in for the jacket: its gestures go through the same mappings.
        watch = WatchLink(this).apply {
            stateProvider = {
                val h = hid.state
                val laptop = if (h.status == HidBridgeManager.Status.CONNECTED) h.hostName ?: "Laptop" else ""
                "$laptop|${router.mode.name}"
            }
            onWatchChanged = { publish() }
            onGesture = { g ->
                router.onGesture(
                    when (g) {
                        WatchLink.Gesture.NEXT -> GestureType.BRUSH_OUT
                        WatchLink.Gesture.BACK -> GestureType.BRUSH_IN
                        WatchLink.Gesture.SELECT -> GestureType.DOUBLE_TAP
                        WatchLink.Gesture.MODE -> GestureType.SHORT_COVER
                    },
                    fromWatch = true,
                )
            }
            start()
        }

        clicker = VolumeClicker(this, ::nextSlide, ::previousSlide).apply {
            enabled = prefs.getBoolean(PREF_VOLUME_KEYS, true)
        }

        hid.start()
        link?.connectSaved()
        publish()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_NEXT -> nextSlide()
            ACTION_BACK -> previousSlide()
            ACTION_QUIT -> quit()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        airMouse.setClutch(false)
        clicker.release()
        watch.stop()
        link?.destroy()
        hid.stop()
        super.onDestroy()
    }

    /** Set by the open dashboard so Quit from the notification also closes the screen (and unbinds). */
    var onQuit: (() -> Unit)? = null
    private var quitting = false

    /**
     * Shuts everything down: disconnects the laptop, gives the volume keys back, closes the watch
     * app. The service is destroyed once the dashboard (if open) unbinds.
     */
    fun quit() {
        quitting = true
        watch.sendQuit()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        onQuit?.invoke()
    }

    // ---- Presenter ----

    lateinit var clicker: VolumeClicker
        private set

    fun nextSlide() = hid.sendKeyTap(KeyMap.RIGHT_ARROW)

    fun previousSlide() = hid.sendKeyTap(KeyMap.LEFT_ARROW)

    /** Re-enters the slideshow at the current slide: Shift+F5 in PowerPoint, Ctrl+F5 in Google Slides. */
    fun resumeShow() {
        val google = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_PRESENT_APP, null) == APP_GOOGLE
        hid.sendKeyTap(KeyMap.F5, if (google) MOD_LEFT_CTRL else MOD_LEFT_SHIFT)
    }

    var volumeKeysEnabled: Boolean
        get() = clicker.enabled
        set(on) {
            clicker.enabled = on
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_VOLUME_KEYS, on).apply()
            publish()
        }

    // ---- Prompt queue (SPEC §4.3) ----

    fun queuePrompt(text: String) {
        promptQueue.addLast(text)
        publish()
    }

    fun clearQueue() {
        promptQueue.clear()
        publish()
    }

    /** Double-tap in LLM mode: types the next queued prompt + Enter, or just Enter if empty. */
    private fun sendNextPrompt() {
        val next = promptQueue.removeFirstOrNull()
        if (next != null) hid.typeTextString(next, pressEnterAtEnd = true)
        else hid.sendKeyTap(KeyMap.ENTER)
        publish()
    }

    // ---- Air mouse clutches (SPEC §4.2) ----

    fun setScreenClutch(on: Boolean) {
        screenClutch = on
        applyClutch()
    }

    /** Switches the tag to continuous touch so holding the cuff acts as the clutch. Gestures pause. */
    fun setSleeveAirMouse(on: Boolean) {
        sleeveAirMouse = on
        sleeveClutch = false
        link?.setTouchMode(if (on) TouchMode.CONTINUOUS else TouchMode.GESTURE)
        applyClutch()
        publish()
    }

    private fun onSleeveTouch(d: TouchData) {
        if (!sleeveAirMouse) return
        val total = d.lines().sum()
        // Hysteresis so a resting hand doesn't flicker the clutch on and off.
        val engaged = if (sleeveClutch) total > TOUCH_RELEASE else total > TOUCH_ENGAGE
        if (engaged != sleeveClutch) {
            sleeveClutch = engaged
            applyClutch()
        }
    }

    private fun applyClutch() = airMouse.setClutch(screenClutch || sleeveClutch)

    // ---- State / notification ----

    private fun publish() {
        if (quitting) return
        val s = state
        updateNotification(s)
        if (::watch.isInitialized) watch.sendState()
        onState?.invoke(s)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Dreamcoat bridge", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun updateNotification(s: State) {
        val laptop = when (s.hid.status) {
            HidBridgeManager.Status.CONNECTED -> "Laptop: ${s.hid.hostName}"
            HidBridgeManager.Status.UNAVAILABLE -> "Laptop: Bluetooth unavailable"
            else -> "Laptop: waiting"
        }
        val jacket = when (s.tag) {
            JacquardLink.Status.READY -> "Jacket: ready"
            JacquardLink.Status.CONNECTED_NO_JACKET -> "Tag: not on jacket"
            JacquardLink.Status.CONNECTING -> "Jacket: connecting"
            JacquardLink.Status.DISCONNECTED -> "Jacket: disconnected"
            JacquardLink.Status.NO_TAG -> "Jacket: not paired"
            null -> null
        }
        val text = listOfNotNull(s.mode.label, laptop, jacket).joinToString(" · ")
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, BridgeDashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dreamcoat)
            .setContentTitle("Dreamcoat")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_arrow_back, "Back", serviceIntent(ACTION_BACK, 1))
            .addAction(R.drawable.ic_arrow_forward, "Next", serviceIntent(ACTION_NEXT, 2))
            .addAction(R.drawable.ic_close, "Quit", serviceIntent(ACTION_QUIT, 3))
        // Media style puts Back/Next on the lock screen; it needs the clicker's session.
        if (::clicker.isInitialized && clicker.enabled) {
            builder.setStyle(
                MediaStyle().setMediaSession(clicker.session.sessionToken).setShowActionsInCompactView(0, 1)
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, BridgeService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val PREFS = "dreamcoat"
        const val PREF_KEY_DELAY = "key_delay_ms"
        const val PREF_SENSITIVITY = "air_sensitivity"
        const val PREF_VOLUME_KEYS = "volume_keys"
        const val PREF_KEEP_SCREEN_ON = "keep_screen_on"
        const val PREF_PRESS_ENTER = "press_enter"
        const val PREF_PRESENT_APP = "present_app"
        const val APP_POWERPOINT = "powerpoint"
        const val APP_GOOGLE = "google"
        private const val ACTION_NEXT = "com.dreamcoat.NEXT"
        private const val ACTION_BACK = "com.dreamcoat.BACK"
        private const val ACTION_QUIT = "com.dreamcoat.QUIT"
        private const val CHANNEL = "bridge"
        private const val NOTIFICATION_ID = 1
        // Sum of the 12 thread readings (0-255 each). Tune on real hardware.
        private const val TOUCH_ENGAGE = 150
        private const val TOUCH_RELEASE = 70
    }
}
