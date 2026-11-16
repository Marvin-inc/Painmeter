package app.painmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One data field: a small label at the top left, the value big at the bottom right.
 * [suffix] is drawn smaller after the value (the HR zone in "171 Z4").
 * [accent], when set, colours the value and draws a bar along the bottom edge (HR zone colour).
 */
@Composable
fun DataField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueSize: TextUnit = 46.sp,
    valueColor: Color = Pm.Text,
    suffix: String? = null,
    accent: Color? = null,
    background: Color = Pm.Surface,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(background)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Pm.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            FittedValue(
                text = buildAnnotatedString {
                    append(value)
                    if (suffix != null) {
                        append(" ")
                        withStyle(SpanStyle(fontSize = valueSize * 0.5f)) { append(suffix) }
                    }
                },
                color = accent ?: valueColor,
                size = valueSize,
            )
        }
        if (accent != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp).background(accent)
            )
        }
    }
}

/**
 * A value drawn right-aligned at [size], shrunk just enough to fit the field's width, so
 * "1:28:03" is never cut off in a narrow field.
 */
@Composable
private fun FittedValue(text: AnnotatedString, color: Color, size: TextUnit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val style = TextStyle(fontFamily = Pm.Numbers, fontWeight = FontWeight.Bold, fontSize = size)
        val natural = remember(text, size) { measurer.measure(text, style, maxLines = 1, softWrap = false).size.width }
        val available = with(density) { maxWidth.toPx() }
        val fitted = if (natural > available && natural > 0) size * (available / natural * 0.97f) else size
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            color = color,
            fontFamily = Pm.Numbers,
            fontWeight = FontWeight.Bold,
            fontSize = fitted,
            lineHeight = fitted,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The thin bar at the top of every ride page. */
@Composable
fun StatusBar(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(40.dp).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (text, color) ->
            Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
