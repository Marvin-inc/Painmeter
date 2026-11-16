package app.painmeter.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.painmeter.R
import java.util.Locale

/** Colours and type from the screen mockups. */
object Pm {
    val Background = Color(0xFF0B0D10)
    val Surface = Color(0xFF14181E)
    val SurfaceAlt = Color(0xFF1A2230)
    val Divider = Color(0xFF1C2128)
    val Border = Color(0xFF2A313B)
    val Text = Color(0xFFF2F4F7)
    val Muted = Color(0xFFA9B1BC)
    val Disabled = Color(0xFF5B6470)
    val Power = Color(0xFF7FD8F0)
    val Rec = Color(0xFFFF5A5F)
    val Amber = Color(0xFFF5A524)
    val Blue = Color(0xFF2F6FED)
    val Green = Color(0xFF3BC77A)

    val Numbers = FontFamily(
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    )
}

object Format {
    fun speedKmh(mps: Double?): String = mps?.let { String.format(Locale.ROOT, "%.1f", it * 3.6) } ?: "--"

    fun distanceKm(m: Double): String = String.format(Locale.ROOT, if (m < 100_000) "%.1f" else "%.0f", m / 1000)

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    fun metres(m: Double): String = String.format(Locale.ROOT, "%.0f", m)
}
