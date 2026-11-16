package app.painmeter.core

import kotlin.math.roundToInt

/** One notification of the Bluetooth "Heart Rate Measurement" characteristic (0x2A37). */
data class HrMeasurement(
    val bpm: Int,
    /** null when the strap does not report skin contact. */
    val contact: Boolean?,
    /** Beat-to-beat intervals in milliseconds, when the strap sends them. */
    val rrMs: List<Int> = emptyList(),
)

object HrMeasurementParser {
    /**
     * Parses the characteristic value (Bluetooth Heart Rate Profile):
     * flags (bit 0: 16-bit value, bits 1–2: contact status, bit 3: energy expended present,
     * bit 4: RR intervals present), then the heart rate, optional energy, optional RR in 1/1024 s.
     */
    fun parse(value: ByteArray): HrMeasurement? {
        if (value.size < 2) return null
        val flags = value[0].toInt() and 0xFF
        var i = 1
        val bpm: Int
        if (flags and 0x01 != 0) {
            if (value.size < 3) return null
            bpm = u16(value, i); i += 2
        } else {
            bpm = value[i].toInt() and 0xFF; i += 1
        }
        val contact = when ((flags shr 1) and 0x03) {
            2 -> false
            3 -> true
            else -> null
        }
        if (flags and 0x08 != 0) i += 2
        val rr = mutableListOf<Int>()
        if (flags and 0x10 != 0) {
            while (i + 1 < value.size) {
                rr += (u16(value, i) * 1000.0 / 1024.0).roundToInt(); i += 2
            }
        }
        return HrMeasurement(bpm, contact, rr)
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
}

/**
 * Five HR zones, given by the four upper bounds of zones 1 to 4 (bpm, included in that zone).
 * Zone 5 is everything above the last bound.
 */
data class HrZones(val upperBounds: List<Int>) {
    init {
        require(upperBounds.size == 4) { "4 bounds expected" }
        require(upperBounds.zipWithNext().all { (a, b) -> a < b }) { "bounds must increase" }
    }

    /** Zone 1..5 for a heart rate. */
    fun zoneOf(bpm: Int): Int = upperBounds.indexOfFirst { bpm <= it }.let { if (it < 0) 5 else it + 1 }

    companion object {
        /** Default zones from max HR: 60 / 70 / 80 / 90 % of max. */
        fun fromMaxHr(maxHr: Int) = HrZones(listOf(0.60, 0.70, 0.80, 0.90).map { (maxHr * it).roundToInt() })
    }
}
