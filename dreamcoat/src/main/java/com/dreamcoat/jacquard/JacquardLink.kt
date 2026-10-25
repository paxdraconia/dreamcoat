package com.dreamcoat.jacquard

import android.content.Context
import android.content.IntentSender
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.jacquard.sdk.JacquardManager
import com.google.android.jacquard.sdk.command.ContinuousTouchNotificationSubscription
import com.google.android.jacquard.sdk.command.GestureNotificationSubscription
import com.google.android.jacquard.sdk.command.HapticCommand
import com.google.android.jacquard.sdk.command.PlayLedPatternCommand
import com.google.android.jacquard.sdk.command.PlayLedPatternCommand.Color
import com.google.android.jacquard.sdk.command.PlayLedPatternCommand.Frame
import com.google.android.jacquard.sdk.command.PlayLedPatternCommand.LedPatternType
import com.google.android.jacquard.sdk.command.PlayLedPatternCommand.PlayType
import com.google.android.jacquard.sdk.connection.ConnectionState
import com.google.android.jacquard.sdk.model.Component
import com.google.android.jacquard.sdk.model.GearState
import com.google.android.jacquard.sdk.model.Gesture.GestureType
import com.google.android.jacquard.sdk.model.SdkConfig
import com.google.android.jacquard.sdk.model.TouchData
import com.google.android.jacquard.sdk.model.TouchMode
import com.google.android.jacquard.sdk.rx.Fn
import com.google.android.jacquard.sdk.rx.Signal
import com.google.android.jacquard.sdk.tag.AdvertisedJacquardTag
import com.google.android.jacquard.sdk.tag.ConnectedJacquardTag
import com.google.android.jacquard.sdk.util.StringUtils

/**
 * Owns the BLE link to the Jacquard tag: pairing, auto-reconnect, gesture / continuous-touch
 * streams, and LED / haptic feedback. Fully offline (cloud/DFU stubbed in the SDK).
 */
class JacquardLink(context: Context) {

    enum class Status { NO_TAG, CONNECTING, CONNECTED_NO_JACKET, READY, DISCONNECTED }

    var onStatus: ((Status) -> Unit)? = null
    var onGesture: ((GestureType) -> Unit)? = null
    var onTouch: ((TouchData) -> Unit)? = null

    var status = Status.NO_TAG
        private set

    private val prefs = context.getSharedPreferences("jacquard", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val manager: JacquardManager = JacquardManager.getInstance().also {
        // DfuManagerImpl reads this config on every connection, even though DFU is stubbed.
        it.init(SdkConfig.of("dreamcoat", "offline", null))
    }

    private var tag: ConnectedJacquardTag? = null
    private var gear: Component? = null
    private var touchMode = TouchMode.GESTURE
    private val subs = mutableListOf<Signal.Subscription>()
    private var scanSub: Signal.Subscription? = null

    val savedAddress: String? get() = prefs.getString(KEY_ADDRESS, null)

    /** Reconnects to the previously paired tag, if any. */
    fun connectSaved() {
        val address = savedAddress ?: return setStatus(Status.NO_TAG)
        follow(manager.connect(address))
    }

    fun startScanning(onFound: (AdvertisedJacquardTag) -> Unit, onError: (Throwable) -> Unit) {
        stopScanning()
        val seen = mutableSetOf<String>()
        scanSub = manager.startScanning()
            .tapError { main.post { onError(it) } }
            .onNext { t -> if (seen.add(t.address())) main.post { onFound(t) } }
    }

    fun stopScanning() {
        scanSub?.unsubscribe()
        scanSub = null
    }

    /**
     * First-time pairing. Needs an Activity, because Android shows a companion-device prompt
     * through [senderHandler].
     */
    fun pair(activity: Context, address: String, senderHandler: Fn<IntentSender, Signal<Boolean>>) {
        stopScanning()
        savedAddress?.takeIf { it != address }?.let { manager.forget(it) }
        prefs.edit().putString(KEY_ADDRESS, address).apply()
        follow(manager.connect(activity, address, senderHandler))
    }

    fun forget() {
        savedAddress?.let { manager.forget(it) }
        prefs.edit().remove(KEY_ADDRESS).apply()
        clearSubs()
        tag = null
        gear = null
        setStatus(Status.NO_TAG)
    }

    fun destroy() {
        stopScanning()
        clearSubs()
        manager.destroy()
    }

    private fun follow(states: Signal<ConnectionState>) {
        clearSubs()
        setStatus(Status.CONNECTING)
        subs += states
            .tapError { Log.w(TAG, "connection error", it); setStatus(Status.DISCONNECTED) }
            .onNext { state ->
                Log.i(TAG, "tag state ${state.type}")
                when (state.type) {
                    ConnectionState.Type.CONNECTED -> onConnected(state.connected())
                    ConnectionState.Type.DISCONNECTED -> {
                        tag = null
                        gear = null
                        setStatus(Status.DISCONNECTED)
                    }
                    else -> if (tag == null) setStatus(Status.CONNECTING)
                }
            }
    }

    private fun onConnected(t: ConnectedJacquardTag) {
        if (tag === t) return
        tag = t
        setStatus(Status.CONNECTED_NO_JACKET)
        subs += t.connectedGearSignal.onNext { g ->
            if (g.type == GearState.Type.ATTACHED) {
                gear = g.attached()
                applyTouchMode()
                setStatus(Status.READY)
            } else {
                gear = null
                setStatus(Status.CONNECTED_NO_JACKET)
            }
        }
        subs += t.subscribe(GestureNotificationSubscription()).onNext { g ->
            val type = g.gestureType()
            if (type != GestureType.NO_INFERENCE) main.post { onGesture?.invoke(type) }
        }
        subs += t.subscribe(ContinuousTouchNotificationSubscription()).onNext { d ->
            main.post { onTouch?.invoke(d) }
        }
    }

    /** GESTURE mode reports gestures; CONTINUOUS reports raw thread touch. The tag can't do both. */
    fun setTouchMode(mode: TouchMode) {
        touchMode = mode
        applyTouchMode()
    }

    private fun applyTouchMode() {
        val t = tag ?: return
        val g = gear ?: return
        t.setTouchMode(g, touchMode)
            .tapError { Log.w(TAG, "setTouchMode failed", it) }
            .consume()
    }

    // ---- Feedback ----

    /** Plays a colour on the tag's LED for [durationMs]. */
    fun led(r: Int, g: Int, b: Int, durationMs: Int, solid: Boolean = false) {
        val t = tag ?: return
        val cmd = PlayLedPatternCommand.newBuilder()
            .setFrames(listOf(Frame.of(Color.of(r, g, b), durationMs)))
            .setLedPatternType(if (solid) LedPatternType.PATTERN_TYPE_SOLID else LedPatternType.PATTERN_TYPE_SINGLE_BLINK)
            .setPlayType(PlayType.PLAY)
            .setResumable(false)
            .setHaltAll(false)
            .setIntensityLevel(100)
            .setDurationInMs(durationMs)
            .setComponent(t.tagComponent())
            .setStringUtils(StringUtils.getInstance())
            .build()
        t.enqueue(cmd).tapError { Log.w(TAG, "LED failed", it) }.consume()
    }

    /** Vibrates the jacket [pulses] times. Haptics live on the gear (jacket) side, not the tag. */
    fun haptic(pulses: Int) {
        val t = tag ?: return
        val g = gear ?: return
        val frame = HapticCommand.Frame.builder()
            .setOnMs(170)
            .setOffMs(if (pulses > 1) 120 else 0)
            .setPattern(HapticCommand.Pattern.HAPTIC_SYMBOL_SINE_INCREASE)
            .setMaxAmplitudePercent(60)
            .setRepeatNminusOne(pulses - 1)
            .build()
        t.enqueue(HapticCommand(frame, g)).tapError { Log.w(TAG, "haptic failed", it) }.consume()
    }

    private fun clearSubs() {
        subs.forEach { it.unsubscribe() }
        subs.clear()
    }

    private fun setStatus(s: Status) {
        if (status == s) return
        status = s
        main.post { onStatus?.invoke(s) }
    }

    companion object {
        private const val TAG = "JacquardLink"
        private const val KEY_ADDRESS = "tag_address"
    }
}
