package it.tastierina.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Everything the screen shows, and the only place that talks to [Engine]. */
object AppState {
    val padNames = listOf("Cassa", "Rullante", "Clap", "Hat", "Tom", "Cowbell", "Basso", "Accordo")
    val padKinds = listOf("batteria", "batteria", "batteria", "batteria", "batteria", "percussione", "nota", "nota")

    /** In "Tieni il tempo" the player keeps the beat on this pad. */
    const val TARGET_PAD = 0
    const val BPM_MIN = 60
    const val BPM_MAX = 140

    /** Frame time of the current UI frame, System.nanoTime() clock. */
    var now by mutableLongStateOf(0L)
        private set

    /** When each pad was last hit, same clock as [now]. */
    val flashes = mutableStateListOf<Long>().apply { repeat(8) { add(Long.MIN_VALUE / 2) } }

    var lastPad by mutableIntStateOf(-1)
        private set
    var tempo by mutableStateOf(false)
        private set
    var bpm by mutableIntStateOf(90)
        private set
    var volume by mutableFloatStateOf(0.8f)
        private set
    var beatPosition by mutableStateOf(Double.NaN)
        private set
    var lastJudgment by mutableStateOf<Judgment?>(null)
        private set
    var streak by mutableIntStateOf(0)
        private set
    var best by mutableIntStateOf(0)
        private set
    var audioOk by mutableStateOf(true)
        private set

    private var prefs: SharedPreferences? = null
    private var frames = 0

    fun load(context: Context) {
        val p = context.getSharedPreferences("tastierina", Context.MODE_PRIVATE)
        prefs = p
        volume = p.getFloat("volume", 0.8f)
        bpm = p.getInt("bpm", 90).coerceIn(BPM_MIN, BPM_MAX)
        best = p.getInt("best", 0)
    }

    private fun save() {
        prefs?.edit()?.putFloat("volume", volume)?.putInt("bpm", bpm)?.putInt("best", best)?.apply()
    }

    /** Opens audio and restores what the engine should be doing. */
    fun startAudio() {
        audioOk = Engine.nativeStart()
        Engine.nativeSetVolume(volume)
        if (tempo) Engine.nativeSetMetronome(true, bpm.toFloat())
    }

    fun stopAudio() = Engine.nativeStop()

    /** Called once per UI frame. */
    fun tick(frameNanos: Long) {
        now = frameNanos
        beatPosition = if (tempo) Engine.nativeBeatPosition(System.nanoTime()) else Double.NaN
        frames++
        if (frames % 30 == 0 && Engine.nativeNeedsRestart()) {
            stopAudio()
            startAudio()
        }
    }

    fun hit(pad: Int, touchNanos: Long) {
        flashes[pad] = System.nanoTime()
        lastPad = pad
        val judgment = Judgment.unpack(Engine.nativeHit(pad, 1f, touchNanos))
        if (tempo && pad == TARGET_PAD && judgment != null) {
            lastJudgment = judgment
            streak = if (judgment.verdict == Verdict.ON_TIME) streak + 1 else 0
            if (streak > best) {
                best = streak
                save()
            }
        }
    }

    fun setTempoMode(on: Boolean) {
        tempo = on
        streak = 0
        lastJudgment = null
        Engine.nativeSetMetronome(on, bpm.toFloat())
    }

    fun changeBpm(delta: Int) {
        bpm = (bpm + delta).coerceIn(BPM_MIN, BPM_MAX)
        if (tempo) Engine.nativeSetMetronome(true, bpm.toFloat())
        save()
    }

    fun changeVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        Engine.nativeSetVolume(volume)
        save()
    }
}
