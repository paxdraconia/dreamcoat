package com.dreamcoat.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureAction
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureClickIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureClickIndicatorState
import androidx.wear.compose.material3.onehandedgesture.OneHandedGesturePriority
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Pixel Watch remote. Double pinch = Next, wrist turn = Back (Wear OS 7 one-handed gestures),
 * plus buttons, horizontal swipes and the crown. Everything is sent to the phone app, which maps
 * it through the same gesture settings as the jacket and types the key on the laptop.
 */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private var laptop by mutableStateOf("")
    private var mode by mutableStateOf("PRESENTATION")
    private var phoneReachable by mutableStateOf(false)

    private val messages by lazy { Wearable.getMessageClient(this) }
    private val nodes by lazy { Wearable.getNodeClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A talk is short; keeping the screen on means a pinch always lands.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, RemoteService::class.java))
        RemoteService.onQuit = { finishAndRemoveTask() }
        setContent { MaterialTheme { Remote() } }
    }

    override fun onResume() {
        super.onResume()
        messages.addListener(this)
        send(PATH_HELLO, "")
    }

    override fun onPause() {
        messages.removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        RemoteService.onQuit = null
        if (isFinishing) stopService(Intent(this, RemoteService::class.java))
        super.onDestroy()
    }

    override fun onMessageReceived(e: MessageEvent) {
        if (e.path != PATH_STATE) return
        val parts = String(e.data).split('|')
        laptop = parts.getOrElse(0) { "" }
        mode = parts.getOrElse(1) { mode }
        phoneReachable = true
    }

    private fun gesture(name: String) {
        buzz(if (name == "MODE") 2 else 1)
        send(PATH_GESTURE, name)
    }

    private fun send(path: String, payload: String) {
        // phoneReachable only flips on a state reply: the watch stays Bluetooth-connected to the
        // phone even when the Dreamcoat phone app isn't running.
        nodes.connectedNodes.addOnSuccessListener { list ->
            list.forEach { messages.sendMessage(it.id, path, payload.toByteArray()) }
        }
    }

    private fun buzz(pulses: Int) {
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(
            if (pulses == 1) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            else VibrationEffect.createWaveform(longArrayOf(0, 40, 90, 40), -1)
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Remote() {
        val modeColor = if (mode == "LLM_DEMO") Color(0xFFA000FF) else Color(0xFF00C4CC)
        val focus = remember { FocusRequester() }
        var rotary = 0f
        var dragX = 0f

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF1A2A45))
                .onRotaryScrollEvent {
                    rotary += it.verticalScrollPixels
                    if (abs(rotary) > ROTARY_STEP) {
                        gesture(if (rotary > 0) "NEXT" else "BACK")
                        rotary = 0f
                    }
                    true
                }
                .focusRequester(focus)
                .focusable()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onDragEnd = {
                            if (abs(dragX) > SWIPE_PX) gesture(if (dragX < 0) "NEXT" else "BACK")
                        },
                    ) { _, dx -> dragX += dx }
                },
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                // Tap = the jacket's double tap (send queued prompt); long-press = swap mode.
                Text(
                    text = (if (mode == "LLM_DEMO") "LLM demo" else "Slides") +
                        when {
                            laptop.isNotEmpty() -> " · $laptop"
                            phoneReachable -> " · no laptop"
                            else -> " · open phone app"
                        },
                    color = modeColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { gesture("SELECT") },
                            onLongClick = { gesture("MODE") },
                        )
                        .padding(vertical = 6.dp),
                )
                GestureButton(
                    label = "Next",
                    action = OneHandedGestureAction.Primary,
                    gestureId = "dreamcoat_next",
                    container = Color(0xFFFFC759),
                    content = Color(0xFF1A2A45),
                    height = 96,
                ) { gesture("NEXT") }
                GestureButton(
                    label = "Back",
                    action = OneHandedGestureAction.Dismiss,
                    gestureId = "dreamcoat_back",
                    container = Color(0xFF2A3D5E),
                    content = Color(0xFFF4F6F8),
                    height = 52,
                ) { gesture("BACK") }
                // Leaving with the side button keeps the remote running (raise wrist to return);
                // Exit really closes it.
                Text(
                    "Exit",
                    color = Color(0xFFB0B8C4),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { finishAndRemoveTask() }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    /** A button that also fires on a one-handed gesture (double pinch / wrist turn). */
    @Composable
    private fun GestureButton(
        label: String,
        action: OneHandedGestureAction,
        gestureId: String,
        container: Color,
        content: Color,
        height: Int,
        onClick: () -> Unit,
    ) {
        val config = rememberOneHandedGestureConfiguration(
            action = action,
            gestureId = gestureId,
            priority = OneHandedGesturePriority.Clickable,
        )
        val indicator = remember { OneHandedGestureClickIndicatorState() }
        val interaction = remember { MutableInteractionSource() }
        val scope = rememberCoroutineScope()
        Button(
            onClick = onClick,
            interactionSource = interaction,
            colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
            modifier = Modifier
                // The lower button sits near the round edge; narrow it so its corners aren't clipped.
                .fillMaxWidth(if (height > 80) 1f else 0.78f)
                .height(height.dp)
                .oneHandedGesture(
                    gestureConfiguration = config,
                    onGestureLabel = label,
                    interactionSource = interaction,
                    onGestureAvailable = { scope.launch { indicator.showIndicator() } },
                    onGesture = { onClick() },
                ),
        ) {
            OneHandedGestureClickIndicator(gestureConfiguration = config, state = indicator) {
                Text(
                    label,
                    fontSize = if (height > 80) 30.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    companion object {
        const val PATH_HELLO = "/dreamcoat/hello"
        const val PATH_GESTURE = "/dreamcoat/gesture"
        const val PATH_STATE = "/dreamcoat/state"
        private const val ROTARY_STEP = 90f
        private const val SWIPE_PX = 60f
    }
}
