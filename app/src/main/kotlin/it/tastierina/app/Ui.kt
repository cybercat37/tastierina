package it.tastierina.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.abs
import kotlin.math.floor

private object C {
    val shell = Color(0xFFCC3323)
    val ink = Color(0xFFFFF4EE)
    val ivory = Color(0xFFF7F2E7)
    val pad = Color(0xFFEAE4D7)
    val ebony = Color(0xFF1B1918)
    val well = Color(0xFF151617)
    val lcd = Color(0xFFB9C6A2)
    val lcdInk = Color(0xFF1D2917)
    val guide = Color(0xFF0A857A)
    val guideGlow = Color(0x5962E3D2)
    val good = Color(0xFF7FD3A6)
    val near = Color(0xFFF0B429)
    val miss = Color(0xFFFF8A75)
    val muted = Color(0xFF56626A)
    val padColors = listOf(
        Color(0xFFE2523F), Color(0xFFE89A3C), Color(0xFFD9B23A), Color(0xFF5FB7A6),
        Color(0xFF6E9BE0), Color(0xFFA07BD6), Color(0xFFF0B429), Color(0xFF7CC0FF),
    )
}

private val Mono = FontFamily.Monospace
private const val FLASH_NANOS = 110_000_000L

@Composable
fun TastierinaApp() {
    val s = AppState
    var showLevels by remember { mutableStateOf(false) }
    var showSound by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) withFrameNanos { s.tick(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(C.shell)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TopBar(
            onLevels = { showLevels = true },
            onSound = { showSound = true },
            onHelp = { showHelp = true },
        )
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadGrid(Modifier.weight(1f).fillMaxHeight())
            if (s.tempo) TempoPanel(Modifier.width(160.dp).fillMaxHeight())
        }
    }

    if (showLevels) LevelsDialog { showLevels = false }
    if (showSound) SoundDialog { showSound = false }
    if (showHelp) HelpDialog { showHelp = false }
}

@Composable
private fun TopBar(onLevels: () -> Unit, onSound: () -> Unit, onHelp: () -> Unit) {
    val s = AppState
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (s.audioOk) Color(0xFFFFE27A) else Color(0xFF5E130C)),
        )
        Text("Tastierina", color = C.ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp)
        Pill(dark = true, label = "Livello 0, Pad. Cambia livello", onClick = onLevels) {
            Text("0", fontFamily = Mono, fontSize = 17.sp, color = C.ivory.copy(alpha = 0.75f))
            Spacer(Modifier.width(6.dp))
            Text("Pad", color = C.ivory, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Text("▾", color = C.ivory, fontSize = 12.sp)
        }
        Lcd(Modifier.weight(1f))
        if (s.tempo) {
            Pill(dark = true, label = "Velocità più lenta", onClick = { s.changeBpm(-5) }) {
                Text("−", color = C.ivory, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text("${s.bpm} BPM", color = C.ink, fontFamily = Mono, fontSize = 18.sp)
            Pill(dark = true, label = "Velocità più veloce", onClick = { s.changeBpm(5) }) {
                Text("+", color = C.ivory, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Pill(
            dark = false,
            filled = s.tempo,
            label = if (s.tempo) "Esci da Tieni il tempo" else "Tieni il tempo",
            onClick = { s.setTempoMode(!s.tempo) },
        ) {
            Text(
                if (s.tempo) "Fine" else "Tieni il tempo",
                color = if (s.tempo) C.ebony else C.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        RoundButton("Suono e volume", onSound) {
            Text("≡", color = C.ebony, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        RoundButton("Aiuto", onHelp) {
            Text("?", color = C.ebony, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun Lcd(modifier: Modifier) {
    val s = AppState
    val text = when {
        !s.audioOk -> "audio non disponibile: riapri l'app"
        s.tempo && s.beatPosition.isNaN() -> "pronti..."
        s.tempo -> "tocca la Cassa su ogni battito"
        s.lastPad >= 0 -> s.padNames[s.lastPad].uppercase() + "  ·  tocca, ascolta, ripeti"
        else -> "tocca un pad"
    }
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(7.dp)).background(C.lcd).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s.tempo) {
            val pos = s.beatPosition
            val lit = if (pos.isNaN() || pos < 0) -1 else Math.floorMod(floor(pos).toLong(), 4L).toInt()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .then(if (i == lit) Modifier.background(C.lcdInk) else Modifier.border(2.dp, C.lcdInk, CircleShape)),
                    )
                }
            }
        }
        Text(text, color = C.lcdInk, fontFamily = Mono, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PadGrid(modifier: Modifier) {
    val s = AppState
    val view = LocalView.current
    val gap = 10.dp
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(C.well)
            .padding(gap)
            // One handler for the whole grid: every finger down is a hit, even several at once.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (!change.changedToDownIgnoreConsumed()) continue
                            val col = (change.position.x / size.width * 4).toInt().coerceIn(0, 3)
                            val row = (change.position.y / size.height * 2).toInt().coerceIn(0, 1)
                            s.hit(row * 4 + col, change.uptimeMillis * 1_000_000L)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            change.consume()
                        }
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in 0 until 2) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (col in 0 until 4) {
                        Pad(row * 4 + col, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun Pad(i: Int, modifier: Modifier) {
    val s = AppState
    val color = C.padColors[i]
    val on = s.now - s.flashes[i] < FLASH_NANOS
    val target = s.tempo && i == AppState.TARGET_PAD
    val dim = s.tempo && !target
    // Near the beat the target pad glows, so the eye can follow the ear.
    val pos = s.beatPosition
    val nearBeat = target && !pos.isNaN() && pos >= -0.05 && (pos - floor(pos + 0.12)).let { abs(it) < 0.12 }
    Box(
        modifier
            .alpha(if (dim) 0.45f else 1f)
            .then(
                if (target) Modifier.border(3.dp, C.guide, RoundedCornerShape(12.dp))
                else Modifier,
            )
            .shadow(if (on || nearBeat) 10.dp else 0.dp, RoundedCornerShape(12.dp), ambientColor = color, spotColor = color)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    on -> color
                    target -> color.copy(alpha = if (nearBeat) 1f else 0.85f)
                    else -> C.pad
                },
            )
            .semantics { contentDescription = s.padNames[i] }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            Modifier.align(Alignment.TopStart).width(28.dp).height(6.dp)
                .clip(RoundedCornerShape(3.dp)).background(if (on || target) Color.White else color),
        )
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                s.padNames[i],
                color = if (on || target) Color.White else C.ebony,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
            )
            Text(
                if (target) "qui, sul battito" else s.padKinds[i],
                color = (if (on || target) Color.White else C.ebony).copy(alpha = 0.7f),
                fontFamily = Mono,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun TempoPanel(modifier: Modifier) {
    val s = AppState
    val j = s.lastJudgment
    val (word, color) = when (j?.verdict) {
        null -> "—" to C.ivory
        Verdict.ON_TIME -> "A tempo!" to C.good
        Verdict.EARLY -> "Un po' presto" to C.near
        Verdict.LATE -> "Un po' tardi" to C.near
        Verdict.OFF -> "Fuori tempo" to C.miss
    }
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(C.ebony).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("Ultimo colpo")
        Text(word, color = color, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp)
        BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
            val w = maxWidth
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(10.dp).clip(CircleShape).background(Color(0xFF3A3634)))
            Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(C.ivory))
            if (j != null) {
                // ±120 ms across the bar.
                val x = ((j.offsetMs / 240.0 + 0.5).coerceIn(0.0, 1.0)).toFloat()
                Box(
                    Modifier.align(Alignment.CenterStart).offset(x = w * x - 5.dp).size(10.dp).clip(CircleShape).background(color),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("presto", color = C.ivory.copy(alpha = 0.7f), fontFamily = Mono, fontSize = 13.sp)
            Text(
                if (j != null) "%+.0f ms".format(j.offsetMs) else "",
                color = C.ivory, fontFamily = Mono, fontSize = 13.sp,
            )
            Text("tardi", color = C.ivory.copy(alpha = 0.7f), fontFamily = Mono, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        Label("Di fila a tempo")
        Text("${s.streak}", color = C.ivory, fontFamily = Mono, fontSize = 40.sp, lineHeight = 40.sp)
        Text("record: ${s.best}", color = C.ivory.copy(alpha = 0.7f), fontFamily = Mono, fontSize = 13.sp)
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        color = C.ivory.copy(alpha = 0.8f),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.3.sp,
    )
}

@Composable
private fun Pill(
    dark: Boolean,
    label: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .height(36.dp)
            .clip(shape)
            .then(
                when {
                    dark -> Modifier.background(C.ebony)
                    filled -> Modifier.background(C.ivory)
                    else -> Modifier.border(1.5.dp, C.ink.copy(alpha = 0.6f), shape)
                },
            )
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun RoundButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(C.ivory)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Sheet(onClose: () -> Unit, width: Dp, title: String, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(width).clip(RoundedCornerShape(20.dp)).background(C.ivory).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = C.ebony, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(C.ebony)
                        .clickable(onClickLabel = "Chiudi", onClick = onClose)
                        .semantics { contentDescription = "Chiudi" },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = C.ivory, fontSize = 14.sp) }
            }
            content()
        }
    }
}

@Composable
private fun LevelsDialog(onClose: () -> Unit) {
    val levels = listOf(
        Triple("0", "Pad", "Otto pad da colpire e il gioco del battito."),
        Triple("1", "Tastierina", "La tastiera e le canzoni guidate a pallini."),
        Triple("2", "Ritmo", "Batteria a 16 passi e lezioni di ritmo."),
        Triple("3", "Groove", "Basso e melodia, registrazione dai tasti."),
        Triple("4", "Groovebox", "Giri A B C, battute e scaletta."),
    )
    Sheet(onClose, 720.dp, "Scegli il livello") {
        Text("Nella versione nativa per ora c'è il livello 0: gli altri arrivano uno alla volta.", color = C.muted, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            levels.forEachIndexed { i, (n, name, what) ->
                val ready = i == 0
                Column(
                    Modifier.weight(1f).height(190.dp).clip(RoundedCornerShape(14.dp))
                        .then(
                            if (ready) Modifier.background(C.shell)
                            else Modifier.background(Color(0xFFFFFDF8)).border(1.5.dp, Color(0xFFCFC6B5), RoundedCornerShape(14.dp)),
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val ink = if (ready) C.ink else C.ebony
                    Text(n, color = ink, fontFamily = Mono, fontSize = 32.sp)
                    Text(name, color = ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Text(what, color = ink.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 16.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (ready) "IN USO" else "IN ARRIVO", color = ink.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SoundDialog(onClose: () -> Unit) {
    val s = AppState
    val latency = remember { Engine.nativeLatencyMs(System.nanoTime()) }
    val rate = remember { Engine.nativeSampleRate() }
    val path = remember {
        when (Engine.nativeIsExclusive()) {
            1 -> "esclusivo (il più veloce)"
            0 -> "condiviso"
            else -> "chiuso"
        }
    }
    Sheet(onClose, 460.dp, "Suono") {
        Text("Volume", color = C.ebony, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = s.volume,
            onValueChange = { s.changeVolume(it) },
            colors = SliderDefaults.colors(thumbColor = C.shell, activeTrackColor = C.shell),
        )
        Text(
            if (latency >= 0) "Ritardo dell'audio: %.0f ms · %d Hz · percorso %s".format(latency, rate, path)
            else "Audio non attivo",
            color = C.muted,
            fontSize = 13.sp,
            style = TextStyle(fontFamily = Mono),
        )
    }
}

@Composable
private fun HelpDialog(onClose: () -> Unit) {
    val lines = listOf(
        "Pad" to "Tocca un pad per sentirlo. Puoi usare più dita insieme.",
        "Tieni il tempo" to "Parte il battito: tocca la Cassa su ogni colpo. Il pannello a destra dice se eri a tempo, presto o tardi, in millisecondi.",
        "− BPM +" to "Cambia la velocità del battito, anche mentre suona.",
        "Livello" to "Da qui si sale di livello, man mano che arrivano.",
        "≡" to "Volume, e quanto ritardo ha l'audio su questo telefono.",
    )
    Sheet(onClose, 560.dp, "Come si usa") {
        lines.forEach { (k, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(k, color = C.ebony, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(110.dp))
                Text(v, color = C.ebony, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}
