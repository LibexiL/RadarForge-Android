package com.libexil.radarforge.core

import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hurricanes, like the desktop app (radarforge/features/tropical.py): active storms from the National Hurricane
 * Center (position, forecast cone and track, wind field, coastal watches / warnings), the tropical weather outlook,
 * model tracks (ATCF a-decks), past storms (HURDAT2 and this season's b-decks), when a storm's winds would reach a
 * place, and NDBC buoys.
 */
object Tropical {
    const val CURRENT_URL = "https://www.nhc.noaa.gov/CurrentStorms.json"
    const val MAPSERVER = "https://mapservices.weather.noaa.gov/tropical/rest/services/tropical/NHC_tropical_weather/MapServer"
    const val ATCF = "https://ftp.nhc.noaa.gov/atcf"
    const val HURDAT_INDEX = "https://www.nhc.noaa.gov/data/"
    const val NDBC_LATEST = "https://www.ndbc.noaa.gov/data/latest_obs/latest_obs.txt"
    const val KM_PER_NM = 1.852

    // ---------------------------------------------------------------- categories
    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    val CAT_COLORS = mapOf("TD" to rgb(94, 186, 255), "TS" to rgb(0, 250, 244), "1" to rgb(255, 255, 204), "2" to rgb(255, 231, 117),
        "3" to rgb(255, 193, 64), "4" to rgb(255, 143, 32), "5" to rgb(255, 96, 96), "EX" to rgb(200, 200, 200))
    val CLASS_NAMES = mapOf("HU" to "Hurricane", "TS" to "Tropical Storm", "TD" to "Tropical Depression", "STS" to "Subtropical Storm",
        "STD" to "Subtropical Depression", "PTC" to "Potential Tropical Cyclone", "PC" to "Post-Tropical Cyclone",
        "EX" to "Extratropical", "LO" to "Low", "DB" to "Disturbance", "SS" to "Subtropical Storm", "SD" to "Subtropical Depression",
        "TY" to "Typhoon", "MH" to "Major Hurricane")

    fun category(kt: Double?): String {
        if (kt == null || kt.isNaN() || kt < 34) return "TD"
        if (kt < 64) return "TS"
        for ((c, lim) in listOf("1" to 83, "2" to 96, "3" to 113, "4" to 137)) if (kt < lim) return c
        return "5"
    }

    fun catLabel(kt: Double?): String = when (val c = category(kt)) { "TD" -> "Tropical depression"; "TS" -> "Tropical storm"; else -> "Category $c hurricane" }
    fun catColor(kt: Double?): Int = CAT_COLORS.getValue(category(kt))

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 6371.0 * 2 * asin(minOf(1.0, sqrt(a)))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    // ---------------------------------------------------------------- active storms
    class TrackPoint(val timeMs: Long, val lat: Double, val lon: Double, val wind: Double? = null, val pressure: Double? = null,
                     val label: String = "", val tau: Int? = null)

    /** A ring or line, lon/lat pairs. */
    class Ring(val pts: FloatArray)

    class Radii(val tau: Int, val kt: Int, val ne: Double, val se: Double, val sw: Double, val nw: Double, val validMs: Long) {
        fun nm(quad: String) = when (quad) { "ne" -> ne; "se" -> se; "sw" -> sw; else -> nw }
    }

    class Storm(
        val id: String, val bin: String, val name: String, val classification: String, val wind: Double, val pressure: Double?,
        val lat: Double, val lon: Double, val moveDir: Double?, val moveKt: Double?, val updatedMs: Long, val advisory: String = "",
        val links: List<Pair<String, String>> = emptyList(),
    ) {
        var forecast: List<TrackPoint> = emptyList()
        var trackLine: List<Ring> = emptyList()
        var cone: List<Ring> = emptyList()
        var past: List<TrackPoint> = emptyList()
        var warnings: List<Pair<String, Ring>> = emptyList()
        var windNow: List<Pair<Int, Ring>> = emptyList()
        var radii: List<Radii> = emptyList()
        var loadedMs = 0L

        val title: String
            get() {
                var n = CLASS_NAMES[classification] ?: classification
                if (classification == "HU" && wind >= 96) n = "Major Hurricane"
                return "$n $name"
            }
        val basin: String get() = id.take(2).uppercase()
    }

    private fun numOf(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun parseCurrent(json: String): List<Storm> {
        val root = Json.parse(json)
        val items = (root as? Map<*, *>)?.get("activeStorms")?.arr() ?: root.arr()
        val out = ArrayList<Storm>()
        for (it in items) {
            val s = it.obj()
            val id = s["id"].str()
            if (id.isEmpty()) continue
            val lat = numOf(s["latitudeNumeric"]) ?: continue
            val lon = numOf(s["longitudeNumeric"]) ?: continue
            val links = ArrayList<Pair<String, String>>()
            for ((k, label) in listOf("publicAdvisory" to "Public advisory", "forecastDiscussion" to "Forecast discussion",
                "forecastAdvisory" to "Forecast advisory", "windSpeedProbabilities" to "Wind probabilities", "forecastGraphics" to "NHC graphics")) {
                val u = s[k].obj()["url"].str()
                if (u.isNotEmpty()) links.add(label to u)
            }
            val adv = s["publicAdvisory"].obj()["advNum"].let { a -> (a as? String) ?: numOf(a)?.toInt()?.toString() ?: "" }
            out.add(Storm(id.lowercase(), s["binNumber"].str(), s["name"].str("?"), s["classification"].str("TS"),
                numOf(s["intensity"]) ?: 0.0, numOf(s["pressure"]), lat, lon, numOf(s["movementDir"]), numOf(s["movementSpeed"]),
                Time.parseIso(s["lastUpdate"].str()), adv, links))
        }
        return out
    }

    /** Lines / outer rings of a GeoJSON geometry (lon, lat pairs). */
    fun geoRings(geom: Any?): List<Ring> {
        val g = geom.obj()
        val c = g["coordinates"].arr()
        fun line(pts: List<Any?>): Ring? {
            if (pts.size < 2) return null
            val r = FloatArray(pts.size * 2)
            for ((i, p) in pts.withIndex()) { val q = p.arr(); r[2 * i] = q.getOrNull(0).num(0.0).toFloat(); r[2 * i + 1] = q.getOrNull(1).num(0.0).toFloat() }
            return Ring(r)
        }
        return when (g["type"].str()) {
            "LineString" -> listOfNotNull(line(c))
            "MultiLineString" -> c.mapNotNull { line(it.arr()) }
            "Polygon" -> listOfNotNull(c.firstOrNull()?.arr()?.let { if (it.size > 2) line(it) else null })
            "MultiPolygon" -> c.mapNotNull { p -> p.arr().firstOrNull()?.arr()?.let { if (it.size > 2) line(it) else null } }
            else -> emptyList()
        }
    }

    /** "15/1200" (day / HHMM, in the advisory's month) -> ms. */
    fun validFrom(advMs: Long, text: String?): Long? {
        val m = Regex("""\s*(\d{1,2})/(\d{2})(\d{2})""").find(text ?: "") ?: return null
        if (advMs <= 0) return null
        val d = m.groupValues[1].toInt(); val hh = m.groupValues[2].toInt(); val mm = m.groupValues[3].toInt()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = advMs }
        var y = cal.get(Calendar.YEAR); var mo = cal.get(Calendar.MONTH)
        if (d < cal.get(Calendar.DAY_OF_MONTH) - 15) { mo += 1; if (mo > 11) { y += 1; mo = 0 } }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo, d, hh, mm) }.timeInMillis
    }

    fun parseForecastPoints(json: String, advMs: Long): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        for (f in Json.parse(json).obj()["features"].arr()) {
            val p = f.obj()["properties"].obj(); val g = f.obj()["geometry"].obj()
            if (g["type"].str() != "Point") continue
            val c = g["coordinates"].arr()
            val tau = numOf(p["tau"])?.toInt()
            var t = validFrom(advMs, p["validtime"].str())
            if (t == null && advMs > 0 && tau != null) t = advMs + tau * Models.HOUR
            out.add(TrackPoint(t ?: 0L, c.getOrNull(1).num(), c.getOrNull(0).num(), numOf(p["maxwind"]), numOf(p["mslp"]), p["dvlbl"].str(), tau))
        }
        out.sortBy { it.tau ?: 999 }
        return out
    }

    fun parsePastPoints(json: String): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        val f10 = Models.fmt("yyyyMMddHH")
        for (f in Json.parse(json).obj()["features"].arr()) {
            val p = f.obj()["properties"].obj(); val g = f.obj()["geometry"].obj()
            if (g["type"].str() != "Point") continue
            val c = g["coordinates"].arr()
            val dtg = numOf(p["dtg"])?.toLong()?.toString()
            val t = dtg?.let { try { f10.parse(it)!!.time } catch (_: Exception) { 0L } } ?: 0L
            out.add(TrackPoint(t, c.getOrNull(1).num(), c.getOrNull(0).num(), numOf(p["intensity"]), numOf(p["mslp"]), p["stormtype"].str()))
        }
        out.sortBy { it.timeMs }
        return out
    }

    fun parseRadii(json: String, advMs: Long): List<Radii> {
        val out = ArrayList<Radii>()
        for (f in Json.parse(json).obj()["features"].arr()) {
            val p = f.obj()["properties"].obj()
            val kt = numOf(p["radii"]) ?: continue
            val tau = numOf(p["tau"])?.toInt() ?: 0
            fun q(k: String) = numOf(p[k]) ?: 0.0
            out.add(Radii(tau, kt.toInt(), q("ne"), q("se"), q("sw"), q("nw"), validFrom(advMs, p["validtime"].str()) ?: (advMs + tau * Models.HOUR)))
        }
        return out
    }

    /** Finds the MapServer layer numbers by name ("AT1 Forecast Cone") and queries them as GeoJSON. */
    class NhcService(private val get: (String) -> String) {
        private var layers: Map<String, Int> = emptyMap()
        private var at = 0L

        fun layers(): Map<String, Int> {
            if (layers.isEmpty() || System.currentTimeMillis() - at > 6 * Models.HOUR) {
                val js = Json.parse(get("$MAPSERVER?f=json")).obj()
                layers = js["layers"].arr().mapNotNull { l -> val o = l.obj(); val id = numOf(o["id"])?.toInt(); if (id == null) null else o["name"].str().trim() to id }.toMap()
                at = System.currentTimeMillis()
            }
            return layers
        }

        fun query(name: String): String {
            val id = layers()[name] ?: return """{"features": []}"""
            return get("$MAPSERVER/$id/query?where=" + URLEncoder.encode("1=1", "UTF-8") + "&outFields=*&outSR=4326&f=geojson")
        }
    }

    fun loadStormLayers(svc: NhcService, st: Storm) {
        val b = st.bin.uppercase()
        val adv = st.updatedMs
        st.forecast = parseForecastPoints(svc.query("$b Forecast Points"), adv)
        st.trackLine = Json.parse(svc.query("$b Forecast Track")).obj()["features"].arr().flatMap { geoRings(it.obj()["geometry"]) }
        st.cone = Json.parse(svc.query("$b Forecast Cone")).obj()["features"].arr().flatMap { geoRings(it.obj()["geometry"]) }
        st.past = parsePastPoints(svc.query("$b Past Points"))
        st.warnings = Json.parse(svc.query("$b Watch-Warning")).obj()["features"].arr().flatMap { f ->
            val code = f.obj()["properties"].obj()["tcww"].str()
            geoRings(f.obj()["geometry"]).map { code to it }
        }
        val radiiJson = svc.query("$b Forecast Wind Radii")
        st.radii = parseRadii(radiiJson, adv)
        st.windNow = Json.parse(svc.query("$b Advisory Wind Field")).obj()["features"].arr().flatMap { f ->
            val kt = numOf(f.obj()["properties"].obj()["radii"])?.toInt() ?: 34
            geoRings(f.obj()["geometry"]).map { kt to it }
        }
        st.loadedMs = System.currentTimeMillis()
    }

    /** Coastal watch / warning lines: colour, width (dp), name. */
    val WARN_STYLE = mapOf("HWR" to Triple(rgb(255, 0, 0), 6f, "Hurricane warning"), "HWA" to Triple(rgb(255, 127, 127), 6f, "Hurricane watch"),
        "TWR" to Triple(rgb(0, 77, 168), 4f, "Tropical storm warning"), "TWA" to Triple(rgb(255, 255, 0), 4f, "Tropical storm watch"))

    // ---------------------------------------------------------------- outlook
    class OutlookArea(val ring: Ring, val p2: String, val p7: String, val risk7: String, val basin: String)
    class OutlookPoint(val lat: Double, val lon: Double, val p2: String, val p7: String)

    fun loadOutlook(svc: NhcService): Pair<List<OutlookArea>, List<OutlookPoint>> {
        val areas = ArrayList<OutlookArea>()
        for (f in Json.parse(svc.query("Seven-Day: Potential Development Region")).obj()["features"].arr()) {
            val p = f.obj()["properties"].obj()
            for (r in geoRings(f.obj()["geometry"])) areas.add(OutlookArea(r, p["prob2day"].str(), p["prob7day"].str(), p["risk7day"].str(), p["basin"].str()))
        }
        val points = ArrayList<OutlookPoint>()
        for (name in listOf("Two-Day: Current Location", "Seven-Day: Current Location")) {
            for (f in Json.parse(svc.query(name)).obj()["features"].arr()) {
                val p = f.obj()["properties"].obj(); val g = f.obj()["geometry"].obj()
                if (g["type"].str() != "Point") continue
                val c = g["coordinates"].arr()
                val lat = c.getOrNull(1).num(); val lon = c.getOrNull(0).num()
                if (points.none { Math.abs(it.lat - lat) < 0.01 && Math.abs(it.lon - lon) < 0.01 }) points.add(OutlookPoint(lat, lon, p["prob2day"].str(), p["prob7day"].str()))
            }
        }
        return areas to points
    }

    fun outlookColor(p7: String): Int {
        val v = p7.trim('%', ' ').toDoubleOrNull() ?: return rgb(255, 255, 0)
        return if (v >= 70) rgb(230, 0, 0) else if (v >= 40) rgb(230, 152, 0) else rgb(255, 255, 0)
    }

    // ---------------------------------------------------------------- model tracks (a-decks)
    val AID_NAMES = mapOf("OFCL" to "Official (NHC)", "AVNO" to "GFS", "AVNI" to "GFS", "HFAI" to "HAFS-A", "HFBI" to "HAFS-B",
        "HFSA" to "HAFS-A", "HFSB" to "HAFS-B", "HWRF" to "HWRF", "HWFI" to "HWRF", "CMC" to "Canadian", "CMCI" to "Canadian",
        "NVGM" to "Navy", "NVGI" to "Navy", "UKX" to "UK Met", "UKXI" to "UK Met", "UKM" to "UK Met", "UKMI" to "UK Met",
        "EMXI" to "European", "EMX" to "European", "ECMF" to "European", "ECM" to "European", "TVCN" to "Consensus",
        "TVCA" to "Consensus", "IVCN" to "Consensus", "HCCA" to "HCCA consensus", "CLP5" to "CLIPER", "XTRP" to "Extrapolation",
        "BAMD" to "BAM deep", "BAMM" to "BAM medium", "BAMS" to "BAM shallow", "AEMN" to "GFS ensemble mean", "AEMI" to "GFS ensemble mean")
    val AID_COLORS = mapOf("OFCL" to rgb(255, 255, 255), "AVNO" to rgb(60, 140, 255), "AVNI" to rgb(60, 140, 255), "HFAI" to rgb(255, 90, 90),
        "HFBI" to rgb(255, 160, 60), "HWRF" to rgb(255, 120, 120), "HWFI" to rgb(255, 120, 120), "CMC" to rgb(120, 220, 120),
        "CMCI" to rgb(120, 220, 120), "NVGM" to rgb(190, 140, 255), "NVGI" to rgb(190, 140, 255), "UKX" to rgb(255, 220, 60),
        "UKXI" to rgb(255, 220, 60), "UKM" to rgb(255, 220, 60), "EMXI" to rgb(255, 80, 200), "EMX" to rgb(255, 80, 200),
        "TVCN" to rgb(240, 240, 140), "IVCN" to rgb(240, 240, 140), "CLP5" to rgb(160, 160, 160), "XTRP" to rgb(160, 160, 160),
        "AEMN" to rgb(110, 180, 255), "AEMI" to rgb(110, 180, 255))
    /** The aids drawn by default (the rest are early-cycle copies, ensembles and statistical tracks). */
    val MAIN_AIDS = setOf("OFCL", "AVNI", "AVNO", "HFAI", "HFBI", "HWFI", "CMCI", "EMXI", "UKXI", "NVGI", "TVCN", "IVCN", "AEMI", "XTRP")

    /** "254N" / "0885W" -> degrees. */
    fun ll(text: String): Double? {
        val t = text.trim()
        if (t.length < 2) return null
        val v = t.dropLast(1).trim().toIntOrNull() ?: return null
        return when (t.last()) { 'S', 'W' -> -v / 10.0; 'N', 'E' -> v / 10.0; else -> null }
    }

    class Aid(val tech: String, val cycleMs: Long, val points: List<TrackPoint>)

    /** ATCF a-deck lines -> each tech's newest cycle (techs more than 12 h behind the newest are dropped). */
    fun parseAdeck(text: String, techs: Set<String>? = null): List<Aid> {
        val by = HashMap<String, HashMap<Long, java.util.TreeMap<Int, TrackPoint>>>()
        val f10 = Models.fmt("yyyyMMddHH")
        for (line in text.lineSequence()) {
            val p = line.split(",").map { it.trim() }
            if (p.size < 9) continue
            val tech = p[4].uppercase()
            if (techs != null && tech !in techs) continue
            val cyc = try { f10.parse(p[2])!!.time } catch (_: Exception) { continue }
            val tau = p[5].toIntOrNull() ?: continue
            val lat = ll(p[6]) ?: continue; val lon = ll(p[7]) ?: continue
            if (lat == 0.0 && lon == 0.0) continue
            val w = p[8].toDoubleOrNull()
            by.getOrPut(tech) { HashMap() }.getOrPut(cyc) { java.util.TreeMap() }[tau] =
                TrackPoint(cyc + tau * Models.HOUR, lat, lon, if (w != null && w > 0) w else null, tau = tau)
        }
        val newest = by.values.flatMap { it.keys }.maxOrNull() ?: return emptyList()
        val out = ArrayList<Aid>()
        for ((tech, cycles) in by) {
            val cyc = cycles.keys.max()
            if (newest - cyc > 12 * Models.HOUR) continue
            out.add(Aid(tech, cyc, cycles.getValue(cyc).values.toList()))
        }
        return out.sortedBy { it.tech }
    }

    fun gunzipText(raw: ByteArray): String {
        val b = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(raw.inputStream()).readBytes() else raw
        return String(b, Charsets.ISO_8859_1)
    }

    fun adeckUrl(stormId: String) = "$ATCF/aid_public/a${stormId.lowercase()}.dat.gz"

    // ---------------------------------------------------------------- best tracks (HURDAT2, b-decks)
    class PastStorm(val id: String, val name: String, val points: List<TrackPoint>, val landfalls: List<TrackPoint> = emptyList()) {
        val year: Int get() = id.takeLast(4).toIntOrNull() ?: 0
        val peak: Double get() = points.maxOfOrNull { it.wind ?: 0.0 } ?: 0.0
        val displayName: String get() = if (name.isNotBlank() && !name.equals("UNNAMED", true))
            name.lowercase().replaceFirstChar { it.titlecase() } else "Storm ${id.substring(2, 4)}"
        val title: String get() = "$displayName ($year) – ${catLabel(peak).lowercase()}, ${peak.toInt()} kt"
    }

    private val HURDAT_HEAD = Regex("""^(AL|EP|CP)\d{6}$""")
    private val HLL = Regex("""^(\d+(?:\.\d+)?)([NSEW])$""")

    private fun hll(t: String): Double? {
        val m = HLL.find(t.trim()) ?: return null
        val v = m.groupValues[1].toDouble()
        return if (m.groupValues[2] == "S" || m.groupValues[2] == "W") -v else v
    }

    fun parseHurdat2(text: String): List<PastStorm> {
        val out = ArrayList<PastStorm>()
        var id = ""; var name = ""; var pts = ArrayList<TrackPoint>(); var lands = ArrayList<TrackPoint>()
        val f = Models.fmt("yyyyMMddHHmm")
        fun flush() { if (id.isNotEmpty()) out.add(PastStorm(id, name, pts, lands)) }
        for (line in text.lineSequence()) {
            val p = line.split(",").map { it.trim() }
            if (p.size >= 3 && HURDAT_HEAD.matches(p[0])) {
                flush()
                id = p[0]; name = p[1]; pts = ArrayList(); lands = ArrayList()
                continue
            }
            if (id.isEmpty() || p.size < 8) continue
            val t = try { f.parse(p[0] + p[1].padStart(4, '0'))!!.time } catch (_: Exception) { continue }
            val lat = hll(p[4]) ?: continue; val lon = hll(p[5]) ?: continue
            val w = p[6].toDoubleOrNull(); val pr = p[7].toDoubleOrNull()
            val tp = TrackPoint(t, lat, lon, if (w != null && w >= 0) w else null, if (pr != null && pr > 0) pr else null, p[3])
            pts.add(tp)
            if (p[2] == "L") lands.add(tp)
        }
        flush()
        return out
    }

    fun parseBdeck(text: String, sid: String): PastStorm {
        val pts = java.util.TreeMap<Long, TrackPoint>()
        var name = ""
        val f10 = Models.fmt("yyyyMMddHH")
        for (line in text.lineSequence()) {
            val p = line.split(",").map { it.trim() }
            if (p.size < 11) continue
            val t = try { f10.parse(p[2])!!.time } catch (_: Exception) { continue }
            val lat = ll(p[6]) ?: continue; val lon = ll(p[7]) ?: continue
            if (p.size > 27 && p[27].isNotEmpty()) name = p[27]
            pts[t] = TrackPoint(t, lat, lon, p[8].toDoubleOrNull(), p[9].toDoubleOrNull(), p[10])
        }
        return PastStorm(sid.uppercase(), name.ifEmpty { sid.uppercase() }, pts.values.toList())
    }

    /** The newest HURDAT2 file name for a basin on NHC's data page. */
    fun hurdatName(html: String, basin: String): String? {
        val pat = if (basin.uppercase() == "AL") Regex("""hurdat2-1851-\d{4}-\d+\.txt""") else Regex("""hurdat2-nepac-1949-\d{4}-\d+\.txt""")
        return pat.findAll(html).map { it.value }.toSortedSet().lastOrNull()
    }

    fun bdeckNames(html: String, basin: String, year: Int): List<String> =
        Regex("""b(${basin.lowercase()}\d\d$year)\.dat""").findAll(html).map { it.groupValues[1] }.toSortedSet().toList()

    /** Storms whose track came within [km] of a point (since [minYear]), newest first, with the closest distance. */
    fun tracksNear(storms: List<PastStorm>, lat: Double, lon: Double, km: Double = 150.0, minYear: Int = 1950): List<Pair<PastStorm, Double>> {
        val out = ArrayList<Pair<PastStorm, Double>>()
        for (s in storms) {
            if (s.year < minYear) continue
            // a quick box test first: HURDAT2 has thousands of storms
            val dLat = km / 111.0
            if (s.points.none { Math.abs(it.lat - lat) <= dLat + 1 }) continue
            val best = s.points.minOfOrNull { distanceKm(lat, lon, it.lat, it.lon) } ?: continue
            if (best <= km) out.add(s to best)
        }
        out.sortByDescending { it.first.year }
        return out
    }

    // ---------------------------------------------------------------- arrival times
    class Sample(val timeMs: Long, val lat: Double, val lon: Double, val wind: Double?)

    fun interpolateTrack(points: List<TrackPoint>, stepMin: Int = 15): List<Sample> {
        val pts = points.filter { it.timeMs > 0 }
        val out = ArrayList<Sample>()
        for (k in 0 until pts.size - 1) {
            val a = pts[k]; val b = pts[k + 1]
            val n = maxOf(1, ((b.timeMs - a.timeMs) / 60_000 / stepMin).toInt())
            for (i in 0 until n) {
                val f = i.toDouble() / n
                val w = if (a.wind == null || b.wind == null) null else a.wind + (b.wind - a.wind) * f
                out.add(Sample(a.timeMs + ((b.timeMs - a.timeMs) * f).toLong(), a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, w))
            }
        }
        pts.lastOrNull()?.let { out.add(Sample(it.timeMs, it.lat, it.lon, it.wind)) }
        return out
    }

    private fun quadrant(b: Double) = if (b < 90) "ne" else if (b < 180) "se" else if (b < 270) "sw" else "nw"

    /** Radius (km) of [kt] winds in a quadrant at time [t], linear between forecast times (null outside them). */
    fun radiusAt(radii: List<Radii>, t: Long, kt: Int, quad: String): Double? {
        val rows = radii.filter { it.kt == kt && it.validMs > 0 }.sortedBy { it.validMs }
        if (rows.isEmpty()) return null
        if (t < rows.first().validMs - Models.HOUR || t > rows.last().validMs + Models.HOUR) return null
        if (t <= rows.first().validMs) return rows.first().nm(quad) * KM_PER_NM
        for (k in 0 until rows.size - 1) {
            val a = rows[k]; val b = rows[k + 1]
            if (t <= b.validMs) {
                val f = (t - a.validMs).toDouble() / maxOf(1L, b.validMs - a.validMs)
                return (a.nm(quad) + (b.nm(quad) - a.nm(quad)) * f) * KM_PER_NM
            }
        }
        return rows.last().nm(quad) * KM_PER_NM
    }

    class Arrival(val closestMs: Long, val closestKm: Double, val closestWind: Double?, val closestDir: Double,
                  val tsArrive: Long?, val tsLeave: Long?, val huArrive: Long?)

    /** Closest approach and tropical-storm / hurricane-force wind arrival at a point, from the official forecast. */
    fun arrival(st: Storm, lat: Double, lon: Double): Arrival? {
        val pts = st.forecast.ifEmpty { listOf(TrackPoint(st.updatedMs, st.lat, st.lon, st.wind, tau = 0)) }
        val track = interpolateTrack(pts)
        if (track.isEmpty()) return null
        var best: Sample? = null; var bestD = Double.MAX_VALUE
        var tsA: Long? = null; var tsL: Long? = null; var huA: Long? = null
        for (s in track) {
            val d = distanceKm(s.lat, s.lon, lat, lon)
            if (d < bestD) { bestD = d; best = s }
            val q = quadrant(bearingDeg(s.lat, s.lon, lat, lon))
            val r34 = radiusAt(st.radii, s.timeMs, 34, q)
            val r64 = radiusAt(st.radii, s.timeMs, 64, q)
            if (r34 != null && r34 > 0 && d <= r34) { if (tsA == null) tsA = s.timeMs; tsL = s.timeMs }
            if (r64 != null && r64 > 0 && d <= r64 && huA == null) huA = s.timeMs
        }
        val b = best ?: return null
        return Arrival(b.timeMs, bestD, b.wind, bearingDeg(lat, lon, b.lat, b.lon), tsA, tsL, huA)
    }

    // ---------------------------------------------------------------- buoys
    class Buoy(val id: String, val lat: Double, val lon: Double, val timeMs: Long, val wdir: Double?, val wspdKt: Double?,
               val gustKt: Double?, val waveFt: Double?, val pres: Double?, val airF: Double?, val waterF: Double?)

    fun parseNdbc(text: String): List<Buoy> {
        val out = ArrayList<Buoy>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 19) continue
            fun v(i: Int) = p.getOrNull(i)?.let { if (it == "MM") null else it.toDoubleOrNull() }
            val t = try {
                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(p[3].toInt(), p[4].toInt() - 1, p[5].toInt(), p[6].toInt(), p[7].toInt()) }.timeInMillis
            } catch (_: Exception) { 0L }
            val lat = p[1].toDoubleOrNull() ?: continue; val lon = p[2].toDoubleOrNull() ?: continue
            out.add(Buoy(p[0], lat, lon, t, v(8), v(9)?.times(1.943844), v(10)?.times(1.943844), v(11)?.times(3.28084), v(15),
                v(17)?.let { it * 1.8 + 32 }, v(18)?.let { it * 1.8 + 32 }))
        }
        return out
    }
}
