package com.dreamcoat.presenter

import android.content.Context
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.VolumeProviderCompat

/**
 * Turns the phone's volume keys (and the lock-screen media controls) into a slide clicker.
 *
 * An active, "playing" media session with *remote* volume receives volume-key presses even with the
 * screen off and the phone locked, instead of the phone changing its own volume. While enabled, the
 * volume keys therefore don't change the phone's volume.
 */
class VolumeClicker(context: Context, private val onNext: () -> Unit, private val onBack: () -> Unit) {

    val session = MediaSessionCompat(context, "DreamcoatClicker")
    private var lastPress = 0L

    private val volumeProvider = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 2, 1) {
        override fun onAdjustVolume(direction: Int) {
            Log.i(TAG, "volume key direction=$direction")
            when {
                direction < 0 -> debounced(onNext)  // volume down = next slide
                direction > 0 -> debounced(onBack)  // volume up = previous slide
            }
        }
    }

    init {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Dreamcoat clicker")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Vol − next · Vol + back")
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 0f)
                .build()
        )
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onSkipToNext() = onNext()
            override fun onSkipToPrevious() = onBack()
        })
        session.setPlaybackToRemote(volumeProvider)
    }

    var enabled: Boolean
        get() = session.isActive
        set(on) {
            session.isActive = on
        }

    /** Holding a volume key produces a burst of adjustments; only act on distinct presses. */
    private fun debounced(action: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPress < DEBOUNCE_MS) return
        lastPress = now
        action()
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    companion object {
        private const val TAG = "VolumeClicker"
        private const val DEBOUNCE_MS = 250L
    }
}
