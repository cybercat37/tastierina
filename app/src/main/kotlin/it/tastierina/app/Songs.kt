package it.tastierina.app

/** A note of a guided song: a key of the 18 (0 = the first Do), held [beats]. */
data class SongNote(val key: Int, val beats: Double)

/** [level]: 1 facile, 2 media, 3 impegnativa. */
data class Song(val id: String, val title: String, val bpm: Int, val level: Int, val notes: List<SongNote>)

/** Note names of the 18 keys, Do to Fa. */
val KEY_NAMES = listOf("Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")

fun keyName(key: Int): String = KEY_NAMES[key % 12]

const val KEY_COUNT = 18
private val BLACK_PITCHES = setOf(1, 3, 6, 8, 10)
fun isBlack(key: Int) = key % 12 in BLACK_PITCHES

val SONG_LEVELS = listOf("Facili", "Medie", "Impegnative")

/**
 * Parses "Sol La Si:2 Do':0.5": a note name, an apostrophe for the octave up,
 * and the length in beats after the colon (1 if left out).
 */
fun parseNotes(text: String): List<SongNote> = text.trim().split(Regex("\\s+")).map { token ->
    val name = token.substringBefore(':')
    val up = name.count { it == '\'' }
    val pitch = KEY_NAMES.indexOf(name.replace("'", ""))
    require(pitch >= 0) { "nota sconosciuta: $token" }
    val key = pitch + 12 * up
    require(key in 0 until KEY_COUNT) { "nota fuori tastiera: $token" }
    val beats = token.substringAfter(':', "1").toDouble()
    SongNote(key, beats)
}

private fun song(id: String, title: String, bpm: Int, level: Int, notes: String) =
    Song(id, title, bpm, level, parseNotes(notes))

/**
 * Public-domain melodies (traditional, carols, classical), transposed to fit
 * the keys from Do to Fa an octave up. Each was checked to fit the 18 keys.
 */
val SONGS: List<Song> = listOf(
    song("brilla", "Brilla brilla", 104, 1,
        "Do Do Sol Sol La La Sol:2 Fa Fa Mi Mi Re Re Do:2 Sol Sol Fa Fa Mi Mi Re:2 Sol Sol Fa Fa Mi Mi Re:2 Do Do Sol Sol La La Sol:2 Fa Fa Mi Mi Re Re Do:2"),
    song("agnellino", "Maria aveva un agnellino", 112, 1,
        "Mi Re Do Re Mi Mi Mi:2 Re Re Re:2 Mi Sol Sol:2 Mi Re Do Re Mi Mi Mi Mi Re Re Mi Re Do:4"),
    song("luna", "Al chiaro di luna", 100, 1,
        "Fa Fa Fa Sol La:2 Sol:2 Fa La Sol Sol Fa:4 Fa Fa Fa Sol La:2 Sol:2 Fa La Sol Sol Fa:4 Sol Sol Sol Sol Re:2 Re:2 Sol Fa Mi Re Do:4 Fa Fa Fa Sol La:2 Sol:2 Fa La Sol Sol Fa:4"),
    song("martino", "Fra Martino", 120, 1,
        "Sol La Si Sol Sol La Si Sol Si Do' Re':2 Si Do' Re':2 Re':0.5 Mi':0.5 Re':0.5 Do':0.5 Si Sol Re':0.5 Mi':0.5 Re':0.5 Do':0.5 Si Sol Sol Re Sol:2 Sol Re Sol:2"),
    song("gioia", "Inno alla gioia", 112, 1,
        "Mi Mi Fa Sol Sol Fa Mi Re Do Do Re Mi Mi:1.5 Re:0.5 Re:2 Mi Mi Fa Sol Sol Fa Mi Re Do Do Re Mi Re:1.5 Do:0.5 Do:2"),
    song("fattoria", "Nella vecchia fattoria", 120, 1,
        "Fa Fa Fa Do Re Re Do:2 La La Sol Sol Fa:3 Do Fa Fa Fa Do Re Re Do:2 La La Sol Sol Fa:3"),
    song("ponte", "Il ponte di Londra", 112, 1,
        "Sol:1.5 La:0.5 Sol Fa Mi Fa Sol:2 Re Mi Fa:2 Mi Fa Sol:2 Sol:1.5 La:0.5 Sol Fa Mi Fa Sol:2 Re:2 Sol:2 Mi Do:3"),
    song("barca", "Rema la barca", 100, 1,
        "Do Do Do:0.75 Re:0.25 Mi Mi:0.75 Re:0.25 Mi:0.75 Fa:0.25 Sol:2 Do':0.33 Do':0.33 Do':0.34 Sol:0.33 Sol:0.33 Sol:0.34 Mi:0.33 Mi:0.33 Mi:0.34 Do:0.33 Do:0.33 Do:0.34 Sol:0.75 Fa:0.25 Mi:0.75 Re:0.25 Do:2"),
    song("auguri", "Tanti auguri", 100, 1,
        "Do:0.75 Do:0.25 Re Do Fa Mi:2 Do:0.75 Do:0.25 Re Do Sol Fa:2 Do:0.75 Do:0.25 Do' La Fa Mi Re:2 La#:0.75 La#:0.25 La Fa Sol Fa:2"),
    song("jingle", "Jingle Bells", 120, 2,
        "Mi Mi Mi:2 Mi Mi Mi:2 Mi Sol Do:1.5 Re:0.5 Mi:4 Fa Fa Fa:1.5 Fa:0.5 Fa Mi Mi Mi:0.5 Mi:0.5 Mi Re Re Mi Re:2 Sol:2 Mi Mi Mi:2 Mi Mi Mi:2 Mi Sol Do:1.5 Re:0.5 Mi:4 Fa Fa Fa:1.5 Fa:0.5 Fa Mi Mi Mi:0.5 Mi:0.5 Sol Sol Fa Re Do:4"),
    song("saints", "Oh When the Saints", 132, 2,
        "Do Mi Fa Sol:4 Do Mi Fa Sol:4 Do Mi Fa Sol:2 Mi:2 Do:2 Mi:2 Re:4 Mi Mi Re Do:3 Do Mi:2 Sol:2 Sol Fa:3 Mi Fa Sol:2 Mi:2 Do:2 Re:2 Do:4"),
    song("yankee", "Yankee Doodle", 120, 2,
        "Fa:0.5 Fa:0.5 Sol:0.5 La:0.5 Fa:0.5 La:0.5 Sol Fa:0.5 Fa:0.5 Sol:0.5 La:0.5 Fa Mi Fa:0.5 Fa:0.5 Sol:0.5 La:0.5 La#:0.5 La:0.5 Sol:0.5 Fa:0.5 Mi:0.5 Do:0.5 Re:0.5 Mi:0.5 Fa Fa"),
    song("natale", "We Wish You a Merry Christmas", 120, 2,
        "Sol Do' Do':0.5 Re':0.5 Do':0.5 Si:0.5 La La La Re' Re':0.5 Mi':0.5 Re':0.5 Do':0.5 Si Sol Sol Mi' Mi':0.5 Fa':0.5 Mi':0.5 Re':0.5 Do' La Sol:0.5 Sol:0.5 La Re' Si Do':3"),
    song("astro", "Astro del ciel", 80, 2,
        "Sol:1.5 La:0.5 Sol Mi:3 Sol:1.5 La:0.5 Sol Mi:3 Re':2 Re' Si:3 Do':2 Do' Sol:3 La:2 La Do':1.5 Si:0.5 La Sol:1.5 La:0.5 Sol Mi:3 La:2 La Do':1.5 Si:0.5 La Sol:1.5 La:0.5 Sol Mi:3 Re':2 Re' Fa':1.5 Re':0.5 Si Do':3 Mi':3 Do':1.5 Sol:0.5 Mi Sol:1.5 Fa:0.5 Re Do:6"),
    song("brahms", "Ninna nanna di Brahms", 84, 2,
        "Mi:0.5 Mi:0.5 Sol:3 Mi:0.5 Mi:0.5 Sol:3 Mi:0.5 Sol:0.5 Do':2 Si:1.5 La:0.5 La:2 Sol:2 Re:0.5 Mi:0.5 Fa:2 Re Re:0.5 Mi:0.5 Fa:3 Re:0.5 Fa:0.5 Si La Sol:2 Si:2 Do':4"),
    song("grace", "Amazing Grace", 84, 2,
        "Re Sol:2 Si:0.5 Sol:0.5 Si:2 La Sol:2 Mi Re:2 Re Sol:2 Si:0.5 Sol:0.5 Si:2 La Re':3 Si Re':1.5 Si:0.5 Re':0.5 Si:0.5 Sol:2 Re Mi:1.5 Sol:0.5 Sol:0.5 Mi:0.5 Re:2 Re Sol:2 Si:0.5 Sol:0.5 Si:2 La Sol:3"),
    song("greensleeves", "Greensleeves", 100, 3,
        "La Do':2 Re' Mi':1.5 Fa':0.5 Mi' Re':2 Si Sol:1.5 La:0.5 Si Do':2 La La:1.5 Sol#:0.5 La Si:2 Sol# Mi:2 La Do':2 Re' Mi':1.5 Fa':0.5 Mi' Re':2 Si Sol:1.5 La:0.5 Si Do':1.5 Si:0.5 La Sol#:1.5 Fa#:0.5 Sol# La:3"),
    song("minuetto", "Minuetto in Sol", 100, 3,
        "Do' Fa:0.5 Sol:0.5 La:0.5 La#:0.5 Do' Fa Fa Re' La#:0.5 Do':0.5 Re':0.5 Mi':0.5 Fa' Fa Fa La# Do':0.5 La#:0.5 La:0.5 Sol:0.5 La La#:0.5 La:0.5 Sol:0.5 Fa:0.5 Mi Fa:0.5 Sol:0.5 La:0.5 Fa:0.5 Sol:3"),
    song("serenata", "Piccola serenata notturna", 116, 3,
        "Sol Re Sol Re Sol:0.5 Re:0.5 Sol:0.5 Si:0.5 Re':2 Do' La Do' La Do':0.5 La:0.5 Fa#:0.5 La:0.5 Re:2"),
    song("elisa", "Per Elisa", 72, 3,
        "Mi':0.5 Re#':0.5 Mi':0.5 Re#':0.5 Mi':0.5 Si:0.5 Re':0.5 Do':0.5 La:1.5 Do:0.5 Mi:0.5 La:0.5 Si:1.5 Mi:0.5 Sol#:0.5 Si:0.5 Do':1.5 Mi:0.5 Mi':0.5 Re#':0.5 Mi':0.5 Re#':0.5 Mi':0.5 Si:0.5 Re':0.5 Do':0.5 La:1.5 Do:0.5 Mi:0.5 La:0.5 Si:1.5 Mi:0.5 Do':0.5 Si:0.5 La:2"),
    song("montagna", "Nell'antro del re della montagna", 120, 3,
        "Re:0.5 Mi:0.5 Fa:0.5 Sol:0.5 La:0.5 Fa:0.5 La Sol#:0.5 Mi:0.5 Sol# Sol:0.5 Re#:0.5 Sol Re:0.5 Mi:0.5 Fa:0.5 Sol:0.5 La:0.5 Fa:0.5 La:0.5 Re':0.5 Do':0.5 La:0.5 Fa:0.5 La:0.5 Do':2"),
)
