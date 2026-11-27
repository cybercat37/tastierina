package it.tastierina.app

/** Every sound of the drum machine, by engine id (engine-android DRUM_TRACKS, drum_sound). */
val DRUM_SOUNDS = listOf(
    "Cassa", "Rullante", "Clap", "Hat chiuso", "Hat aperto", "Tom basso", "Cowbell", "Tom alto",
    "Rimshot", "Shaker", "Ride", "Crash", "Conga", "Clave", "Tamburello", "Cassa 808",
)
const val DRUM_STEPS = 16
/** Rows shown at most at once: they must fit the screen with the keys of a phone. */
const val MAX_ROWS = 8
val DEFAULT_ROWS = listOf(0, 1, 2, 3, 4)

private const val CASSA = 0
private const val RULLANTE = 1
private const val CLAP = 2
private const val HAT = 3
private const val HAT_APERTO = 4
private const val RIMSHOT = 8
private const val SHAKER = 9
private const val CRASH = 11
private const val CONGA = 12
private const val CLAVE = 13
private const val CASSA_808 = 15

/** One step of a lesson: light these steps on this sound's row. */
data class LessonStage(val track: Int, val steps: Set<Int>, val tip: String)

data class Lesson(
    val id: String,
    val title: String,
    /** How the display names the finished groove: "questo è un giro …". */
    val style: String,
    val group: String,
    val bpm: Int,
    val stages: List<LessonStage>,
) {
    val sounds: List<Int> get() = stages.map { it.track }.distinct()
}

val LESSON_GROUPS = listOf("Elettronica", "Groove", "Latino")

private val EVERY = (0 until DRUM_STEPS).toSet()
private fun every(n: Int, from: Int = 0) = (from until DRUM_STEPS step n).toSet()
private val FOUR = every(4)
private val OFFBEATS = every(4, 2)
private val BACKBEAT = setOf(4, 12)

private fun stage(track: Int, steps: Set<Int>, tip: String) = LessonStage(track, steps, tip)

val LESSONS = listOf(
    // Elettronica
    Lesson(
        "house", "House", "house", "Elettronica", 124,
        listOf(
            stage(CASSA, FOUR, "un colpo ogni 4 caselle: 1, 5, 9, 13"),
            stage(CLAP, BACKBEAT, "sul 2° e sul 4° battito: 5 e 13"),
            stage(HAT_APERTO, OFFBEATS, "in levare, a metà tra una cassa e l'altra"),
            stage(SHAKER, EVERY, "shaker su tutte e 16: il movimento"),
        ),
    ),
    Lesson(
        "techno", "Techno", "techno", "Elettronica", 132,
        listOf(
            stage(CASSA, FOUR, "cassa dritta: 1, 5, 9, 13"),
            stage(HAT, EVERY, "tutte e 16: trascina lungo la riga"),
            stage(HAT_APERTO, OFFBEATS, "in levare: 3, 7, 11, 15"),
        ),
    ),
    Lesson(
        "dnb", "Drum and bass", "drum and bass", "Elettronica", 170,
        listOf(
            stage(CASSA, setOf(0, 10), "cassa spezzata: solo 1 e 11"),
            stage(RULLANTE, BACKBEAT, "rullante forte su 5 e 13"),
            stage(HAT, every(2), "una casella sì e una no"),
        ),
    ),
    Lesson(
        "garage", "UK garage", "UK garage", "Elettronica", 132,
        listOf(
            stage(CASSA, setOf(0, 10), "il 2-step: cassa su 1 e 11"),
            stage(CLAP, BACKBEAT, "clap su 5 e 13"),
            stage(HAT, OFFBEATS, "hat in levare: 3, 7, 11, 15"),
            stage(SHAKER, every(2, 1), "shaker sui sedicesimi pari: 2, 4, 6…"),
        ),
    ),
    Lesson(
        "dubstep", "Dubstep", "dubstep", "Elettronica", 140,
        listOf(
            stage(CASSA, setOf(0, 10), "cassa su 1 e 11"),
            stage(RULLANTE, setOf(8), "rullante solo su 9: il tempo dimezzato"),
            stage(HAT, every(2), "hat una casella sì e una no"),
        ),
    ),
    Lesson(
        "trap", "Trap", "trap", "Elettronica", 140,
        listOf(
            stage(CASSA_808, setOf(0, 3, 11), "cassa 808 lunga: 1, 4, 12"),
            stage(CLAP, setOf(8), "clap solo su 9"),
            stage(HAT, EVERY, "hat su tutte e 16"),
        ),
    ),
    Lesson(
        "afro", "Afro house", "afro house", "Elettronica", 122,
        listOf(
            stage(CASSA, FOUR, "cassa dritta: 1, 5, 9, 13"),
            stage(CONGA, setOf(3, 6, 11, 14), "conga: 4, 7, 12, 15"),
            stage(SHAKER, EVERY, "shaker su tutte e 16"),
            stage(CLAP, BACKBEAT, "clap su 5 e 13"),
        ),
    ),
    // Groove
    Lesson(
        "disco", "Disco", "disco", "Groove", 118,
        listOf(
            stage(CASSA, FOUR, "cassa dritta: 1, 5, 9, 13"),
            stage(RULLANTE, BACKBEAT, "rullante su 5 e 13"),
            stage(HAT_APERTO, OFFBEATS, "hat aperto in levare: 3, 7, 11, 15"),
        ),
    ),
    Lesson(
        "funk", "Funk", "funk", "Groove", 100,
        listOf(
            stage(CASSA, setOf(0, 3, 7, 10), "cassa sincopata: 1, 4, 8, 11"),
            stage(RULLANTE, BACKBEAT, "rullante su 5 e 13"),
            stage(HAT, every(2), "hat una casella sì e una no"),
        ),
    ),
    Lesson(
        "hiphop", "Hip hop", "hip hop", "Groove", 90,
        listOf(
            stage(CASSA, setOf(0, 7, 10), "cassa: 1, 8, 11"),
            stage(RULLANTE, BACKBEAT, "rullante su 5 e 13"),
            stage(HAT, every(2), "hat una casella sì e una no"),
        ),
    ),
    Lesson(
        "rock", "Rock", "rock", "Groove", 110,
        listOf(
            stage(CASSA, setOf(0, 8, 10), "cassa: 1, 9, 11"),
            stage(RULLANTE, BACKBEAT, "rullante su 5 e 13"),
            stage(HAT, every(2), "hat una casella sì e una no"),
            stage(CRASH, setOf(0), "un crash sull'1"),
        ),
    ),
    Lesson(
        "breakbeat", "Breakbeat", "breakbeat", "Groove", 130,
        listOf(
            stage(CASSA, setOf(0, 6, 10), "cassa spezzata: 1, 7, 11"),
            stage(RULLANTE, BACKBEAT, "rullante su 5 e 13"),
            stage(HAT, every(2), "hat una casella sì e una no"),
        ),
    ),
    // Latino
    Lesson(
        "reggaeton", "Reggaeton", "reggaeton", "Latino", 95,
        listOf(
            stage(CASSA, FOUR, "cassa dritta: 1, 5, 9, 13"),
            stage(RIMSHOT, setOf(3, 6, 11, 14), "il dembow: rimshot su 4, 7, 12, 15"),
            stage(HAT, every(2), "hat una casella sì e una no"),
        ),
    ),
    Lesson(
        "clave", "Clave e conga", "latino", "Latino", 100,
        listOf(
            stage(CLAVE, setOf(0, 3, 6, 10, 12), "la clave son 3-2: 1, 4, 7, 11, 13"),
            stage(CONGA, setOf(2, 6, 7, 10, 14, 15), "conga: 3, 7, 8, 11, 15, 16"),
            stage(CASSA, setOf(0, 8), "cassa su 1 e 9"),
        ),
    ),
)

/** Where a lesson stands against the pattern: the first row that is not right yet. */
sealed interface LessonState {
    data class Working(
        val index: Int,
        val total: Int,
        val stage: LessonStage,
        val missing: Set<Int>,
        val extra: Set<Int>,
    ) : LessonState

    data class Done(val lesson: Lesson) : LessonState
}

fun lessonState(lesson: Lesson, pattern: List<Int>): LessonState {
    lesson.stages.forEachIndexed { i, st ->
        val row = pattern.getOrElse(st.track) { 0 }
        val on = (0 until DRUM_STEPS).filter { row and (1 shl it) != 0 }.toSet()
        val missing = st.steps - on
        val extra = on - st.steps
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            return LessonState.Working(i, lesson.stages.size, st, missing, extra)
        }
    }
    return LessonState.Done(lesson)
}
