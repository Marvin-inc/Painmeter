package app.painmeter.core.elevation

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * One 1°×1° elevation tile in the SRTM "HGT" format: a square grid of big-endian signed 16-bit
 * metres, rows from north to south, columns from west to east, edges shared with the neighbours.
 * 3601×3601 for 1 arc-second (~30 m), 1201×1201 for 3 arc-seconds. −32768 means no data.
 *
 * Tiles come from the AWS Terrain Tiles open dataset ("skadi" layout, gzip-compressed).
 */
class HgtTile(val latSw: Int, val lonSw: Int, private val samples: ShortArray) {
    val size: Int = sqrt(samples.size.toDouble()).toInt()

    init {
        require(size * size == samples.size && size >= 2) { "not a square HGT grid" }
    }

    /** Elevation (m) at a point inside the tile, bilinear between the 4 surrounding samples. */
    fun elevation(lat: Double, lon: Double): Double? {
        val y = (latSw + 1 - lat) * (size - 1)
        val x = (lon - lonSw) * (size - 1)
        if (y < 0 || x < 0 || y > size - 1 || x > size - 1) return null
        val r0 = floor(y).toInt().coerceAtMost(size - 2)
        val c0 = floor(x).toInt().coerceAtMost(size - 2)
        val fy = y - r0
        val fx = x - c0
        val q = intArrayOf(at(r0, c0), at(r0, c0 + 1), at(r0 + 1, c0), at(r0 + 1, c0 + 1))
        if (q.any { it == VOID }) {
            // Next to a hole: use the nearest valid sample rather than mixing in the void.
            val nearest = listOf(
                Triple(r0, c0, (fx * fx + fy * fy)), Triple(r0, c0 + 1, (1 - fx) * (1 - fx) + fy * fy),
                Triple(r0 + 1, c0, fx * fx + (1 - fy) * (1 - fy)), Triple(r0 + 1, c0 + 1, (1 - fx) * (1 - fx) + (1 - fy) * (1 - fy)),
            ).filter { at(it.first, it.second) != VOID }.minByOrNull { it.third } ?: return null
            return at(nearest.first, nearest.second).toDouble()
        }
        val top = q[0] + (q[1] - q[0]) * fx
        val bottom = q[2] + (q[3] - q[2]) * fx
        return top + (bottom - top) * fy
    }

    private fun at(row: Int, col: Int): Int = samples[row * size + col].toInt()

    companion object {
        const val VOID = -32768

        /** Tile name for a point, e.g. "N45E005" for 45.2°N 5.7°E, "S34W071" for 33.4°S 70.6°W. */
        fun nameFor(lat: Double, lon: Double): String {
            val la = floor(lat).toInt()
            val lo = floor(lon).toInt()
            return String.format(
                Locale.ROOT, "%s%02d%s%03d",
                if (la >= 0) "N" else "S", kotlin.math.abs(la), if (lo >= 0) "E" else "W", kotlin.math.abs(lo),
            )
        }

        /** South-west corner of a named tile. */
        fun cornerOf(name: String): Pair<Int, Int> {
            val lat = name.substring(1, 3).toInt() * (if (name[0] == 'S') -1 else 1)
            val lon = name.substring(4, 7).toInt() * (if (name[3] == 'W') -1 else 1)
            return lat to lon
        }

        fun read(input: InputStream, name: String): HgtTile {
            val bytes = input.readBytes()
            require(bytes.size % 2 == 0) { "odd HGT size" }
            val samples = ShortArray(bytes.size / 2)
            DataInputStream(bytes.inputStream()).use { d -> for (i in samples.indices) samples[i] = d.readShort() }
            val (lat, lon) = cornerOf(name)
            return HgtTile(lat, lon, samples)
        }

        /** Reads a `.hgt` or `.hgt.gz` file named after its tile. */
        fun read(file: File): HgtTile {
            val name = file.name.take(7)
            return file.inputStream().buffered().use { raw ->
                if (file.name.endsWith(".gz")) GZIPInputStream(raw).use { read(it, name) } else read(raw, name)
            }
        }

        /** Download address of a tile in the AWS Terrain Tiles dataset. */
        fun url(name: String) = "https://s3.amazonaws.com/elevation-tiles-prod/skadi/${name.take(3)}/$name.hgt.gz"

        /** Names of the tiles covering a box. */
        fun tilesCovering(south: Double, west: Double, north: Double, east: Double): List<String> {
            val out = mutableListOf<String>()
            for (la in floor(south).toInt()..floor(north).toInt())
                for (lo in floor(west).toInt()..floor(east).toInt())
                    out += nameFor(la + 0.5, lo + 0.5)
            return out
        }
    }
}

/** Looks up elevations in a set of tiles, keeping the few most recently used in memory. */
class ElevationModel(private val load: (String) -> HgtTile?, private val cacheSize: Int = 2) {
    private val cache = LinkedHashMap<String, HgtTile?>(4, 0.75f, true)

    /** Forget loaded and missing tiles (after a download). */
    fun clear() = cache.clear()

    fun elevation(lat: Double, lon: Double): Double? {
        val name = HgtTile.nameFor(lat, lon)
        val tile = if (cache.containsKey(name)) cache[name] else load(name).also {
            cache[name] = it
            while (cache.size > cacheSize) cache.remove(cache.keys.first())
        }
        return tile?.elevation(lat, lon)
    }
}
