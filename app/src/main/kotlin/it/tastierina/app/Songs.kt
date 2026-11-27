package it.tastierina.app

/** A note of a guided song: a key of the 18 (0 = the first Do), held [beats]. */
data class SongNote(val key: Int, val beats: Double)

data class Song(val id: String, val title: String, val bpm: Int, val notes: List<SongNote>)

/** Note names of the 18 keys, Do to Fa. */
val KEY_NAMES = listOf("Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")

fun keyName(key: Int): String = KEY_NAMES[key % 12]

const val KEY_COUNT = 18
private val BLACK_PITCHES = setOf(1, 3, 6, 8, 10)
fun isBlack(key: Int) = key % 12 in BLACK_PITCHES

private fun n(vararg pairs: Pair<Int, Double>) = pairs.map { SongNote(it.first, it.second) }
private fun twice(l: List<SongNote>) = l + l

/** The same songs as the web version (web/index.html, SONGS). */
val SONGS: List<Song> = listOf(
    // In Sol, so the low "din don dan" fits on the keys.
    Song(
        "martino", "Fra Martino", 120,
        twice(n(7 to 1.0, 9 to 1.0, 11 to 1.0, 7 to 1.0)) +
            twice(n(11 to 1.0, 12 to 1.0, 14 to 2.0)) +
            twice(n(14 to 0.5, 16 to 0.5, 14 to 0.5, 12 to 0.5, 11 to 1.0, 7 to 1.0)) +
            twice(n(7 to 1.0, 2 to 1.0, 7 to 2.0)),
    ),
    Song(
        "gioia", "Inno alla gioia", 112,
        n(4 to 1.0, 4 to 1.0, 5 to 1.0, 7 to 1.0, 7 to 1.0, 5 to 1.0, 4 to 1.0, 2 to 1.0, 0 to 1.0, 0 to 1.0, 2 to 1.0, 4 to 1.0) +
            n(4 to 1.5, 2 to 0.5, 2 to 2.0) +
            n(4 to 1.0, 4 to 1.0, 5 to 1.0, 7 to 1.0, 7 to 1.0, 5 to 1.0, 4 to 1.0, 2 to 1.0, 0 to 1.0, 0 to 1.0, 2 to 1.0, 4 to 1.0) +
            n(2 to 1.5, 0 to 0.5, 0 to 2.0),
    ),
    Song(
        "stellina", "Brilla brilla", 104,
        run {
            val a = n(0 to 1.0, 0 to 1.0, 7 to 1.0, 7 to 1.0, 9 to 1.0, 9 to 1.0, 7 to 2.0, 5 to 1.0, 5 to 1.0, 4 to 1.0, 4 to 1.0, 2 to 1.0, 2 to 1.0, 0 to 2.0)
            val b = n(7 to 1.0, 7 to 1.0, 5 to 1.0, 5 to 1.0, 4 to 1.0, 4 to 1.0, 2 to 2.0)
            a + b + b + a
        },
    ),
)
