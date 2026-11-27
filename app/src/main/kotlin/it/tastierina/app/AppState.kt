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
    val timbreNames = listOf("Piano", "Piano el.", "Organo", "Archi", "Flauto", "Chitarra", "Marimba", "Synth", "Campana")
    /** Engine index of each name above. */
    val timbreIds = listOf(0, 8, 1, 4, 5, 6, 7, 2, 3)
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
    /** One bitmask per drum sound (by engine id): bit s is step s. */
    val pattern = mutableStateListOf<Int>().apply { repeat(DRUM_SOUNDS.size) { add(0) } }
    /** Sounds shown as rows, in id order, at most [MAX_ROWS]. */
    val rows = mutableStateListOf<Int>().apply { addAll(DEFAULT_ROWS) }
    /** Lessons finished at least once. */
    val lessonsDone = mutableStateListOf<String>()
    var drumsPlaying by mutableStateOf(false)
        private set
    var drumBpm by mutableIntStateOf(124)
        private set
    var lesson by mutableStateOf<Lesson?>(LESSONS.first())
        private set
    val lessonNow: LessonState? get() = lesson?.let { lessonState(it, pattern) }
    /** Step under the playhead, or -1. */
    val drumStep: Int
        get() {
            val pos = beatPosition
            return if (!drumsPlaying || pos.isNaN() || pos < 0) -1 else Math.floorMod(kotlin.math.floor(pos * 4).toLong(), 16L).toInt()
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
        p.getString("pattern", null)?.split(',')?.forEachIndexed { i, v ->
            if (i < pattern.size) pattern[i] = v.toIntOrNull() ?: 0
        }
        p.getString("rows", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?.filter { it in DRUM_SOUNDS.indices }?.distinct()?.take(MAX_ROWS)
            ?.takeIf { it.isNotEmpty() }?.let { rows.clear(); rows.addAll(it.sorted()) }
        p.getString("lessonsDone", "")?.split(',')?.filter { it.isNotBlank() }?.let { lessonsDone.addAll(it) }
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
            ?.putString("pattern", pattern.joinToString(","))
            ?.putString("rows", rows.joinToString(","))
            ?.putString("lessonsDone", lessonsDone.joinToString(","))
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
        if (level == Level.RITMO) stopDrums()
        if (tempo) setTempoMode(false)
        level = l
        if (l == Level.RIPETI) startRipeti()
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
        if (held[key] == 1) {
            Engine.nativeNoteOn(midiOf(key), timbre, 0.9f)
            when {
                takeRunning -> takeHit(key, touchNanos)
                playMode == PlayMode.IMPARA -> followSong(key)
                else -> {} // A tempo, before Via!: free playing
            }
        }
    }

    fun keyUp(key: Int) {
        if (key !in 0 until KEY_COUNT || held[key] == 0) return
        held[key] = held[key] - 1
        if (held[key] == 0) Engine.nativeNoteOff(midiOf(key))
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

    private fun pushPattern() {
        pattern.forEachIndexed { t, row -> Engine.nativeSetPattern(t, row) }
    }

    fun isOn(track: Int, step: Int) = pattern[track] and (1 shl step) != 0

    fun setStep(track: Int, step: Int, on: Boolean) {
        if (track !in pattern.indices || step !in 0 until DRUM_STEPS || isOn(track, step) == on) return
        forgetCleared()
        pattern[track] = if (on) pattern[track] or (1 shl step) else pattern[track] and (1 shl step).inv()
        Engine.nativeSetStep(track, step, on)
        if (on && !drumsPlaying) Engine.nativeDrum(track)
        val st = lessonNow
        if (st is LessonState.Done && st.lesson.id !in lessonsDone) lessonsDone.add(st.lesson.id)
        save()
    }

    /** Shows or hides a sound's row. Hiding clears its steps, so nothing plays unseen. */
    fun toggleRow(id: Int) {
        if (id !in DRUM_SOUNDS.indices) return
        forgetCleared()
        if (id in rows) {
            if (rows.size <= 1) return
            rows.remove(id)
            pattern[id] = 0
            Engine.nativeSetPattern(id, 0)
        } else {
            if (rows.size >= MAX_ROWS) return
            rows.add(id)
            rows.sort()
            Engine.nativeDrum(id)
        }
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

    fun clearPattern() {
        for (i in pattern.indices) pattern[i] = 0
        pushPattern()
        save()
    }

    /** What "Svuota" removed, until the next change: "Annulla" brings it back. */
    private var cleared: List<Int>? = null
    var canUndoClear by mutableStateOf(false)
        private set

    private fun forgetCleared() {
        cleared = null
        canUndoClear = false
    }

    fun clearByUser() {
        if (pattern.all { it == 0 }) return
        cleared = pattern.toList()
        canUndoClear = true
        clearPattern()
    }

    fun undoClear() {
        val before = cleared ?: return
        for (i in pattern.indices) pattern[i] = before.getOrElse(i) { 0 }
        forgetCleared()
        pushPattern()
        save()
    }

    fun previewDrum(id: Int) {
        if (id in DRUM_SOUNDS.indices) Engine.nativeDrum(id)
    }

    /** A lesson starts from an empty grid at its own tempo. */
    fun selectLesson(l: Lesson?) {
        lesson = l
        forgetCleared()
        if (l != null) {
            showRowsFor(l)
            clearPattern()
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
}
