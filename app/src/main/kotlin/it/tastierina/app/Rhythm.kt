package it.tastierina.app

/** Rows of the drum machine, same order as engine DRUM_TRACKS. */
val DRUM_NAMES = listOf("Cassa", "Rullante", "Clap", "Hat chiuso", "Hat aperto")
const val DRUM_STEPS = 16

/** One step of a lesson: light these steps on this row. */
data class LessonStage(val track: Int, val steps: Set<Int>, val tip: String)

data class Lesson(val id: String, val title: String, val style: String, val bpm: Int, val stages: List<LessonStage>)

private val EVERY = (0 until DRUM_STEPS).toSet()
private fun every(n: Int) = (0 until DRUM_STEPS step n).toSet()

/** The drum lessons of the web version (web/index.html, LESSONS), without the bass stage. */
val LESSONS = listOf(
    Lesson(
        "house", "House", "house", 124,
        listOf(
            LessonStage(0, setOf(0, 4, 8, 12), "un colpo ogni 4 caselle: 1, 5, 9, 13"),
            LessonStage(2, setOf(4, 12), "sul 2° e sul 4° battito: 5 e 13"),
            LessonStage(4, setOf(2, 6, 10, 14), "in levare, a metà tra una cassa e l'altra"),
        ),
    ),
    Lesson(
        "techno", "Techno", "techno", 132,
        listOf(
            LessonStage(0, setOf(0, 4, 8, 12), "cassa dritta: 1, 5, 9, 13"),
            LessonStage(3, EVERY, "tutte e 16: trascina lungo la riga"),
            LessonStage(4, setOf(2, 6, 10, 14), "in levare: 3, 7, 11, 15"),
        ),
    ),
    Lesson(
        "dnb", "Drum and bass", "drum and bass", 170,
        listOf(
            LessonStage(0, setOf(0, 10), "cassa spezzata: solo 1 e 11"),
            LessonStage(1, setOf(4, 12), "rullante forte su 5 e 13"),
            LessonStage(3, every(2), "una casella sì e una no"),
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
