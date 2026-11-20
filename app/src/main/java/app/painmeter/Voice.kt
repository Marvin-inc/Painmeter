package app.painmeter

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import app.painmeter.core.LapSummary
import java.util.Locale

/**
 * Spoken lap announcements for running (SPEC §16.4): when running the screen is rarely looked
 * at, so each lap (every km with the auto lap) is read out through the phone's speech engine,
 * over music, which is lowered meanwhile. In French when the phone is in French, else English.
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false

    fun start(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.apply {
                setLanguage(Locale.getDefault())
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
        }
    }

    fun stop() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    fun say(text: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "pm-${System.nanoTime()}")
    }

    /** "Kilomètre 3. 5 minutes 12. Cardio 152." for a lap of the ride. */
    fun lapText(lap: LapSummary, totalDistanceM: Double, french: Boolean = Locale.getDefault().language == "fr"): String {
        val secs = if (lap.distanceM > 50) Math.round(lap.timerMs / 1000.0 * 1000 / lap.distanceM) else null
        val km = Math.round(totalDistanceM / 1000.0)
        val head = when {
            lap.auto && french -> "Kilomètre $km."
            lap.auto -> "Kilometre $km."
            french -> "Tour ${lap.index + 1}."
            else -> "Lap ${lap.index + 1}."
        }
        val pace = secs?.let {
            if (french) "${it / 60} minutes ${it % 60} au kilo." else "${it / 60} minutes ${it % 60} per kilometre."
        } ?: ""
        val time = if (!lap.auto) {
            val t = lap.timerMs / 1000
            if (french) "Temps ${t / 60} minutes ${t % 60}." else "Time ${t / 60} minutes ${t % 60}."
        } else ""
        val hr = lap.avgHr?.let { if (french) "Cardio $it." else "Heart rate $it." } ?: ""
        return listOf(head, time, pace, hr).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
