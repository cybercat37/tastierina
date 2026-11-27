package it.tastierina.app

const val GIRO_COUNT = 3
val GIRO_NAMES = listOf("A", "B", "C")
/** A giro is 1, 2 or 4 bars of sixteen steps. */
const val GIRO_STEPS = 64
val BAR_CHOICES = listOf(1, 2, 4)
val CHAIN_REPS = listOf(1, 2, 4, 8)
const val CHAIN_MAX = 8

/** A step of the scaletta: play [giro] [reps] times. */
data class ChainStep(val giro: Int, val reps: Int)

/**
 * A giro as stored: every step of up to four bars. The drum rows are bitmasks
 * (bit s is step s); the note rows hold a key of the keyboard per step, -1 silent.
 * Mirrors the engine's `Giro`.
 */
class GiroData {
    var bars = 1
    val drums = LongArray(DRUM_SOUNDS.size)
    val bass = IntArray(GIRO_STEPS) { -1 }
    val lead = IntArray(GIRO_STEPS) { -1 }

    /** Engine rows: 0 bass, 1 melody. */
    fun notes(row: Int): IntArray = if (row == 0) bass else lead

    fun copyFrom(other: GiroData) {
        bars = other.bars
        other.drums.copyInto(drums)
        other.bass.copyInto(bass)
        other.lead.copyInto(lead)
    }

    fun copy(): GiroData = GiroData().also { it.copyFrom(this) }

    fun clear(notes: Boolean) {
        drums.fill(0L)
        if (notes) {
            bass.fill(-1)
            lead.fill(-1)
        }
    }

    fun isEmpty(notes: Boolean): Boolean =
        drums.all { it == 0L } && (!notes || (bass.all { it < 0 } && lead.all { it < 0 }))

    /** The sixteen steps of bar [page] of a drum row, as a 16-bit mask. */
    fun pageMask(track: Int, page: Int): Int = ((drums[track] ushr (page * 16)) and 0xFFFFL).toInt()

    private fun barEmpty(bar: Int): Boolean {
        val mask = 0xFFFFL shl (bar * 16)
        return drums.all { it and mask == 0L } && (bar * 16 until bar * 16 + 16).all { bass[it] < 0 && lead[it] < 0 }
    }

    /** Makes the giro [n] bars long. Bars never used start as a copy of the ones before, so the rhythm goes on. */
    fun resize(n: Int) {
        val old = bars
        for (bar in old until n) {
            if (!barEmpty(bar)) continue
            val from = (bar % old) * 16
            val at = bar * 16
            for (t in drums.indices) {
                val bits = (drums[t] ushr from) and 0xFFFFL
                drums[t] = drums[t] or (bits shl at)
            }
            for (i in 0 until 16) {
                bass[at + i] = bass[from + i]
                lead[at + i] = lead[from + i]
            }
        }
        bars = n
    }

    fun encode(): String =
        listOf(bars.toString(), drums.joinToString(","), bass.joinToString(","), lead.joinToString(",")).joinToString(";")

    companion object {
        fun decode(text: String): GiroData? {
            val parts = text.split(';')
            if (parts.size != 4) return null
            val g = GiroData()
            g.bars = parts[0].toIntOrNull()?.takeIf { it in BAR_CHOICES } ?: 1
            parts[1].split(',').forEachIndexed { i, v -> if (i < g.drums.size) g.drums[i] = v.toLongOrNull() ?: 0L }
            for ((part, row) in listOf(parts[2] to g.bass, parts[3] to g.lead)) {
                part.split(',').forEachIndexed { i, v ->
                    if (i < GIRO_STEPS) row[i] = (v.toIntOrNull() ?: -1).coerceIn(-1, KEY_COUNT - 1)
                }
            }
            return g
        }
    }
}
