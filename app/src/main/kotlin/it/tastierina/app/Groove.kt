package it.tastierina.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val GInk = Color(0xFFF4F1EA)
private val GAccent = Color(0xFFE2572F)
private val GWell = Color(0xFF151617)
private val GGuide = Color(0xFF0A857A)
private val GDark = Color(0xFF1B1918)
/** Engine rows: 0 bass (amber), 1 melody (blue). */
private val ROW_COLORS = listOf(Color(0xFFF0B429), Color(0xFF7CC0FF))
private val NOTE_LABEL = 104.dp

/**
 * Levels 3 and 4. The giro has two views: "Batteria" is the drum grid of
 * level 2, "Note" shows the melody and bass rows with the keys underneath.
 * Level 4 adds "Brano": giri A B C, bars, the scaletta.
 */
@Composable
fun GrooveScreen(modifier: Modifier) {
    val s = AppState
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GrooveControls(Modifier.fillMaxWidth().height(44.dp))
        when {
            s.giroView -> BranoPanel(Modifier.fillMaxWidth().weight(1f))
            s.noteView -> {
                NoteGrid(Modifier.fillMaxWidth().height(104.dp))
                Keyboard(Modifier.fillMaxWidth().weight(1f), next = s.bassNeed, after = -1, middleC = -1)
            }
            else -> DrumGrid(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun ClearChip() {
    val s = AppState
    if (s.canUndoClear) {
        DChip("Annulla", true, "Rimetti le caselle che avevi") { s.undoClear() }
    } else {
        DChip("Svuota", false, "Svuota il giro in uso: batteria, basso e melodia") { s.clearByUser() }
    }
}

@Composable
private fun GrooveControls(modifier: Modifier) {
    val s = AppState
    val full = s.level == Level.GROOVEBOX
    var showLessons by remember { mutableStateOf(false) }
    var showSounds by remember { mutableStateOf(false) }
    if (showLessons) LessonsDialog { showLessons = false }
    if (showSounds) SoundsDialog { showSounds = false }
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DChip("Batteria", !s.noteView && !s.giroView, "Vista batteria") { s.showNotes(false) }
        DChip("Note", s.noteView, "Vista note: melodia e basso con i tasti") { s.showNotes(true) }
        if (full) DChip("Brano", s.giroView, "Vista brano: giri, battute, scaletta") { s.showGiro() }
        Spacer(Modifier.width(6.dp))
        if (full && !s.giroView) {
            // Which giro and which bar the grid shows; a tap moves to the next.
            val next = if (s.queuedGiro >= 0) " → ${GIRO_NAMES[s.queuedGiro]}" else ""
            DChip("Giro ${GIRO_NAMES[s.giro]}$next", false, "Giro in uso: tocca per passare al successivo") {
                s.selectGiro((s.giro + 1) % GIRO_COUNT)
            }
            if (s.bars > 1) {
                DChip("batt. ${s.page + 1}/${s.bars}", false, "Battuta mostrata: tocca per la successiva") {
                    s.showBar((s.page + 1) % s.bars)
                }
            }
        }
        when {
            s.giroView -> {}
            s.noteView -> {
                DChip("● Registra", s.recArmed, if (s.recArmed) "Smetti di registrare" else "Registra dai tasti nella riga scelta") { s.toggleRec() }
                DChip("Basso", s.recTarget == 0, "I tasti suonano e scrivono il basso") { s.selectTarget(0) }
                DChip("Melodia", s.recTarget == 1, "I tasti suonano e scrivono la melodia") { s.selectTarget(1) }
                if (!full) FilterStepper()
            }
            else -> {
                DChip("Lezione: ${s.lesson?.title ?: "libera"}  ▾", s.lesson != null, "Scegli la lezione") { showLessons = true }
                DChip("Suoni (${s.rows.size})  ▾", false, "Scegli i suoni della griglia") { showSounds = true }
            }
        }
        // At level 4 "Svuota" lives in the Brano view, next to the giro it empties.
        if (!full) ClearChip()
    }
}

@Composable
private fun FilterStepper() {
    val s = AppState
    Stepper(
        "filtro ${s.bassCut}",
        "Basso più scuro",
        "Basso più brillante",
        { s.changeBassCut(-1) },
        { s.changeBassCut(1) },
        valueWidth = 70.dp,
    )
}

/** Level 4: the song. Which giro, how long it is, in what order the giri play. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranoPanel(modifier: Modifier) {
    val s = AppState
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(GWell).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Giro in uso") {
            for (g in 0 until GIRO_COUNT) {
                val waiting = if (s.queuedGiro == g) " (a fine giro)" else ""
                DChip(GIRO_NAMES[g] + waiting, s.giro == g, "Giro ${GIRO_NAMES[g]}") { s.selectGiro(g) }
            }
            val to = GIRO_NAMES[(s.giro + 1) % GIRO_COUNT]
            DChip("Copia in $to", false, "Copia questo giro nel giro $to e vai lì") { s.copyGiro() }
            ClearChip()
        }
        Section("Battute del giro ${GIRO_NAMES[s.giro]}") {
            for (n in BAR_CHOICES) DChip("$n", s.bars == n, if (n == 1) "1 battuta" else "$n battute") { s.changeBars(n) }
            if (s.bars > 1) {
                Caption("mostra")
                for (b in 0 until s.bars) DChip("${b + 1}", s.page == b, "Mostra la battuta ${b + 1}") { s.showBar(b) }
                DChip("Segui", s.followPage, "La griglia segue la battuta che suona") { s.toggleFollow() }
            }
        }
        Section("Scaletta") {
            DChip(if (s.chainOn) "Accesa" else "Spenta", s.chainOn, "Scaletta: i giri cambiano da soli") { s.toggleChain() }
            s.chain.forEachIndexed { i, step ->
                if (i > 0) Caption("→")
                val live = s.chainOn && s.drumsPlaying && s.chainPos == i
                ChainSlot(i, step, live, s.chainRep, canRemove = s.chain.size > 1)
            }
            if (s.chain.size < CHAIN_MAX) DChip("+", false, "Aggiungi un passo alla scaletta") { s.chainAdd() }
        }
        Section("Basso") {
            FilterStepper()
            Stepper(
                "durata ${s.bassGate}%",
                "Note del basso più corte",
                "Note del basso più lunghe",
                { s.changeBassGate(-5) },
                { s.changeBassGate(5) },
                valueWidth = 104.dp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title.uppercase(),
            color = GInk.copy(alpha = 0.8f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.3.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/** A word or arrow between chips, centred on their height. */
@Composable
private fun Caption(text: String) {
    Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
        Text(text, color = GInk.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One step of the scaletta: the giro, how many times, and a way to remove it. Lit while it plays. */
@Composable
private fun ChainSlot(index: Int, step: ChainStep, live: Boolean, rep: Int, canRemove: Boolean) {
    val s = AppState
    val shape = RoundedCornerShape(22.dp)
    val giro = GIRO_NAMES[step.giro]
    Row(
        Modifier.height(44.dp).clip(shape)
            .then(if (live) Modifier.background(GAccent) else Modifier.border(1.5.dp, GInk.copy(alpha = 0.45f), shape)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlotButton(giro, "Passo ${index + 1}: giro $giro. Tocca per cambiare giro", 46.dp) { s.chainCycleGiro(index) }
        SlotButton(
            if (live) "${rep + 1}/${step.reps}" else "×${step.reps}",
            "Passo ${index + 1}: ${step.reps} volte. Tocca per cambiare",
            50.dp,
        ) { s.chainCycleReps(index) }
        if (canRemove) SlotButton("✕", "Togli il passo ${index + 1}", 40.dp) { s.chainRemove(index) }
    }
}

@Composable
private fun SlotButton(text: String, label: String, width: Dp, onClick: () -> Unit) {
    Box(
        Modifier.width(width).fillMaxHeight()
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(text, color = GInk, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

/**
 * Melody on top, bass under it: sixteen cells each, showing the note they hold.
 * A tap puts the chosen note in a cell or empties it; dragging along a row does
 * the same to every cell crossed. A tap on a row's name makes it the keys' row.
 */
@Composable
private fun NoteGrid(modifier: Modifier) {
    val s = AppState
    val view = LocalView.current
    val labelPx = with(LocalDensity.current) { NOTE_LABEL.toPx() }
    // Per finger: the row it started on and whether it fills or empties.
    val strokes = remember { HashMap<PointerId, Pair<Int, Boolean>>() }
    val lesson = s.lessonNow as? LessonState.Bass
    val head = s.drumStep

    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(GWell).padding(8.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (c in event.changes) {
                            val line = if (c.position.y < size.height / 2f) 0 else 1
                            val row = if (line == 0) 1 else 0 // the melody is drawn first
                            val x = c.position.x - labelPx
                            val step = (x / (size.width - labelPx) * DRUM_STEPS).toInt()
                            val onCell = x >= 0 && step in 0 until DRUM_STEPS
                            if (!c.pressed) {
                                strokes.remove(c.id)
                            } else if (!c.previousPressed) {
                                if (onCell) {
                                    val fill = s.noteAt(row, step) < 0
                                    strokes[c.id] = row to fill
                                    if (s.setNote(row, step, fill)) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                } else {
                                    s.selectTarget(row)
                                }
                            } else {
                                val stroke = strokes[c.id]
                                if (stroke != null && onCell) s.setNote(stroke.first, step, stroke.second)
                            }
                            c.consume()
                        }
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (row in listOf(1, 0)) {
                val guided = lesson != null && row == 0
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.width(NOTE_LABEL),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // The dot marks the row the keys play and write to.
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .then(
                                    if (s.recTarget == row) Modifier.background(GAccent)
                                    else Modifier.border(1.5.dp, GInk.copy(alpha = 0.5f), CircleShape),
                                ),
                        )
                        Text(
                            s.rowNames[row],
                            color = if (s.recTarget == row) GInk else GInk.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                    Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (step in 0 until DRUM_STEPS) {
                            if (step > 0 && step % 4 == 0) Spacer(Modifier.width(2.dp))
                            val note = s.noteAt(row, step)
                            val wanted = if (guided && lesson != null && step in lesson.missing) lesson.want[step] else null
                            NoteCell(
                                Modifier.weight(1f).fillMaxHeight(),
                                note = note,
                                color = ROW_COLORS[row],
                                beat = step % 4 == 0,
                                playing = step == head,
                                wanted = wanted,
                                extra = guided && lesson != null && step in lesson.extra,
                                label = "${s.rowNames[row]}, casella ${step + 1}",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCell(
    modifier: Modifier,
    note: Int,
    color: Color,
    beat: Boolean,
    playing: Boolean,
    wanted: Int?,
    extra: Boolean,
    label: String,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.clip(shape)
            .background(
                when {
                    note >= 0 -> color
                    beat -> Color(0xFFEAE4D7)
                    else -> Color(0xFFCDC7BA)
                },
            )
            .then(
                when {
                    playing -> Modifier.border(3.dp, GInk, shape)
                    wanted != null -> Modifier.border(3.dp, GGuide, shape)
                    else -> Modifier
                },
            )
            .semantics { contentDescription = label + if (note >= 0) ", ${keyName(note)}" else "" },
        contentAlignment = Alignment.Center,
    ) {
        when {
            note >= 0 -> Text(keyName(note), color = GDark, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            wanted != null -> Text(keyName(wanted), color = GGuide, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
        if (extra) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(9.dp).clip(CircleShape)
                    .border(2.dp, GDark, CircleShape),
            )
        }
    }
}
