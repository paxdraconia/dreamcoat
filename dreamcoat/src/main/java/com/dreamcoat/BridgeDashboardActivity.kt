package com.dreamcoat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.ListView
import androidx.core.view.GravityCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognizerIntent
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dreamcoat.databinding.ActivityDashboardBinding
import com.dreamcoat.hid.HidBridgeManager.Companion.BUTTON_LEFT
import com.dreamcoat.hid.HidBridgeManager.Companion.BUTTON_RIGHT
import com.dreamcoat.hid.HidBridgeManager.Status
import com.dreamcoat.hid.KeyMap
import com.dreamcoat.jacquard.Action
import com.dreamcoat.jacquard.JacquardLink
import com.dreamcoat.jacquard.MAPPABLE_GESTURES
import com.dreamcoat.jacquard.Mode
import com.google.android.jacquard.sdk.model.Gesture.GestureType
import com.google.android.jacquard.sdk.rx.Fn
import com.google.android.jacquard.sdk.rx.Signal
import com.google.android.jacquard.sdk.tag.AdvertisedJacquardTag

/** The one screen of the app (SPEC §3.3 "BridgeDashboardActivity"). */
class BridgeDashboardActivity : AppCompatActivity() {

    private lateinit var b: ActivityDashboardBinding
    private var service: BridgeService? = null
    /** Set while the UI is being updated from state, so widget listeners don't echo it back. */
    private var rendering = false
    private var renderedMappingMode: Mode? = null

    private val requiredPermissions = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val connectGranted = Build.VERSION.SDK_INT < 31 ||
                result[Manifest.permission.BLUETOOTH_CONNECT] == true
            if (connectGranted) ensureBluetoothOn()
            else b.status.text = getString(R.string.status_need_permission)
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startBridge() }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r: ActivityResult ->
            val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                val current = b.prompt.text?.toString().orEmpty()
                b.prompt.setText(if (current.isBlank()) text else "$current $text")
                b.prompt.setSelection(b.prompt.text?.length ?: 0)
            }
        }

    /** Companion-device pairing prompt for the tag; result is fed back to the SDK. */
    private var pairResult: Signal<Boolean>? = null
    private val pairLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            pairResult?.next(r.resultCode == RESULT_OK)
            pairResult = null
        }
    private val pairHandler = Fn<IntentSender, Signal<Boolean>> { sender ->
        Signal.create<Boolean>().also {
            pairResult = it
            pairLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    private val prefs by lazy { getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as BridgeService.LocalBinder).service
            service = s
            s.onState = ::render
            s.onQuit = { finishAndRemoveTask() }
            b.sensitivity.value = s.airMouse.sensitivity.coerceIn(200f, 4000f).let { (it / 100).toInt() * 100f }
            render(s.state)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.version.text = getString(R.string.version, packageManager.getPackageInfo(packageName, 0).versionName)
        wireControls()
        applyKeepScreenOn(prefs.getBoolean(BridgeService.PREF_KEEP_SCREEN_ON, false))

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) ensureBluetoothOn() else permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onDestroy() {
        service?.let {
            it.onState = null
            it.onQuit = null
            it.setScreenClutch(false)
            it.link?.stopScanning()
            unbindService(connection)
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun ensureBluetoothOn() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter != null && !adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            startBridge()
        }
    }

    private fun startBridge() {
        val intent = Intent(this, BridgeService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /** While the app is in front, volume keys go here first; the service's media session handles them when locked. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = service
        if (s != null && s.volumeKeysEnabled &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            if (event.repeatCount == 0) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) s.nextSlide() else s.previousSlide()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun render(s: BridgeService.State) {
        rendering = true
        val connected = s.hid.status == Status.CONNECTED
        b.statusChip.text = if (connected) getString(R.string.chip_connected, s.hid.hostName)
            else getString(R.string.chip_not_connected)
        b.statusChip.chipBackgroundColor = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (connected) R.color.electric_teal else R.color.marigold)
        )
        b.statusChip.setTextColor(ContextCompat.getColor(this, R.color.deep_space_blue))
        b.statusChip.chipIconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.deep_space_blue))
        b.volumeKeys.isChecked = service?.volumeKeysEnabled ?: true
        // Queued prompts are sent by a double tap on the cuff or a tap on the watch's status line.
        val canSendQueued = s.tag == JacquardLink.Status.READY || s.watchName != null
        b.queuePrompt.visibility = if (canSendQueued) View.VISIBLE else View.GONE
        b.status.text = when (s.hid.status) {
            Status.CONNECTED -> getString(R.string.status_connected, s.hid.hostName)
            Status.CONNECTING -> getString(R.string.status_connecting, s.hid.hostName)
            Status.REGISTERED -> getString(R.string.status_ready)
            Status.REGISTERING -> getString(R.string.status_starting)
            Status.UNAVAILABLE -> getString(R.string.status_unavailable)
        }
        if (s.tag != null) {
            b.tagStatus.text = getString(
                when (s.tag) {
                    JacquardLink.Status.NO_TAG -> R.string.tag_none
                    JacquardLink.Status.CONNECTING -> R.string.tag_connecting
                    JacquardLink.Status.CONNECTED_NO_JACKET -> R.string.tag_no_jacket
                    JacquardLink.Status.READY -> R.string.tag_ready
                    JacquardLink.Status.DISCONNECTED -> R.string.tag_disconnected
                }
            )
            b.forgetJacket.isEnabled = s.tag != JacquardLink.Status.NO_TAG
            b.sleeveAir.isChecked = s.sleeveAirMouse
        }
        b.modeGroup.check(if (s.mode == Mode.PRESENTATION) R.id.modePresentation else R.id.modeLlm)
        b.watchStatus.text = s.watchName?.let { getString(R.string.watch_connected, it) }
            ?: getString(R.string.watch_none)
        b.queueStatus.visibility = if (s.queue.isEmpty()) View.GONE else View.VISIBLE
        b.queueStatus.text = s.queue.firstOrNull()
            ?.let { getString(R.string.queue_status, s.queue.size, it.take(40)) }
            ?: getString(R.string.queue_empty)
        if (renderedMappingMode != s.mode) renderMappings(s.mode)
        rendering = false
    }

    /** One label + dropdown per gesture, for the active mode. */
    private fun renderMappings(mode: Mode) {
        val router = service?.router ?: return
        renderedMappingMode = mode
        b.mappingTitle.text = getString(R.string.section_mapping, mode.label)
        b.mappings.removeAllViews()
        val actions = Action.entries
        for (gesture in MAPPABLE_GESTURES) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val label = if (BuildConfig.JACKET) gesture.description else WATCH_LABELS[gesture]
            row.addView(TextView(this).apply { text = label }, LinearLayout.LayoutParams(0, -2, 0.8f))
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@BridgeDashboardActivity, android.R.layout.simple_spinner_dropdown_item,
                    actions.map { it.label },
                )
                setSelection(actions.indexOf(router.actionFor(mode, gesture)), false)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        router.setAction(mode, gesture, actions[pos])
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }
            row.addView(spinner, LinearLayout.LayoutParams(0, -2, 1.4f))
            b.mappings.addView(row)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireControls() {
        b.discoverable.setOnClickListener {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            )
        }
        b.reconnect.setOnClickListener { service?.hid?.reconnect() }
        b.quit.setOnClickListener { service?.quit() ?: finishAndRemoveTask() }

        if (BuildConfig.JACKET) {
            b.findJacket.setOnClickListener { showScanDialog() }
            b.forgetJacket.setOnClickListener { service?.link?.forget() }
            b.sleeveAir.setOnCheckedChangeListener { _, on ->
                if (!rendering) service?.setSleeveAirMouse(on)
            }
        } else {
            b.jacketSection.visibility = View.GONE
            b.mappingNote.setText(R.string.mapping_note_watch)
        }

        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (rendering || !checked) return@addOnButtonCheckedListener
            service?.router?.setMode(if (id == R.id.modePresentation) Mode.PRESENTATION else Mode.LLM_DEMO)
        }

        b.mic.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            try {
                speechLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_recognizer, Toast.LENGTH_LONG).show()
            }
        }
        b.send.setOnClickListener {
            val text = b.prompt.text.toString()
            if (text.isNotBlank()) {
                service?.hid?.typeTextString(text, pressEnterAtEnd = b.pressEnter.isChecked)
                b.prompt.text?.clear()
            }
        }
        b.queuePrompt.setOnClickListener {
            val text = b.prompt.text.toString()
            if (text.isNotBlank()) {
                service?.queuePrompt(text)
                b.prompt.text?.clear()
            }
        }
        b.queueStatus.setOnLongClickListener { service?.clearQueue(); true }

        b.toolbar.setNavigationOnClickListener { b.drawerLayout.openDrawer(GravityCompat.START) }
        b.statusChip.setOnClickListener { service?.hid?.reconnect() }
        b.next.setOnClickListener { service?.nextSlide() }
        b.back.setOnClickListener { service?.previousSlide() }
        b.black.setOnClickListener { service?.hid?.sendKeyTap(KeyMap.B) }
        b.esc.setOnClickListener { service?.hid?.sendKeyTap(KeyMap.ESCAPE) }
        b.present.setOnClickListener { service?.resumeShow() }
        b.presentAppGroup.check(
            if (prefs.getString(BridgeService.PREF_PRESENT_APP, null) == BridgeService.APP_GOOGLE) R.id.presentGoogle
            else R.id.presentPowerPoint
        )
        b.presentAppGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val app = if (id == R.id.presentGoogle) BridgeService.APP_GOOGLE else BridgeService.APP_POWERPOINT
            prefs.edit().putString(BridgeService.PREF_PRESENT_APP, app).apply()
        }

        b.aim.setOnTouchListener { v, e -> holdToggle(v, e) { on -> service?.setScreenClutch(on) } }
        b.clickLeft.setOnTouchListener { v, e -> holdToggle(v, e) { on -> service?.hid?.setMouseButton(BUTTON_LEFT, on) } }
        b.clickRight.setOnTouchListener { v, e -> holdToggle(v, e) { on -> service?.hid?.setMouseButton(BUTTON_RIGHT, on) } }

        b.volumeKeys.setOnCheckedChangeListener { _, on -> if (!rendering) service?.volumeKeysEnabled = on }
        b.keepScreenOn.isChecked = prefs.getBoolean(BridgeService.PREF_KEEP_SCREEN_ON, false)
        b.keepScreenOn.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BridgeService.PREF_KEEP_SCREEN_ON, on).apply()
            applyKeepScreenOn(on)
        }
        b.pressEnter.isChecked = prefs.getBoolean(BridgeService.PREF_PRESS_ENTER, true)
        b.pressEnter.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BridgeService.PREF_PRESS_ENTER, on).apply()
        }

        b.sensitivity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            service?.airMouse?.sensitivity = value
            prefs.edit().putFloat(BridgeService.PREF_SENSITIVITY, value).apply()
        }
    }

    private fun showScanDialog() {
        val link = service?.link ?: return
        val found = mutableListOf<AdvertisedJacquardTag>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply { setText(R.string.scanning_hint) })
        }
        lateinit var dialog: AlertDialog
        val list = ListView(this).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, which, _ ->
                link.pair(this@BridgeDashboardActivity, found[which].address(), pairHandler)
                dialog.dismiss()
            }
        }
        content.addView(list, LinearLayout.LayoutParams(-1, -2))
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scanning_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { link.stopScanning() }
            .create()
        dialog.show()
        link.startScanning(
            onFound = { tag ->
                found += tag
                adapter.add("${tag.displayName()}  (${tag.address()})")
            },
            onError = { e ->
                Toast.makeText(this, "Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            },
        )
    }

    /** Treats a view as a press-and-hold control: down = on, up/cancel = off. */
    private fun holdToggle(v: View, e: MotionEvent, set: (Boolean) -> Unit): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { v.isPressed = true; set(true) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; set(false) }
        }
        return true
    }

    companion object {
        /** Row labels for the gesture dropdowns when there's no jacket: what each slot is on the watch. */
        private val WATCH_LABELS = mapOf(
            GestureType.BRUSH_OUT to "Double pinch",
            GestureType.BRUSH_IN to "Wrist turn",
            GestureType.DOUBLE_TAP to "Tap top line",
            GestureType.SHORT_COVER to "Hold top line",
        )
    }
}
