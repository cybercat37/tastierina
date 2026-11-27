package it.tastierina.app

/**
 * The Rust audio engine (engine-android, libtastierina.so).
 *
 * Call from the main thread. Times are monotonic nanoseconds: System.nanoTime(),
 * or a touch's uptimeMillis × 1 000 000, the same clock AAudio stamps frames with.
 */
object Engine {
    init {
        System.loadLibrary("tastierina")
    }

    /** No judgment: the metronome is off. */
    const val NO_JUDGMENT = Long.MIN_VALUE

    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativeNeedsRestart(): Boolean

    /** Plays [pad]; with the metronome on, returns the packed judgment of the touch. */
    external fun nativeHit(pad: Int, velocity: Float, touchNanos: Long): Long
    external fun nativeSetMetronome(on: Boolean, bpm: Float)
    external fun nativeSetVolume(volume: Float)

    /** Beats since beat 0 as heard at [nowNanos]; NaN when the metronome is off. */
    external fun nativeBeatPosition(nowNanos: Long): Double
    external fun nativeLatencyMs(nowNanos: Long): Float

    /**
     * "Ripeti": schedules a phrase in the bar starting at beat [listenStart] of the
     * running metronome; the player answers in the next bar. Returns the packed phrase.
     */
    external fun nativeRepeatRound(round: Int, listenStart: Long, keep: Boolean, seed: Long): Long

    /** Judges the player's bar starting at beat [barStart]. */
    external fun nativeRepeatResult(barStart: Long): Long
    external fun nativeRepeatStop()

    // Keyboard (level 1). Timbre: 0 piano, 1 organo, 2 synth, 3 campana.
    external fun nativeNoteOn(midi: Int, timbre: Int, velocity: Float)
    external fun nativeNoteOff(midi: Int)
    external fun nativeAllNotesOff()
    /** A note at [beat] of the running metronome, held [length] beats. */
    external fun nativeScheduleNote(midi: Int, timbre: Int, beat: Double, length: Double)
    /** Drops everything scheduled and lets go of every note. */
    external fun nativeClearScheduled()
    /** Whether the metronome ticks are heard; the beat grid runs either way. */
    external fun nativeSetClick(on: Boolean)

    /** 1 exclusive path, 0 shared, -1 closed. */
    external fun nativeIsExclusive(): Int
    external fun nativeSampleRate(): Int
}

enum class Verdict { ON_TIME, EARLY, LATE, OFF }

data class Judgment(val verdict: Verdict, val offsetMs: Double) {
    companion object {
        /** Mirrors engine::timing::Judgment::pack. */
        fun unpack(packed: Long): Judgment? {
            if (packed == Engine.NO_JUDGMENT) return null
            val v = ((packed shr 32) and 0xFF).toInt()
            val micros = packed.toInt() // low 32 bits, signed
            return Judgment(Verdict.entries.getOrElse(v) { Verdict.OFF }, micros / 1000.0)
        }
    }
}

/** One note of a "Ripeti" phrase: a pad on an eighth of the bar (0..7). */
data class PhraseNote(val pad: Int, val step: Int) {
    val beat: Double get() = step / 2.0
}

/** Mirrors engine::repeat::Phrase::pack. */
fun unpackPhrase(v: Long): List<PhraseNote> {
    val len = ((v shr 48) and 0xF).toInt().coerceAtMost(8)
    return List(len) { i ->
        val bits = ((v shr (6 * i)) and 0x3F).toInt()
        PhraseNote(pad = bits and 7, step = (bits shr 3) and 7)
    }
}

enum class NoteResult { ON_TIME, EARLY, LATE, MISSED }

data class Evaluation(val results: List<NoteResult>, val extra: Int, val passed: Boolean, val perfect: Boolean) {
    companion object {
        /** Mirrors engine::repeat::Evaluation::pack. */
        fun unpack(v: Long): Evaluation {
            val len = ((v shr 16) and 0xF).toInt().coerceAtMost(8)
            val results = List(len) { i -> NoteResult.entries[((v shr (2 * i)) and 3).toInt()] }
            return Evaluation(
                results = results,
                extra = ((v shr 20) and 0xFF).toInt(),
                passed = ((v shr 28) and 1L) == 1L,
                perfect = ((v shr 29) and 1L) == 1L,
            )
        }
    }
}
