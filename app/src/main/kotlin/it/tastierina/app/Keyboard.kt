package it.tastierina.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KIvory = Color(0xFFF7F2E7)
private val KEbony = Color(0xFF1B1918)
private val KWell = Color(0xFF151617)
private val KAccent = Color(0xFFE2572F)
private val KGuide = Color(0xFF0A857A)
private val KGuideOnBlack = Color(0xFF62E3D2)
private val KInk = Color(0xFFF4F1EA)
private val KMono = FontFamily.Monospace
private val KGood = Color(0xFF7FD3A6)
private val KNear = Color(0xFFF0B429)
private val KMiss = Color(0xFFFF8A75)

private const val WHITE_COUNT = 11
private const val BLACK_HEIGHT = 0.62f
private const val BLACK_WIDTH = 0.6f // of a white key

/** For each key: its white-key slot (whites) or the boundary it sits on (blacks). */
private val whiteSlot: List<Int> = run {
    var whites = 0
    List(KEY_COUNT) { k -> if (isBlack(k)) whites else whites++ }
}

/** Which key is under a point of the keyboard: black keys first, they sit on top. */
private fun keyAt(p: Offset, size: IntSize): Int? {
    if (p.x < 0 || p.y < 0 || p.x >= size.width || p.y >= size.height) return null
    val w = size.width / WHITE_COUNT.toFloat()
    if (p.y < size.height * BLACK_HEIGHT) {
        for (k in 0 until KEY_COUNT) {
            if (!isBlack(k)) continue
            val center = whiteSlot[k] * w
            if (kotlin.math.abs(p.x - center) <= w * BLACK_WIDTH / 2) return k
        }
    }
    val slot = (p.x / w).toInt().coerceIn(0, WHITE_COUNT - 1)
    return (0 until KEY_COUNT).first { !isBlack(it) && whiteSlot[it] == slot }
}

@Composable
fun TastiScreen(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TastiControls(Modifier.fillMaxWidth().height(38.dp))
        SongStrip(Modifier.fillMaxWidth().height(40.dp))
        Keyboard(Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(19.dp)
    Box(
        Modifier.height(38.dp).clip(shape)
            .then(if (selected) Modifier.background(KAccent) else Modifier.border(1.5.dp, KInk.copy(alpha = 0.45f), shape))
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = KInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun TastiControls(modifier: Modifier) {
    val s = AppState
    var showSongs by remember { mutableStateOf(false) }
    if (showSongs) SongsDialog { showSongs = false }
    s.result?.let { ResultDialog(it) }
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip("♪  ${s.song?.title ?: "Libera"}  ▾", s.song != null, "Scegli la canzone") { showSongs = true }
        if (s.song != null) {
            for (m in PlayMode.entries) Chip(m.title, s.playMode == m, "Modo: ${m.title}") { s.selectPlayMode(m) }
            if (s.playMode == PlayMode.A_TEMPO) {
                Chip(if (s.slow) "lenta" else "normale", false, "Velocità: tocca per cambiare") { s.toggleSlow() }
            }
        }
        Spacer(Modifier.width(10.dp))
        s.timbreNames.forEachIndexed { i, name ->
            val id = s.timbreIds[i]
            Chip(name, s.timbre == id, "Suono: $name") { s.selectTimbre(id) }
        }
        Spacer(Modifier.width(10.dp))
        Chip("−", false, "Ottava più bassa") { s.changeOctave(-1) }
        Text(
            "ottava ${if (s.octave > 0) "+" else ""}${s.octave}",
            color = KInk, fontFamily = KMono, fontSize = 15.sp,
        )
        Chip("+", false, "Ottava più alta") { s.changeOctave(1) }
    }
}

/** The song as a row of note names: the one to play now, and what comes next. */
@Composable
private fun SongStrip(modifier: Modifier) {
    val s = AppState
    val sg = s.song
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(KWell).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (sg == null) {
            Text("Suona libera: più dita insieme fanno un accordo, trascinare fa un glissando.", color = KInk.copy(alpha = 0.8f), fontSize = 14.sp)
            return@Row
        }
        val from = (s.songStep - 2).coerceAtLeast(0)
        val to = (from + 12).coerceAtMost(sg.notes.size)
        for (i in from until to) {
            val now = i == s.songStep && !s.songDone
            val mark = s.marks.getOrElse(i) { 0 }
            val done = mark != 0
            // Impara: 1 right, 2 right after errors. A tempo: Mark ordinals.
            val markColor = when {
                s.playMode == PlayMode.IMPARA && mark == 1 -> KGood
                s.playMode == PlayMode.IMPARA && mark == 2 -> KNear
                mark == Mark.ON_TIME.ordinal -> KGood
                mark == Mark.EARLY.ordinal || mark == Mark.LATE.ordinal -> KNear
                mark == Mark.MISSED.ordinal -> KMiss
                else -> null
            }
            Box(
                Modifier.height(28.dp).clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            now -> KGuide
                            markColor != null -> markColor.copy(alpha = 0.28f)
                            else -> Color(0xFF2C2E31)
                        },
                    )
                    .then(if (markColor != null) Modifier.border(1.5.dp, markColor, RoundedCornerShape(6.dp)) else Modifier)
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    keyName(sg.notes[i].key),
                    color = if (done) KInk.copy(alpha = 0.85f) else KInk,
                    fontSize = 15.sp,
                    fontWeight = if (now) FontWeight.ExtraBold else FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text("${s.songStep + 1}/${sg.notes.size}", color = KInk.copy(alpha = 0.7f), fontFamily = KMono, fontSize = 14.sp)
    }
}

@Composable
private fun Keyboard(modifier: Modifier) {
    val s = AppState
    val view = LocalView.current
    val fingers = remember { HashMap<PointerId, Int>() }
    val sg = s.song
    val demo = s.demoStarts != null
    val next = if (sg != null && !s.songDone) sg.notes[s.songStep].key else -1
    val after = if (sg != null && !demo && !s.takeRunning && s.songStep + 1 < sg.notes.size) sg.notes[s.songStep + 1].key.takeIf { it != next } ?: -1 else -1

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(KEbony)
            .padding(start = 6.dp, end = 6.dp, bottom = 8.dp)
            // Every finger is tracked: press, slide to the next key (glissando), lift.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (c in event.changes) {
                            val now = if (c.pressed) keyAt(c.position, size) else null
                            val before = fingers[c.id]
                            if (now != before) {
                                before?.let { s.keyUp(it) }
                                if (now != null) {
                                    s.keyDown(now, c.uptimeMillis * 1_000_000L)
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    fingers[c.id] = now
                                } else {
                                    fingers.remove(c.id)
                                }
                            }
                            c.consume()
                        }
                    }
                }
            },
    ) {
        val w = maxWidth / WHITE_COUNT
        val h = maxHeight
        // White keys.
        Row(Modifier.fillMaxSize()) {
            for (k in 0 until KEY_COUNT) {
                if (isBlack(k)) continue
                val down = s.held[k] > 0 || s.demoKey == k
                Box(
                    Modifier.width(w).fillMaxHeight().padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .background(if (down) Color(0xFFF3C7BB) else KIvory)
                        .semantics { contentDescription = keyName(k) },
                ) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GuideDot(k == next, k == after, KGuide, 22)
                        if (k == -12 * s.octave) {
                            // middle C (MIDI 60)
                            Box(Modifier.size(6.dp).clip(CircleShape).background(KAccent))
                        }
                        Text(keyName(k), color = Color(0xFF4E483E), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        // Black keys, on the boundaries between whites.
        for (k in 0 until KEY_COUNT) {
            if (!isBlack(k)) continue
            val down = s.held[k] > 0 || s.demoKey == k
            Box(
                Modifier
                    .offset(x = w * whiteSlot[k] - w * (BLACK_WIDTH / 2))
                    .width(w * BLACK_WIDTH)
                    .height(h * BLACK_HEIGHT)
                    .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                    .background(if (down) Color(0xFF6A2219) else Color(0xFF26231F))
                    .semantics { contentDescription = keyName(k) },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(Modifier.padding(bottom = 10.dp)) { GuideDot(k == next, k == after, KGuideOnBlack, 16) }
            }
        }
    }
}

/** The song's guide: a filled dot on the key to play, a ring on the one after. */
@Composable
private fun GuideDot(now: Boolean, after: Boolean, color: Color, sizeDp: Int) {
    val m = Modifier.size(sizeDp.dp).clip(CircleShape)
    when {
        now -> Box(m.background(color))
        after -> Box(m.border(3.dp, color, CircleShape))
        else -> Box(Modifier.size(sizeDp.dp))
    }
}

/** Every song, grouped by difficulty; "Libera" plays without a guide. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongsDialog(onClose: () -> Unit) {
    val s = AppState
    val pick = { sg: Song? ->
        s.selectSong(sg)
        onClose()
    }
    Sheet(onClose, 760.dp, "Scegli la canzone") {
        SheetChip("Libera, senza canzone", s.song == null) { pick(null) }
        SONG_LEVELS.forEachIndexed { i, levelName ->
            Text(levelName, color = Color(0xFF17181A), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sg in SONGS.filter { it.level == i + 1 }) {
                    val best = PlayMode.entries.maxOf { s.bestStars(sg, it) }
                    val stars = if (best >= 0) "  " + "★".repeat(best) + "☆".repeat(3 - best) else ""
                    SheetChip(sg.title + stars, s.song == sg) { pick(sg) }
                }
            }
        }
    }
}

@Composable
private fun SheetChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.height(44.dp).clip(shape)
            .then(if (selected) Modifier.background(KAccent) else Modifier.background(Color.White).border(1.5.dp, Color(0xFFB9B2A4), shape))
            .clickable(onClickLabel = text, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color.White else Color(0xFF17181A), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** How the song went: stars, counts, and one piece of advice. */
@Composable
private fun ResultDialog(r: PlayResult) {
    val s = AppState
    val dark = Color(0xFF17181A)
    val muted = Color(0xFF3F464B)
    Sheet({ s.dismissResult() }, 560.dp, r.song.title) {
        Text(
            "★".repeat(r.stars) + "☆".repeat(3 - r.stars),
            color = KAccent, fontSize = 44.sp, letterSpacing = 4.sp,
        )
        Text(
            if (r.stars >= r.best && r.stars > 0) "${r.mode.title} · nuovo record!" else "${r.mode.title} · record " + "★".repeat(r.best.coerceAtLeast(0)),
            color = muted, fontSize = 15.sp,
        )
        val lines = buildList {
            if (r.mode == PlayMode.A_TEMPO) {
                add("A tempo" to "${r.onTime} su ${r.notes}")
                if (r.near > 0) add("Un po' presto o tardi" to "${r.near}")
                if (r.missed > 0) add("Mancate" to "${r.missed}")
            } else {
                add("Giuste al primo colpo" to "${r.onTime} su ${r.notes}")
            }
            add("Tasti sbagliati" to "${r.wrong}")
        }
        lines.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth()) {
                Text(k, color = dark, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(v, color = dark, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = KMono)
            }
        }
        Text(advice(r), color = dark, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetChip("Riprova", true) {
                s.dismissResult()
                if (r.mode == PlayMode.A_TEMPO) s.startTake() else s.selectSong(r.song)
            }
            SheetChip("Chiudi", false) { s.dismissResult() }
        }
    }
}

private fun advice(r: PlayResult): String {
    val played = r.onTime + r.near
    return when {
        r.mode == PlayMode.A_TEMPO && played >= 4 && r.meanOffsetMs <= -25 ->
            "Tendi ad anticipare di circa ${-r.meanOffsetMs} ms: aspetta il click."
        r.mode == PlayMode.A_TEMPO && played >= 4 && r.meanOffsetMs >= 25 ->
            "Tendi a ritardare di circa ${r.meanOffsetMs} ms: segui il click e parti un attimo prima."
        r.mode == PlayMode.A_TEMPO && r.missed * 4 > r.notes ->
            "Molte note mancate: prova la velocità lenta, o prima in modo Impara."
        r.worstKey != null -> "Occhio al ${keyName(r.worstKey)}: è lì che sbagli di più."
        r.stars == 3 && r.mode == PlayMode.IMPARA -> "Nessun errore: ora prova in modo A tempo!"
        r.stars == 3 -> "Ottimo controllo del tempo."
        else -> "Riprova: ogni volta va un po' meglio."
    }
}
