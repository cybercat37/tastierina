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
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip("Libera", s.song == null, "Suona libera, senza canzone") { s.selectSong(null) }
        for (sg in SONGS) Chip(sg.title, s.song == sg, "Canzone guidata: ${sg.title}") { s.selectSong(sg) }
        Spacer(Modifier.width(10.dp))
        s.timbreNames.forEachIndexed { i, name -> Chip(name, s.timbre == i, "Suono: $name") { s.selectTimbre(i) } }
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
            val now = i == s.songStep
            val done = i < s.songStep
            Box(
                Modifier.height(28.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (now) KGuide else Color(0xFF2C2E31))
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    keyName(sg.notes[i].key),
                    color = if (done) KInk.copy(alpha = 0.4f) else KInk,
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
    val after = if (sg != null && !demo && s.songStep + 1 < sg.notes.size) sg.notes[s.songStep + 1].key.takeIf { it != next } ?: -1 else -1

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
                                    s.keyDown(now)
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
