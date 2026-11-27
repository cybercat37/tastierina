package it.tastierina.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.verticalScroll
import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DInk = Color(0xFFF4F1EA)
private val DAccent = Color(0xFFE2572F)
private val DWell = Color(0xFF151617)
private val DGuide = Color(0xFF62E3D2)
private val DMono = FontFamily.Monospace
/** One colour per sound id: skins warm, metals cool, hand percussion earthy. */
private val DRUM_COLORS = listOf(
    Color(0xFFE2523F), Color(0xFFE89A3C), Color(0xFFD9B23A), Color(0xFF5FB7A6),
    Color(0xFF6E9BE0), Color(0xFFC0563B), Color(0xFFA07BD6), Color(0xFFD9774A),
    Color(0xFFB8875A), Color(0xFF8FBF6A), Color(0xFF7FA7C9), Color(0xFF9DB4E8),
    Color(0xFFB0703F), Color(0xFFCFA45E), Color(0xFF8BC9B5), Color(0xFFB03A2E),
)
private val LABEL_WIDTH = 104.dp
private val CELL_GAP = 4.dp
private val BEAT_GAP = 6.dp

@Composable
fun RitmoScreen(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RitmoControls(Modifier.fillMaxWidth().height(44.dp))
        DrumGrid(Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
internal fun DChip(text: String, selected: Boolean, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.height(44.dp).clip(shape)
            .then(if (selected) Modifier.background(DAccent) else Modifier.border(1.5.dp, DInk.copy(alpha = 0.45f), shape))
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = DInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun RitmoControls(modifier: Modifier) {
    val s = AppState
    var showLessons by remember { mutableStateOf(false) }
    var showSounds by remember { mutableStateOf(false) }
    if (showLessons) LessonsDialog { showLessons = false }
    if (showSounds) SoundsDialog { showSounds = false }
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DChip("Lezione: ${s.lesson?.title ?: "libera"}  ▾", s.lesson != null, "Scegli la lezione") { showLessons = true }
        DChip("Suoni (${s.rows.size})  ▾", false, "Scegli i suoni della griglia") { showSounds = true }
        Spacer(Modifier.width(10.dp))
        if (s.canUndoClear) {
            DChip("Annulla", true, "Rimetti le caselle che avevi") { s.undoClear() }
        } else {
            DChip("Svuota", false, "Spegni tutte le caselle") { s.clearByUser() }
        }
    }
}

private val MIN_ROW = 34.dp
private val ROW_GAP = 5.dp
private const val UNDECIDED = 0
private const val PAINT = 1
private const val SCROLL = 2

/** One finger on the grid, from touch to lift. */
private class Stroke(val track: Int, val step: Int, val on: Boolean, val x0: Float, val y0: Float) {
    var mode = UNDECIDED
    var lastY = y0
}

/** Row height in pixels: rows share the height, but never go under the minimum (then the grid scrolls). */
private fun rowPixels(height: Int, rows: Int, minRow: Int, gap: Int): Int =
    maxOf(minRow, (height - gap * (rows - 1)) / rows.coerceAtLeast(1))

/**
 * A row of sixteen steps per sound. A tap turns a step on or off; a sideways
 * drag paints every step it crosses the same way as the first; an up-and-down
 * drag scrolls the rows. A tap on a row's name plays its sound.
 */
@Composable
internal fun DrumGrid(modifier: Modifier) {
    val s = AppState
    val view = LocalView.current
    val density = LocalDensity.current
    val labelPx = with(density) { LABEL_WIDTH.toPx() }
    val gapPx = with(density) { ROW_GAP.roundToPx() }
    val minRowPx = with(density) { MIN_ROW.roundToPx() }
    val scroll = rememberScrollState()
    val strokes = remember { HashMap<PointerId, Stroke>() }
    val working = s.lessonNow as? LessonState.Working
    val head = s.drumStep

    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(12.dp)).background(DWell).padding(10.dp)
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (c in event.changes) {
                            val rowPx = rowPixels(size.height, s.rows.size, minRowPx, gapPx)
                            val row = ((c.position.y + scroll.value) / (rowPx + gapPx)).toInt()
                            val track = s.rows.getOrElse(row) { -1 }
                            val x = c.position.x - labelPx
                            val step = (x / (size.width - labelPx) * DRUM_STEPS).toInt()
                            val onCell = x >= 0 && step in 0 until DRUM_STEPS
                            val stroke = strokes[c.id]
                            if (!c.pressed) {
                                // Lifted without dragging: a tap.
                                if (stroke != null && stroke.mode == UNDECIDED) {
                                    if (stroke.step >= 0) {
                                        s.setStep(stroke.track, stroke.step, stroke.on)
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    } else {
                                        s.previewDrum(stroke.track)
                                    }
                                }
                                strokes.remove(c.id)
                            } else if (!c.previousPressed) {
                                if (track >= 0 && c.position.y >= 0) {
                                    strokes[c.id] = Stroke(
                                        track, if (onCell) step else -1, onCell && !s.isOn(track, step), c.position.x, c.position.y,
                                    )
                                }
                            } else if (stroke != null) {
                                if (stroke.mode == UNDECIDED) {
                                    val dx = abs(c.position.x - stroke.x0)
                                    val dy = abs(c.position.y - stroke.y0)
                                    if (dy > slop && dy >= dx) {
                                        stroke.mode = SCROLL
                                    } else if (dx > slop) {
                                        // Sideways on the cells paints; on the names there is nothing to paint.
                                        stroke.mode = if (stroke.step >= 0) PAINT else SCROLL
                                        if (stroke.mode == PAINT) {
                                            s.setStep(stroke.track, stroke.step, stroke.on)
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        }
                                    }
                                }
                                if (stroke.mode == PAINT && onCell) s.setStep(stroke.track, step, stroke.on)
                                if (stroke.mode == SCROLL) scroll.dispatchRawDelta(stroke.lastY - c.position.y)
                                stroke.lastY = c.position.y
                            }
                            c.consume()
                        }
                    }
                }
            },
    ) {
        val viewport = constraints.maxHeight
        val count = s.rows.size
        val rowPx = rowPixels(viewport, count, minRowPx, gapPx)
        val rowDp = with(density) { rowPx.toDp() }
        val gapDp = with(density) { gapPx.toDp() }

        // A lesson that moves to a row out of sight brings it into view.
        val focusTrack = working?.stage?.track
        LaunchedEffect(focusTrack, count, viewport) {
            val index = if (focusTrack != null) s.rows.indexOf(focusTrack) else -1
            if (index >= 0) {
                val top = index * (rowPx + gapPx)
                if (top < scroll.value || top + rowPx > scroll.value + viewport) {
                    scroll.animateScrollTo((top - (viewport - rowPx) / 2).coerceAtLeast(0))
                }
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(scroll, enabled = false),
            verticalArrangement = Arrangement.spacedBy(gapDp),
        ) {
            for (t in s.rows) {
                val name = DRUM_SOUNDS[t]
                val focus = focusTrack == t
                Row(Modifier.fillMaxWidth().height(rowDp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        color = if (focus) DGuide else DInk,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.width(LABEL_WIDTH),
                    )
                    Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                        for (step in 0 until DRUM_STEPS) {
                            if (step > 0 && step % 4 == 0) Spacer(Modifier.width(BEAT_GAP - CELL_GAP))
                            Cell(
                                Modifier.weight(1f).fillMaxHeight(),
                                on = s.isOn(t, step),
                                color = DRUM_COLORS[t],
                                beat = step % 4 == 0,
                                playing = step == head,
                                want = focus && working != null && step in working.missing,
                                extra = focus && working != null && step in working.extra,
                                label = "$name, casella ${step + 1}",
                            )
                        }
                    }
                }
            }
        }

        // A thin bar on the right shows there is more above or below.
        val max = scroll.maxValue
        if (max in 1 until Int.MAX_VALUE) {
            val thumb = viewport.toFloat() * viewport / (viewport + max)
            val top = (viewport - thumb) * scroll.value / max
            Box(
                Modifier.align(Alignment.TopEnd)
                    .offset(x = 7.dp, y = with(density) { top.toDp() })
                    .width(4.dp)
                    .height(with(density) { thumb.toDp() })
                    .clip(CircleShape)
                    .background(DInk.copy(alpha = 0.6f)),
            )
        }
    }
}

@Composable
private fun Cell(
    modifier: Modifier,
    on: Boolean,
    color: Color,
    beat: Boolean,
    playing: Boolean,
    want: Boolean,
    extra: Boolean,
    label: String,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.clip(shape)
            .background(
                when {
                    on && playing -> color.copy(alpha = 1f)
                    on -> color.copy(alpha = 0.85f)
                    beat -> Color(0xFFEAE4D7)
                    else -> Color(0xFFCDC7BA)
                },
            )
            .then(if (playing) Modifier.border(3.dp, DInk, shape) else Modifier)
            .semantics { contentDescription = label + if (on) ", accesa" else "" },
        contentAlignment = Alignment.Center,
    ) {
        when {
            want -> Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF0A857A)))
            extra -> Box(Modifier.size(12.dp).clip(CircleShape).border(2.5.dp, Color(0xFF1B1918), CircleShape))
        }
    }
}

/** The text for the display at levels 2 and 3. */
fun ritmoLcd(): String {
    val s = AppState
    val groove = s.level == Level.GROOVE
    if (groove && s.grooveHint.isNotEmpty()) return s.grooveHint
    return when (val st = s.lessonNow) {
        null -> when {
            s.recArmed -> "REC ${s.rowNames[s.recTarget].uppercase()} · suona i tasti a tempo"
            groove && s.noteView && s.chosenKey < 0 -> "suona un tasto per scegliere la nota, poi tocca le caselle"
            groove && s.noteView -> "nota scelta: ${keyName(s.chosenKey)} · tocca le caselle"
            s.drumsPlaying -> "accendi e spegni mentre suona"
            else -> "accendi le caselle e premi Suona"
        }
        is LessonState.Done -> if (s.drumsPlaying) "BRAVO! questo è un giro ${st.lesson.style}" else "giro ${st.lesson.style} pronto: premi Suona"
        is LessonState.Working -> {
            val name = DRUM_SOUNDS[st.stage.track].uppercase()
            val tip = when {
                groove && s.noteView -> "torna alla vista Batteria"
                st.missing.isEmpty() -> "spegni le caselle col cerchietto"
                else -> st.stage.tip
            }
            "${st.index + 1}/${st.total} $name · $tip"
        }
        is LessonState.Bass -> {
            val wanted = st.missing.minOrNull()?.let { st.want[it] }
            val tip = when {
                !s.noteView -> "passa alla vista Note"
                st.extra.isNotEmpty() -> "spegni le caselle col cerchietto"
                s.recTarget != 0 -> "scegli la riga Basso"
                s.recArmed -> "suona le note a tempo sulle caselle verdi"
                wanted == null -> "spegni le caselle col cerchietto"
                s.chosenKey < 0 || s.chosenKey % 12 != wanted % 12 -> "scegli la nota: suona ${keyName(wanted)} sui tasti"
                else -> "ora tocca le caselle con scritto ${keyName(wanted)}"
            }
            "${st.index + 1}/${st.total} BASSO · $tip"
        }
    }
}

@Composable
private fun LightChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.height(44.dp).clip(shape)
            .then(if (selected) Modifier.background(DAccent) else Modifier.background(Color.White).border(1.5.dp, Color(0xFFB9B2A4), shape))
            .clickable(enabled = enabled, onClickLabel = text, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when {
                selected -> Color.White
                enabled -> Color(0xFF17181A)
                else -> Color(0xFF17181A).copy(alpha = 0.4f)
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The patterns to follow, by family; ✓ marks the ones finished once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LessonsDialog(onClose: () -> Unit) {
    val s = AppState
    val pick = { l: Lesson? ->
        s.selectLesson(l)
        onClose()
    }
    Sheet(onClose, 760.dp, "Scegli la lezione") {
        Text("Scegliendo una lezione la griglia si svuota e compaiono i suoni che servono.", color = Color(0xFF3F464B), fontSize = 15.sp)
        LightChip("Libera, senza lezione", s.lesson == null) { pick(null) }
        for (group in LESSON_GROUPS) {
            Text(group, color = Color(0xFF17181A), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in LESSONS.filter { it.group == group }) {
                    val done = if (l.id in s.lessonsDone) "  ✓" else ""
                    LightChip("${l.title} · ${l.bpm}$done", s.lesson == l) { pick(l) }
                }
            }
        }
    }
}

/** Which sounds are rows of the grid: all of them, the basic five, or a choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SoundsDialog(onClose: () -> Unit) {
    val s = AppState
    Sheet(onClose, 760.dp, "Suoni della griglia") {
        Text(
            "Tocca un suono per aggiungerlo o toglierlo. La griglia scorre in verticale quando le righe non ci stanno tutte. Togliendo una riga si spengono le sue caselle.",
            color = Color(0xFF3F464B), fontSize = 15.sp, lineHeight = 21.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LightChip("Tutti i suoni", s.rows.size == DRUM_SOUNDS.size) { s.showAllRows() }
            LightChip("Solo i suoni base", false) { s.showBaseRows() }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DRUM_SOUNDS.forEachIndexed { id, name ->
                val on = id in s.rows
                LightChip(name, on, enabled = on || s.rows.size < MAX_ROWS) { s.toggleRow(id) }
            }
        }
    }
}
