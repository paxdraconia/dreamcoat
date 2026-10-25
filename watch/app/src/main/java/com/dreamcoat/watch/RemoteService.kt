package com.dreamcoat.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

/**
 * Keeps an Ongoing Activity while the remote is open, so raising your wrist (or tapping the
 * watch-face chip) returns to Dreamcoat instead of the watch face.
 */
class RemoteService : Service(), MessageClient.OnMessageReceivedListener {

    private val messages by lazy { Wearable.getMessageClient(this) }

    override fun onCreate() {
        super.onCreate()
        // Lives as long as the remote does, so it hears the phone's Quit even when the screen is off.
        messages.addListener(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Remote", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Dreamcoat")
            .setContentText("Presenting")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher_monochrome)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate("Presenting").build())
            .build()
            .apply(this)
        startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onMessageReceived(e: MessageEvent) {
        if (e.path == PATH_QUIT) {
            onQuit?.invoke()
            stopSelf()
        }
    }

    override fun onDestroy() {
        messages.removeListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PATH_QUIT = "/dreamcoat/quit"
        /** Set by the open remote screen so a phone Quit closes it too. */
        var onQuit: (() -> Unit)? = null
        private const val CHANNEL = "remote"
        private const val NOTIFICATION_ID = 1
    }
}
