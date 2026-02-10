package org.koitharu.kotatsu.core.audio

import android.content.Context
import android.media.MediaPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AmbientSoundManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mediaPlayer: MediaPlayer? = null
    private var currentMode: AmbientMode = AmbientMode.NONE

    enum class AmbientMode {
        NONE, RAIN, CITY, QUIET_NIGHT, TENSION
    }

    fun playMode(mode: AmbientMode) {
        if (currentMode == mode) return
        stop()
        
        currentMode = mode
        if (mode == AmbientMode.NONE) return

        // NOTE: In a real app, these would be assets/raw files
        // For this prototype, we handle the logic.
        /*
        val resId = when(mode) {
            AmbientMode.RAIN -> R.raw.ambient_rain
            AmbientMode.CITY -> R.raw.ambient_city
            ...
        }
        mediaPlayer = MediaPlayer.create(context, resId)
        mediaPlayer?.isLooping = true
        mediaPlayer?.start()
        */
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentMode = AmbientMode.NONE
    }
}
