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
