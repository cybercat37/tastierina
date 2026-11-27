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
/** One colour per row: cassa, rullante, clap, hat chiuso, hat aperto. */
private val DRUM_COLORS = listOf(Color(0xFFE2523F), Color(0xFFE89A3C), Color(0xFFD9B23A), Color(0xFF5FB7A6), Color(0xFF6E9BE0))
private val LABEL_WIDTH = 104.dp
private val CELL_GAP = 4.dp
private val BEAT_GAP = 6.dp

@Composable
fun RitmoScreen(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RitmoControls(Modifier.fillMaxWidth().height(38.dp))
        DrumGrid(Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun DChip(text: String, selected: Boolean, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(19.dp)
    Box(
        Modifier.height(38.dp).clip(shape)
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
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Lezione", color = DInk.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        DChip("Libera", s.lesson == null, "Senza lezione") { s.selectLesson(null) }
        for (l in LESSONS) DChip(l.title, s.lesson == l, "Lezione ${l.title}: la griglia si svuota") { s.selectLesson(l) }
        Spacer(Modifier.width(10.dp))
        DChip("Svuota", false, "Spegni tutte le caselle") { s.clearPattern() }
    }
}

/**
 * Five rows of sixteen steps. A tap turns a step on or off; dragging along a
 * row paints every step it crosses the same way as the first one.
 */
@Composable
private fun DrumGrid(modifier: Modifier) {
    val s = AppState
    val view = LocalView.current
    val density = LocalDensity.current
    val labelPx = with(density) { LABEL_WIDTH.toPx() }
    // Per finger: the row it started on and whether it turns steps on or off.
    val strokes = remember { HashMap<PointerId, Pair<Int, Boolean>>() }
    val state = s.lessonNow
    val working = state as? LessonState.Working
    val head = s.drumStep

    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(DWell).padding(10.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (c in event.changes) {
                            val rows = DRUM_NAMES.size
                            val track = (c.position.y / size.height * rows).toInt()
                            val x = c.position.x - labelPx
                            val step = (x / (size.width - labelPx) * DRUM_STEPS).toInt()
                            val inside = track in 0 until rows && x >= 0 && step in 0 until DRUM_STEPS
                            if (!c.pressed) {
                                strokes.remove(c.id)
                            } else if (c.previousPressed.not()) {
                                if (inside) {
                                    val on = !s.isOn(track, step)
                                    strokes[c.id] = track to on
                                    s.setStep(track, step, on)
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            } else {
                                val stroke = strokes[c.id]
                                if (stroke != null && step in 0 until DRUM_STEPS && x >= 0) s.setStep(stroke.first, step, stroke.second)
                            }
                            c.consume()
                        }
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DRUM_NAMES.forEachIndexed { t, name ->
                val focus = working?.stage?.track == t
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        color = if (focus) DGuide else DInk,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(LABEL_WIDTH),
                    )
                    Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                        for (step in 0 until DRUM_STEPS) {
                            if (step > 0 && step % 4 == 0) Spacer(Modifier.width(BEAT_GAP - CELL_GAP))
                            val on = s.isOn(t, step)
                            val want = focus && working != null && step in working.missing
                            val extra = focus && working != null && step in working.extra
                            Cell(
                                Modifier.weight(1f).fillMaxHeight(),
                                on = on,
                                color = DRUM_COLORS[t],
                                beat = step % 4 == 0,
                                playing = step == head,
                                want = want,
                                extra = extra,
                                label = "$name, casella ${step + 1}",
                            )
                        }
                    }
                }
            }
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

/** The text for the display at level 2. */
fun ritmoLcd(): String {
    val s = AppState
    return when (val st = s.lessonNow) {
        null -> if (s.drumsPlaying) "accendi e spegni mentre suona" else "accendi le caselle e premi Suona"
        is LessonState.Done -> if (s.drumsPlaying) "BRAVO! questo è un giro ${st.lesson.style}" else "giro ${st.lesson.style} pronto: premi Suona"
        is LessonState.Working -> {
            val name = DRUM_NAMES[st.stage.track].uppercase()
            val tip = if (st.missing.isEmpty()) "spegni le caselle col cerchietto" else st.stage.tip
            "${st.index + 1}/${st.total} $name · $tip"
        }
    }
}
