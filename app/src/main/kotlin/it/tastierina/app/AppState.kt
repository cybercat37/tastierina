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

enum class Level(val number: String, val title: String) {
    PAD("0", "Pad"),
    RIPETI("0.5", "Ripeti"),
    TASTI("1", "Tastierina"),
    RITMO("2", "Ritmo"),
    GROOVE("3", "Groove"),
    GROOVEBOX("4", "Groovebox"),
}

enum class PlayMode(val title: String) { IMPARA("Impara"), A_TEMPO("A tempo") }

/** How a song went: shown in a window at the end, kept as a record per song and mode. */
data class PlayResult(
    val song: Song,
    val mode: PlayMode,
    val stars: Int,
    val best: Int,
    val notes: Int,
    val onTime: Int,
    val near: Int,
    val missed: Int,
    val wrong: Int,
    val meanOffsetMs: Int,
    val worstKey: Int?,
)

/** Where a "Ripeti" round is, from the beat being heard. */
enum class RipetiPhase { COUNT_IN, LISTEN, COUNT, YOUR_TURN }

/** Everything the screen shows, and the only place that talks to [Engine]. */
object AppState {
    val padNames = listOf("Cassa", "Rullante", "Clap", "Hat", "Tom", "Cowbell", "Basso", "Accordo")
    val padKinds = listOf("batteria", "batteria", "batteria", "batteria", "batteria", "percussione", "nota", "nota")

    /** In "Tieni il tempo" the player keeps the beat on this pad. */
    const val TARGET_PAD = 0
    const val BPM_MIN = 60
    const val BPM_MAX = 140

    /** A round of Ripeti: listen bar, count-in bar (4-3-2-1), then the player's bars until they move on. */
    const val COUNT_AT = 4.0
    const val ANSWER_AT = 8.0
    /** A bar is judged once its hit window (engine::repeat::BAR_LEAD) has closed. */
    private const val JUDGE_AFTER = 3.8
    /** How long the verdict of a bar stays up at the start of the next one, in beats. */
    const val VERDICT_BEATS = 1.0

    /** Frame time of the current UI frame, System.nanoTime() clock. */
    var now by mutableLongStateOf(0L)
        private set

    /** When each pad was last hit or played, same clock as [now]. */
    val flashes = mutableStateListOf<Long>().apply { repeat(8) { add(Long.MIN_VALUE / 2) } }

    var level by mutableStateOf(Level.PAD)
        private set
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

    // Ripeti
    var round by mutableIntStateOf(1)
        private set
    var bestRound by mutableIntStateOf(0)
        private set
    /** Beat where the current round's listening bar starts. */
    var roundStart by mutableLongStateOf(4L)
        private set
    var phrase by mutableStateOf<List<PhraseNote>>(emptyList())
        private set
    /** Verdict of the player's last bar, and the beat where that bar ended. */
    var evaluation by mutableStateOf<Evaluation?>(null)
        private set
    var evaluationEnd by mutableStateOf(Double.NaN)
        private set
    var attempts by mutableIntStateOf(0)
        private set
    var passes by mutableIntStateOf(0)
        private set
    var inARow by mutableIntStateOf(0)
        private set
    /** Set when the player asked for "Risenti" or "Prossima": the new round starts at this beat. */
    var pendingStart by mutableStateOf<Long?>(null)
        private set
    var pendingIsReplay by mutableStateOf(false)
        private set
    private var pendingPhrase: List<PhraseNote> = emptyList()
    private var pendingRound = 1
    /** Start of the next player's bar to judge. */
    private var nextBar = 0.0
    /** False until the fresh beat grid is seen: a grid left over from before must not end a round. */
    private var armed = false
    private var previousPosition = Double.NaN

    private var prefs: SharedPreferences? = null
    private var frames = 0

    // Tastierina
    /** Same order as engine::voices::Timbre. */
    val timbreNames = listOf("Piano", "Piano el.", "Organo", "Archi", "Flauto", "Chitarra", "Marimba", "Synth", "Campana", "Basso", "Lead")
    /** Engine index of each name above. */
    val timbreIds = listOf(0, 8, 1, 4, 5, 6, 7, 2, 3, 9, 10)
    var timbre by mutableIntStateOf(0)
        private set
    var octave by mutableIntStateOf(0)
        private set
    /** How many fingers hold each key (the note sounds while > 0). */
    val held = mutableStateListOf<Int>().apply { repeat(KEY_COUNT) { add(0) } }
    var song by mutableStateOf<Song?>(SONGS.first())
        private set
    var songStep by mutableIntStateOf(0)
        private set
    var songDone by mutableStateOf(false)
        private set
    /** "Ascolta": beat where each note of the song starts, while the song plays by itself. */
    var demoStarts by mutableStateOf<List<Double>?>(null)
        private set
    /** Key the demo is playing now, or -1. */
    var demoKey by mutableIntStateOf(-1)
        private set
    private var demoEnd = 0.0

    var playMode by mutableStateOf(PlayMode.IMPARA)
        private set
    /** "A tempo" at 70% of the song's tempo. */
    var slow by mutableStateOf(true)
        private set
    /** Per note of the song: 0 waiting; Impara 1 right, 2 right after errors; A tempo a [Mark] ordinal. */
    val marks = mutableStateListOf<Int>()
    var errors by mutableIntStateOf(0)
        private set
    var rightNotes by mutableIntStateOf(0)
        private set
    private val wrongByKey = IntArray(KEY_COUNT)
    private var stepErrors = 0
    /** "A tempo" in progress. */
    var takeRunning by mutableStateOf(false)
        private set
    /** Beat of the first note of a take: after a bar of 4-3-2-1. */
    val takeFirstBeat = 4.0
    var result by mutableStateOf<PlayResult?>(null)
        private set

    // Ritmo
    /** The bar on screen: one 16-bit mask per drum sound (by engine id), bit s is step s. */
    val pattern = mutableStateListOf<Int>().apply { repeat(DRUM_SOUNDS.size) { add(0) } }
    /** The three giri as stored, every bar. [pattern], [bassNotes] and [leadNotes] show one bar of one of them. */
    private val store = Array(GIRO_COUNT) { GiroData() }
    /** The giro on screen: the one played and edited. Always A below level 4. */
    var giro by mutableIntStateOf(0)
        private set
    /** Bars of that giro, and the bar on screen. */
    var bars by mutableIntStateOf(1)
        private set
    var page by mutableIntStateOf(0)
        private set
    /** While playing, the grid follows the bar being heard. */
    var followPage by mutableStateOf(true)
        private set
    /** A giro chosen while playing, waiting for the end of the loop; -1 for none. */
    var queuedGiro by mutableIntStateOf(-1)
        private set
    val chain = mutableStateListOf(ChainStep(0, 4), ChainStep(1, 4))
    var chainOn by mutableStateOf(false)
        private set
    /** Step of the scaletta being played and how many times it has played already; -1 when it is not driving. */
    var chainPos by mutableIntStateOf(-1)
        private set
    var chainRep by mutableIntStateOf(0)
        private set
    /** How long a bass note is held, percent of its step. */
    var bassGate by mutableIntStateOf(55)
        private set
    /** Level 4, the third view: giri, bars, scaletta. */
    var giroView by mutableStateOf(false)
        private set
    /** Step of the giro being heard, or -1. */
    private var playStep by mutableIntStateOf(-1)
    private val full: Boolean get() = level == Level.GROOVEBOX
    private val notesLive: Boolean get() = level == Level.GROOVE || level == Level.GROOVEBOX
    private val gridLevel: Boolean get() = level == Level.RITMO || notesLive
    /** Below level 4 only the first bar of giro A plays. */
    private fun barsOf(g: Int) = if (full) store[g].bars else 1
    /** Sounds shown as rows, in id order. */
    val rows = mutableStateListOf<Int>().apply { addAll(DEFAULT_ROWS) }
    /** Lessons finished at least once. */
    val lessonsDone = mutableStateListOf<String>()
    var drumsPlaying by mutableStateOf(false)
        private set
    var drumBpm by mutableIntStateOf(124)
        private set
    var lesson by mutableStateOf<Lesson?>(LESSONS.first())
        private set
    val lessonNow: LessonState?
        get() = if (page != 0) null else lesson?.let { lessonState(it, pattern, if (notesLive) bassNotes else null) }

    // Groove: two note rows on the same sixteen steps. Engine rows: 0 bass, 1 melody.
    val bassNotes = mutableStateListOf<Int>().apply { repeat(DRUM_STEPS) { add(-1) } }
    val leadNotes = mutableStateListOf<Int>().apply { repeat(DRUM_STEPS) { add(-1) } }
    val rowNames = listOf("Basso", "Melodia")
    private val rowBase = intArrayOf(36, 60)
    private val rowTimbre = intArrayOf(9, 10)
    /** false: the drum grid; true: the note rows with the keys. */
    var noteView by mutableStateOf(false)
        private set
    /** The row the keys play and write to. */
    var recTarget by mutableIntStateOf(0)
        private set
    var recArmed by mutableStateOf(false)
        private set
    /** Bass filter, 0 dark to 10 bright. */
    var bassCut by mutableIntStateOf(5)
        private set
    /** The last key played: the note a tapped cell takes. */
    var chosenKey by mutableIntStateOf(-1)
        private set
    /** A one-off message for the display, until the next touch. */
    var grooveHint by mutableStateOf("")
        private set
    /** MIDI note each held key started, so it is the one released. */
    private val heldMidi = IntArray(KEY_COUNT)

    fun notesOf(row: Int) = if (row == 0) bassNotes else leadNotes
    fun noteAt(row: Int, step: Int) = notesOf(row)[step]

    /** The key the bass lesson is waiting for you to choose, or -1. */
    val bassNeed: Int
        get() {
            val st = lessonNow as? LessonState.Bass ?: return -1
            if (!noteView || recArmed || recTarget != 0 || st.extra.isNotEmpty() || st.missing.isEmpty()) return -1
            val w = st.want[st.missing.min()] ?: return -1
            return if (chosenKey >= 0 && chosenKey % 12 == w % 12) -1 else w
        }
    /** Step under the playhead, or -1. */
    val drumStep: Int
        get() {
            return if (!drumsPlaying || playStep < 0 || playStep / 16 != page) -1 else playStep % 16
        }

    val metronomeRunning: Boolean get() = tempo || level == Level.RIPETI || demoStarts != null || takeRunning || drumsPlaying

    /** Beats since the start of the current Ripeti round, or NaN. */
    val ripetiBeat: Double get() = beatPosition - roundStart

    val ripetiPhase: RipetiPhase
        get() {
            val pos = beatPosition
            if (pos.isNaN()) return RipetiPhase.COUNT_IN
            val rel = pos - roundStart
            return when {
                rel < 0 -> RipetiPhase.COUNT_IN
                rel < COUNT_AT -> RipetiPhase.LISTEN
                rel < ANSWER_AT -> RipetiPhase.COUNT
                else -> RipetiPhase.YOUR_TURN
            }
        }

    fun load(context: Context) {
        val p = context.getSharedPreferences("tastierina", Context.MODE_PRIVATE)
        prefs = p
        volume = p.getFloat("volume", 0.8f)
        bpm = p.getInt("bpm", 90).coerceIn(BPM_MIN, BPM_MAX)
        best = p.getInt("best", 0)
        bestRound = p.getInt("bestRound", 0)
        timbre = p.getInt("timbre", 0).takeIf { it in timbreIds } ?: 0
        octave = p.getInt("octave", 0).coerceIn(-2, 2)
        val id = p.getString("song", SONGS.first().id)
        song = SONGS.firstOrNull { it.id == id }
        level = Level.entries.getOrNull(p.getInt("level", 0)) ?: Level.PAD
        playMode = PlayMode.entries.getOrNull(p.getInt("playMode", 0)) ?: PlayMode.IMPARA
        slow = p.getBoolean("slow", true)
        drumBpm = p.getInt("drumBpm", 124).coerceIn(70, 180)
        lesson = LESSONS.firstOrNull { it.id == p.getString("lesson", LESSONS.first().id) }
        val savedGiri = p.getString("giri", null)?.split('|')
        if (savedGiri != null) {
            savedGiri.forEachIndexed { i, text -> if (i < GIRO_COUNT) GiroData.decode(text)?.let { store[i].copyFrom(it) } }
        } else {
            // Saved before giri existed: one bar of drums, bass and melody.
            p.getString("pattern", null)?.split(',')?.forEachIndexed { i, v ->
                if (i < store[0].drums.size) store[0].drums[i] = (v.toLongOrNull() ?: 0L) and 0xFFFFL
            }
            for ((key, row) in listOf("bassNotes" to 0, "leadNotes" to 1)) {
                p.getString(key, null)?.split(',')?.forEachIndexed { i, v ->
                    if (i < DRUM_STEPS) store[0].notes(row)[i] = (v.toIntOrNull() ?: -1).coerceIn(-1, KEY_COUNT - 1)
                }
            }
        }
        // Since the grid scrolls, every sound is a row unless the player chose otherwise.
        val savedRows = p.getString("rows2", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?.filter { it in DRUM_SOUNDS.indices }?.distinct()?.takeIf { it.isNotEmpty() }
        rows.clear()
        rows.addAll((savedRows ?: DRUM_SOUNDS.indices.toList()).sorted())
        p.getString("lessonsDone", "")?.split(',')?.filter { it.isNotBlank() }?.let { lessonsDone.addAll(it) }
        bassCut = p.getInt("bassCut", 5).coerceIn(0, 10)
        recTarget = p.getInt("recTarget", 0).coerceIn(0, 1)
        bassGate = p.getInt("bassGate", 55).coerceIn(15, 100)
        chainOn = p.getBoolean("chainOn", false)
        p.getString("chain", null)?.split(',')?.mapNotNull { item ->
            val g = item.substringBefore('x').toIntOrNull()
            val n = item.substringAfter('x', "").toIntOrNull()
            if (g != null && g in 0 until GIRO_COUNT && n != null && n in CHAIN_REPS) ChainStep(g, n) else null
        }?.take(CHAIN_MAX)?.takeIf { it.isNotEmpty() }?.let {
            chain.clear()
            chain.addAll(it)
        }
        giro = if (full) p.getInt("giro", 0).coerceIn(0, GIRO_COUNT - 1) else 0
        showGiroPage(0)
        resetMarks()
    }

    fun bestStars(sg: Song, mode: PlayMode): Int = prefs?.getInt("stars_${sg.id}_${mode.name}", -1) ?: -1

    private fun save() {
        prefs?.edit()
            ?.putFloat("volume", volume)
            ?.putInt("bpm", bpm)
            ?.putInt("best", best)
            ?.putInt("bestRound", bestRound)
            ?.putInt("timbre", timbre)
            ?.putInt("octave", octave)
            ?.putString("song", song?.id ?: "")
            ?.putInt("level", level.ordinal)
            ?.putInt("playMode", playMode.ordinal)
            ?.putBoolean("slow", slow)
            ?.putInt("drumBpm", drumBpm)
            ?.putString("lesson", lesson?.id ?: "")
            ?.putString("giri", store.joinToString("|") { it.encode() })
            ?.putString("chain", chain.joinToString(",") { "${it.giro}x${it.reps}" })
            ?.putBoolean("chainOn", chainOn)
            ?.putInt("bassGate", bassGate)
            ?.putInt("giro", giro)
            ?.putString("rows2", rows.joinToString(","))
            ?.putString("lessonsDone", lessonsDone.joinToString(","))
            ?.putInt("bassCut", bassCut)
            ?.putInt("recTarget", recTarget)
            ?.apply()
    }

    /** Opens audio and restores what the engine should be doing. */
    fun startAudio() {
        audioOk = Engine.nativeStart()
        Engine.nativeSetVolume(volume)
        if (tempo) Engine.nativeSetMetronome(true, bpm.toFloat())
        if (level == Level.RIPETI) startRipeti()
        pushPattern()
    }

    fun stopAudio() {
        // A new stream starts clean: nothing held, nothing playing by itself.
        takeRunning = false
        recArmed = false
        drumsPlaying = false
        demoStarts = null
        demoKey = -1
        for (i in 0 until KEY_COUNT) held[i] = 0
        Engine.nativeStop()
    }

    /** Called once per UI frame. */
    fun tick(frameNanos: Long) {
        now = frameNanos
        val pos = if (metronomeRunning) Engine.nativeBeatPosition(System.nanoTime()) else Double.NaN
        beatPosition = pos
        if (level == Level.RIPETI && !pos.isNaN()) followRipeti(pos)
        if (demoStarts != null && !pos.isNaN()) followDemo(pos)
        if (takeRunning && !pos.isNaN()) followTake()
        if (drumsPlaying) followSequencer(pos)
        previousPosition = pos
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

    fun selectLevel(l: Level) {
        if (l == level) return
        if (level == Level.RIPETI) stopRipeti()
        if (level == Level.TASTI) leaveKeyboard()
        if (gridLevel) stopDrums()
        if (notesLive) releaseAllKeys()
        if (tempo) setTempoMode(false)
        level = l
        if (l == Level.RIPETI) startRipeti()
        if (gridLevel) {
            // Each level plays its part of the same song: send the engine what this one plays.
            forgetCleared()
            if (!notesLive) noteView = false
            if (!full) {
                giroView = false
                giro = 0
            }
            showGiroPage(0)
            pushPattern()
        }
        save()
    }

    fun setTempoMode(on: Boolean) {
        tempo = on
        streak = 0
        lastJudgment = null
        Engine.nativeSetMetronome(on, bpm.toFloat())
    }

    fun changeBpm(delta: Int) {
        if ((bpm + delta).coerceIn(BPM_MIN, BPM_MAX) == bpm) return
        bpm = (bpm + delta).coerceIn(BPM_MIN, BPM_MAX)
        if (metronomeRunning) Engine.nativeSetMetronome(true, bpm.toFloat())
        save()
    }

    fun changeVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        Engine.nativeSetVolume(volume)
        save()
    }

    // ---- Ripeti ------------------------------------------------------------

    /** From round 1: a bar of count-in, then listen, count, and the player's bars. */
    fun startRipeti() {
        Engine.nativeRepeatStop()
        Engine.nativeSetMetronome(false, bpm.toFloat())
        Engine.nativeSetMetronome(true, bpm.toFloat())
        round = 1
        armed = false
        previousPosition = Double.NaN
        pendingStart = null
        beginRound(4L, unpackPhrase(Engine.nativeRepeatRound(round, 4L, false, System.nanoTime())))
    }

    private fun beginRound(start: Long, notes: List<PhraseNote>) {
        roundStart = start
        phrase = notes
        nextBar = start + ANSWER_AT
        evaluation = null
        evaluationEnd = Double.NaN
        attempts = 0
        passes = 0
        inARow = 0
    }

    private fun stopRipeti() {
        Engine.nativeRepeatStop()
        Engine.nativeSetMetronome(false, bpm.toFloat())
        phrase = emptyList()
        evaluation = null
        pendingStart = null
    }

    /** "Prossima": a new phrase, longer if at least one bar was right. */
    fun nextPhrase() = scheduleRound(replay = false)

    /** "Risenti": the same phrase again, with its count-in. */
    fun replayPhrase() = scheduleRound(replay = true)

    private fun scheduleRound(replay: Boolean) {
        val pos = beatPosition
        if (level != Level.RIPETI || pendingStart != null || pos.isNaN()) return
        // The next bar line, at least half a beat away so the first note is scheduled in time.
        var start = roundStart + 4L * kotlin.math.ceil((pos - roundStart) / 4.0).toLong()
        if (start - pos < 0.5) start += 4L
        val nextRound = if (!replay && passes > 0) round + 1 else round
        pendingRound = nextRound
        pendingIsReplay = replay
        pendingPhrase = unpackPhrase(Engine.nativeRepeatRound(nextRound, start, replay, System.nanoTime()))
        pendingStart = start
    }

    private fun followRipeti(pos: Double) {
        if (!armed) {
            if (pos < roundStart) armed = true else return
        }
        // Light the pads as the phrase plays, so eyes follow ears.
        if (!previousPosition.isNaN()) {
            for (n in phrase) {
                val at = roundStart + n.beat
                if (previousPosition < at && pos >= at) flashes[n.pad] = System.nanoTime()
            }
        }
        val pending = pendingStart
        // Judge each finished bar of the player's turn, but not one cut short by a new round.
        if (pos >= nextBar + JUDGE_AFTER && (pending == null || nextBar + 4 <= pending)) {
            val e = Evaluation.unpack(Engine.nativeRepeatResult(nextBar.toLong()))
            evaluation = e
            evaluationEnd = nextBar + 4
            attempts += 1
            if (e.passed) {
                passes += 1
                inARow += 1
                if (round > bestRound) {
                    bestRound = round
                    save()
                }
            } else {
                inARow = 0
            }
            nextBar += 4
        }
        if (pending != null && pos >= pending) {
            round = pendingRound
            pendingStart = null
            beginRound(pending, pendingPhrase)
        }
    }

    // ---- Tastierina ----------------------------------------------------------

    private fun midiOf(key: Int) = 60 + 12 * octave + key

    fun keyDown(key: Int, touchNanos: Long = System.nanoTime()) {
        if (key !in 0 until KEY_COUNT) return
        held[key] = held[key] + 1
        if (held[key] != 1) return
        if (notesLive) {
            // The keys sound like the row they write to, and choose the note for its cells.
            heldMidi[key] = rowBase[recTarget] + key
            Engine.nativeNoteOn(heldMidi[key], rowTimbre[recTarget], 0.9f)
            chosenKey = key
            grooveHint = ""
            if (recArmed && drumsPlaying) {
                val step = Engine.nativeRecord(recTarget, key, touchNanos)
                if (step in 0 until GIRO_STEPS) {
                    store[giro].notes(recTarget)[step] = key
                    if (step / 16 == page) notesOf(recTarget)[step % 16] = key
                    afterEdit()
                }
            }
            return
        }
        heldMidi[key] = midiOf(key)
        Engine.nativeNoteOn(heldMidi[key], timbre, 0.9f)
        when {
            takeRunning -> takeHit(key, touchNanos)
            playMode == PlayMode.IMPARA -> followSong(key)
            else -> {} // A tempo, before Via!: free playing
        }
    }

    fun keyUp(key: Int) {
        if (key !in 0 until KEY_COUNT || held[key] == 0) return
        held[key] = held[key] - 1
        if (held[key] == 0) Engine.nativeNoteOff(heldMidi[key])
    }

    private fun releaseAllKeys() {
        Engine.nativeAllNotesOff()
        for (i in 0 until KEY_COUNT) held[i] = 0
    }

    /** [t] is an engine timbre id (see [timbreIds]). */
    fun selectTimbre(t: Int) {
        timbre = t.takeIf { it in timbreIds } ?: 0
        save()
    }

    fun changeOctave(delta: Int) {
        val o = (octave + delta).coerceIn(-2, 2)
        if (o == octave) return
        releaseAllKeys()
        octave = o
        save()
    }

    fun selectSong(s: Song?) {
        stopDemo()
        stopTake()
        song = s
        restartSong()
        save()
    }

    fun selectPlayMode(m: PlayMode) {
        if (m == playMode) return
        stopDemo()
        stopTake()
        playMode = m
        restartSong()
        save()
    }

    fun toggleSlow() {
        slow = !slow
        save()
    }

    private fun resetMarks() {
        marks.clear()
        repeat(song?.notes?.size ?: 0) { marks.add(0) }
        errors = 0
        rightNotes = 0
        stepErrors = 0
        wrongByKey.fill(0)
    }

    private fun restartSong() {
        songStep = 0
        songDone = false
        resetMarks()
    }

    fun dismissResult() {
        result = null
    }

    /** Saves the stars if they beat the record, and shows the result window. */
    private fun finish(sg: Song, mode: PlayMode, s: TakeSummary) {
        val key = "stars_${sg.id}_${mode.name}"
        val before = prefs?.getInt(key, -1) ?: -1
        if (s.stars > before) prefs?.edit()?.putInt(key, s.stars)?.apply()
        result = PlayResult(
            sg, mode, s.stars, maxOf(before, s.stars), s.notes, s.onTime, s.near, s.missed, s.wrong, s.meanOffsetMs, s.worstKey,
        )
    }

    /** Impara: the song waits; the dot moves on only with the right key. Wrong keys are counted. */
    private fun followSong(key: Int) {
        val sg = song ?: return
        if (demoStarts != null) return
        if (songDone) restartSong()
        val expected = sg.notes[songStep].key
        if (key != expected) {
            errors += 1
            stepErrors += 1
            wrongByKey[expected] += 1
            return
        }
        marks[songStep] = if (stepErrors == 0) 1 else 2
        if (stepErrors == 0) rightNotes += 1
        stepErrors = 0
        songStep += 1
        if (songStep >= sg.notes.size) {
            songStep = sg.notes.size - 1
            songDone = true
            val n = sg.notes.size
            val ratio = errors.toDouble() / n
            val stars = when {
                errors == 0 -> 3
                ratio <= 0.1 -> 2
                ratio <= 0.3 -> 1
                else -> 0
            }
            val worst = wrongByKey.indices.maxByOrNull { wrongByKey[it] }?.takeIf { wrongByKey[it] >= 2 }
            finish(sg, PlayMode.IMPARA, TakeSummary(n, rightNotes, 0, 0, errors, stars, worst, 0))
        }
    }

    // ---- A tempo -------------------------------------------------------------

    /** Starts the metronome, counts 4-3-2-1 and expects every note on its beat. */
    fun startTake() {
        val sg = song ?: return
        stopDemo()
        releaseAllKeys()
        restartSong()
        result = null
        val speed = if (slow) 0.7f else 1f
        val tempo = (sg.bpm * speed).coerceIn(40f, 180f)
        Engine.nativeClearScheduled()
        Engine.nativeSetMetronome(false, tempo)
        Engine.nativeSetClick(true)
        Engine.nativeSetMetronome(true, tempo)
        // The bar before the first note counts 4, 3, 2, 1 with its own beep.
        for (k in 0 until 4) Engine.nativeScheduleCount(k.toDouble(), k == 3)
        Engine.nativeTakeReset()
        var t = takeFirstBeat
        for (n in sg.notes) {
            Engine.nativeTakeAddNote(n.key, t)
            t += n.beats
        }
        takeRunning = true
    }

    fun stopTake() {
        if (!takeRunning) return
        takeRunning = false
        Engine.nativeClearScheduled()
        Engine.nativeSetMetronome(false, bpm.toFloat())
    }

    private fun takeHit(key: Int, touchNanos: Long) {
        val r = Engine.nativeTakeHit(key, touchNanos)
        when {
            r >= 0 -> {
                val i = (r shr 8).toInt()
                if (i in marks.indices) marks[i] = (r and 0xFF).toInt()
                rightNotes += 1
            }
            r == -1L -> errors += 1
        }
    }

    private fun followTake() {
        val sg = song ?: return
        if (Engine.nativeTakeExpire(System.nanoTime()) > 0) {
            for (i in marks.indices) marks[i] = Engine.nativeTakeMark(i)
        }
        val next = Engine.nativeTakeNextPending()
        if (next >= 0) {
            songStep = next
        } else {
            takeRunning = false
            Engine.nativeSetMetronome(false, bpm.toFloat())
            songDone = true
            finish(sg, PlayMode.A_TEMPO, TakeSummary.unpack(Engine.nativeTakeSummary()))
        }
    }

    /** "Ascolta": the engine plays the whole song on the beat grid, with the click muted. */
    fun startDemo() {
        val sg = song ?: return
        stopTake()
        releaseAllKeys()
        Engine.nativeClearScheduled()
        Engine.nativeSetMetronome(false, sg.bpm.toFloat())
        Engine.nativeSetClick(false)
        Engine.nativeSetMetronome(true, sg.bpm.toFloat())
        val starts = ArrayList<Double>(sg.notes.size)
        var t = 1.0
        for (n in sg.notes) {
            starts += t
            Engine.nativeScheduleNote(midiOf(n.key), timbre, t, n.beats * 0.88)
            t += n.beats
        }
        demoEnd = t
        demoStarts = starts
        songStep = 0
        songDone = false
    }

    fun stopDemo() {
        if (demoStarts == null) return
        demoStarts = null
        demoKey = -1
        Engine.nativeClearScheduled()
        Engine.nativeSetMetronome(false, bpm.toFloat())
        Engine.nativeSetClick(true)
        songStep = 0
    }

    private fun followDemo(pos: Double) {
        val starts = demoStarts ?: return
        val sg = song ?: return
        val i = starts.indexOfLast { it <= pos }
        if (i >= 0) {
            songStep = i
            val noteEnd = starts[i] + sg.notes[i].beats * 0.88
            demoKey = if (pos < noteEnd) sg.notes[i].key else -1
        }
        if (pos >= demoEnd + 0.3) stopDemo()
    }

    private fun leaveKeyboard() {
        stopDemo()
        stopTake()
        releaseAllKeys()
    }

    // ---- Ritmo ---------------------------------------------------------------

    /** Sends one giro to the engine as this level plays it. */
    private fun pushGiro(g: Int) {
        val data = store[g]
        Engine.nativeClearGiro(g)
        Engine.nativeSetBars(g, barsOf(g))
        data.drums.forEachIndexed { t, mask -> if (mask != 0L) Engine.nativeSetPattern(g, t, mask) }
        if (notesLive) {
            for (row in 0..1) data.notes(row).forEachIndexed { step, key -> if (key >= 0) Engine.nativeSetNote(g, row, step, key) }
        }
    }

    private fun pushChain() {
        chain.forEachIndexed { i, st -> Engine.nativeSetChain(i, st.giro, st.reps) }
        Engine.nativeChain(chain.size, chainOn && full)
    }

    /** Sends the whole song to the engine. */
    private fun pushPattern() {
        for (g in 0 until GIRO_COUNT) pushGiro(g)
        pushChain()
        Engine.nativeBassFilter(bassCut / 10f)
        Engine.nativeBassGate(if (full) bassGate / 100f else 0.55f)
        if (!drumsPlaying) Engine.nativeSelectGiro(giro)
    }

    /** Copies the bar on screen from the stored giro into the lists the grids draw. */
    private fun loadPage() {
        val data = store[giro]
        for (t in pattern.indices) pattern[t] = data.pageMask(t, page)
        for (i in 0 until DRUM_STEPS) {
            bassNotes[i] = data.bass[page * 16 + i]
            leadNotes[i] = data.lead[page * 16 + i]
        }
    }

    private fun showGiroPage(wanted: Int) {
        bars = barsOf(giro)
        page = wanted.coerceIn(0, bars - 1)
        loadPage()
    }

    /** While playing: which step is heard, which giro and scaletta step the engine is on. */
    private fun followSequencer(heardBeat: Double) {
        val v = Engine.nativeSeqState()
        val running = ((v shr 53) and 1L) == 1L
        if (!running || heardBeat.isNaN()) return
        val nextStep = v and 0xFFFFFFFFL
        val pos = ((v shr 32) and 0xFFL).toInt()
        val engineGiro = ((v shr 40) and 3L).toInt()
        val engineBars = ((v shr 42) and 7L).toInt().coerceAtLeast(1)
        val cp = ((v shr 45) and 15L).toInt()
        chainPos = if (cp == 15) -1 else cp
        chainRep = ((v shr 49) and 15L).toInt()
        queuedGiro = ((v shr 54) and 3L).toInt() - 1
        if (full && engineGiro != giro && engineGiro in 0 until GIRO_COUNT) {
            giro = engineGiro
            forgetCleared()
            showGiroPage(0)
        }
        val heard = kotlin.math.floor(heardBeat * 4).toLong()
        playStep = Math.floorMod(pos + (heard - nextStep), engineBars * 16L).toInt()
        val bar = playStep / 16
        if (followPage && bar != page && bar < bars) {
            page = bar
            loadPage()
        }
    }

    /** After any change to the giro: lesson progress, the view the lesson needs, saving. */
    private fun afterEdit() {
        forgetCleared()
        val st = lessonNow
        if (st is LessonState.Done && st.lesson.id !in lessonsDone) lessonsDone.add(st.lesson.id)
        if (notesLive && st is LessonState.Bass && !noteView) {
            giroView = false
            noteView = true
        }
        save()
    }

    // ---- Groove --------------------------------------------------------------

    fun showNotes(on: Boolean) {
        if (!on) releaseAllKeys()
        giroView = false
        noteView = on
    }

    fun showGiro() {
        releaseAllKeys()
        noteView = false
        giroView = true
    }

    fun selectTarget(row: Int) {
        if (row !in 0..1 || row == recTarget) return
        releaseAllKeys()
        recTarget = row
        save()
    }

    /** Recording starts the giro if it is still: notes played on the keys land on the nearest step. */
    fun toggleRec() {
        if (recArmed) {
            recArmed = false
        } else {
            if (!drumsPlaying) playDrums()
            recArmed = drumsPlaying
        }
    }

    fun changeBassCut(delta: Int) {
        bassCut = (bassCut + delta).coerceIn(0, 10)
        Engine.nativeBassFilter(bassCut / 10f)
        save()
    }

    /**
     * Turns a note cell on (with the chosen key) or off. In the bass lesson a
     * green cell only takes its own note. Returns whether anything changed.
     */
    fun setNote(row: Int, step: Int, on: Boolean): Boolean {
        if (row !in 0..1 || step !in 0 until DRUM_STEPS) return false
        val list = notesOf(row)
        if (on == (list[step] >= 0)) return false
        if (on) {
            val key = if (chosenKey >= 0) chosenKey else 9
            val st = lessonNow
            if (row == 0 && st is LessonState.Bass) {
                val w = st.want[step]
                if (w != null && key % 12 != w % 12) {
                    grooveHint = "prima suona ${keyName(w)} sui tasti, poi tocca la casella"
                    return false
                }
            }
            list[step] = key
        } else {
            list[step] = -1
        }
        grooveHint = ""
        store[giro].notes(row)[page * 16 + step] = list[step]
        Engine.nativeSetNote(giro, row, page * 16 + step, list[step])
        afterEdit()
        return true
    }

    fun isOn(track: Int, step: Int) = pattern[track] and (1 shl step) != 0

    fun setStep(track: Int, step: Int, on: Boolean) {
        if (track !in pattern.indices || step !in 0 until DRUM_STEPS || isOn(track, step) == on) return
        forgetCleared()
        pattern[track] = if (on) pattern[track] or (1 shl step) else pattern[track] and (1 shl step).inv()
        val at = page * 16 + step
        val row = store[giro].drums
        row[track] = if (on) row[track] or (1L shl at) else row[track] and (1L shl at).inv()
        Engine.nativeSetStep(giro, track, at, on)
        if (on && !drumsPlaying) Engine.nativeDrum(track)
        afterEdit()
    }

    /** Shows or hides a sound's row. Hiding clears its steps, so nothing plays unseen. */
    fun toggleRow(id: Int) {
        if (id !in DRUM_SOUNDS.indices) return
        forgetCleared()
        if (id in rows) {
            if (rows.size <= 1) return
            rows.remove(id)
            silenceSound(id)
        } else {
            if (rows.size >= MAX_ROWS) return
            rows.add(id)
            rows.sort()
            Engine.nativeDrum(id)
        }
        save()
    }

    /** A hidden sound must not play: its steps go, in every giro. */
    private fun silenceSound(id: Int) {
        pattern[id] = 0
        for (g in 0 until GIRO_COUNT) {
            if (store[g].drums[id] != 0L) {
                store[g].drums[id] = 0L
                Engine.nativeSetPattern(g, id, 0L)
            }
        }
    }

    fun showAllRows() {
        forgetCleared()
        rows.clear()
        rows.addAll(DRUM_SOUNDS.indices)
        save()
    }

    /** The five basic sounds, plus what the lesson in use needs. Hidden rows lose their steps. */
    fun showBaseRows() {
        forgetCleared()
        val keep = (DEFAULT_ROWS + (lesson?.sounds ?: emptyList())).distinct().sorted()
        for (id in DRUM_SOUNDS.indices) {
            if (id !in keep) silenceSound(id)
        }
        rows.clear()
        rows.addAll(keep)
        save()
    }

    /** A lesson brings the rows it needs; other rows go to make room. */
    private fun showRowsFor(l: Lesson) {
        val need = l.sounds
        for (id in need) if (id !in rows) rows.add(id)
        var i = rows.size - 1
        while (rows.size > MAX_ROWS && i >= 0) {
            if (rows[i] !in need) rows.removeAt(i)
            i--
        }
        rows.sort()
    }

    /** Empties the giro in use: the drums, and from level 3 the note rows too. */
    fun clearPattern() {
        store[giro].clear(notes = notesLive)
        pushGiro(giro)
        loadPage()
        save()
    }

    /** What "Svuota" removed and from which giro, until the next change: "Annulla" brings it back. */
    private var cleared: Pair<Int, GiroData>? = null
    var canUndoClear by mutableStateOf(false)
        private set

    private fun forgetCleared() {
        cleared = null
        canUndoClear = false
    }

    fun clearByUser() {
        if (store[giro].isEmpty(notes = notesLive)) return
        cleared = giro to store[giro].copy()
        canUndoClear = true
        clearPattern()
    }

    fun undoClear() {
        val (g, before) = cleared ?: return
        store[g].copyFrom(before)
        forgetCleared()
        pushGiro(g)
        if (g == giro) showGiroPage(page)
        save()
    }

    fun previewDrum(id: Int) {
        if (id in DRUM_SOUNDS.indices) Engine.nativeDrum(id)
    }

    /** A lesson starts from an empty grid at its own tempo. */
    fun selectLesson(l: Lesson?) {
        lesson = l
        forgetCleared()
        grooveHint = ""
        if (l != null) {
            // A lesson is built in one bar, on the grid.
            noteView = false
            giroView = false
            if (full) store[giro].bars = 1
            showRowsFor(l)
            clearPattern()
            showGiroPage(0)
            applyDrumBpm(l.bpm)
        }
        save()
    }

    fun playDrums() {
        if (drumsPlaying) return
        Engine.nativeSetMetronome(false, drumBpm.toFloat())
        Engine.nativeSetClick(false)
        Engine.nativeSetMetronome(true, drumBpm.toFloat())
        pushPattern()
        Engine.nativeSequencer(true)
        drumsPlaying = true
    }

    fun stopDrums() {
        if (!drumsPlaying) return
        drumsPlaying = false
        recArmed = false
        playStep = -1
        queuedGiro = -1
        chainPos = -1
        Engine.nativeSequencer(false)
        Engine.nativeSetMetronome(false, drumBpm.toFloat())
        Engine.nativeSetClick(true)
    }

    fun changeDrumBpm(delta: Int) = applyDrumBpm(drumBpm + delta)

    private fun applyDrumBpm(v: Int) {
        drumBpm = v.coerceIn(70, 180)
        if (drumsPlaying) Engine.nativeSetMetronome(true, drumBpm.toFloat())
        save()
    }

    // ---- Groovebox ------------------------------------------------------------

    /** Picks the giro to play and edit. While playing it comes in at the end of the loop. */
    fun selectGiro(g: Int) {
        if (!full || g !in 0 until GIRO_COUNT) return
        if (drumsPlaying) {
            if (chainOn) {
                grooveHint = "scaletta accesa: i giri cambiano da soli"
                return
            }
            Engine.nativeSelectGiro(g)
            queuedGiro = if (g == giro) -1 else g
        } else if (g != giro) {
            forgetCleared()
            giro = g
            Engine.nativeSelectGiro(g)
            showGiroPage(0)
            save()
        }
    }

    /** Copies the giro in use onto the next one and goes there: the start of a variation. */
    fun copyGiro() {
        if (!full) return
        val to = (giro + 1) % GIRO_COUNT
        store[to].copyFrom(store[giro])
        pushGiro(to)
        save()
        selectGiro(to)
    }

    /** 1, 2 or 4 bars. New bars start as a copy of the ones before. */
    fun changeBars(n: Int) {
        if (!full || n !in BAR_CHOICES || n == store[giro].bars) return
        forgetCleared()
        store[giro].resize(n)
        pushGiro(giro)
        showGiroPage(page)
        save()
    }

    /** Shows one bar and keeps it there, to edit it while the giro plays. */
    fun showBar(n: Int) {
        if (n !in 0 until bars) return
        page = n
        followPage = false
        loadPage()
    }

    fun toggleFollow() {
        followPage = !followPage
    }

    fun toggleChain() {
        chainOn = !chainOn
        queuedGiro = -1
        grooveHint = ""
        pushChain()
        save()
    }

    fun chainCycleGiro(i: Int) {
        val st = chain.getOrNull(i) ?: return
        chain[i] = st.copy(giro = (st.giro + 1) % GIRO_COUNT)
        pushChain()
        save()
    }

    fun chainCycleReps(i: Int) {
        val st = chain.getOrNull(i) ?: return
        chain[i] = st.copy(reps = CHAIN_REPS[(CHAIN_REPS.indexOf(st.reps) + 1) % CHAIN_REPS.size])
        pushChain()
        save()
    }

    fun chainRemove(i: Int) {
        if (chain.size <= 1 || i !in chain.indices) return
        chain.removeAt(i)
        pushChain()
        save()
    }

    fun chainAdd() {
        if (chain.size >= CHAIN_MAX) return
        val last = chain.last()
        chain.add(ChainStep((last.giro + 1) % GIRO_COUNT, last.reps))
        pushChain()
        save()
    }

    fun changeBassGate(delta: Int) {
        bassGate = (bassGate + delta).coerceIn(15, 100)
        Engine.nativeBassGate(bassGate / 100f)
        save()
    }
}
