package com.libexil.radarforge.core

import java.util.Locale

/** Learn mode: plain-language notes about the values under the cross-hair, and the radar guide (as on the desktop app). */
object Learn {
    private const val KTS = 1.943844f
    private const val TDS_REF = 35f

    val PRODUCT_HELP: Map<Product, String> = mapOf(
        Product.REF to "Reflectivity (dBZ): how much energy comes back. Light rain 20–30, heavy rain 40–50, hail 60+. " +
            "Shapes matter: hooks, bows and lines.",
        Product.VEL to "Velocity: motion toward (green) or away from (red) the radar along the beam. Bright green next to " +
            "bright red means rotation (or a strong wind shift).",
        Product.SRV to "Storm-relative velocity: velocity with the storm's own motion removed, so rotation inside a moving " +
            "storm stands out. Set the storm motion in Settings, or from a storm track.",
        Product.SW to "Spectrum width: how varied the motion is inside each gate. High values mean turbulence or shear – " +
            "or just noisy, weak echo.",
        Product.ZDR to "Differential reflectivity: shape of the targets. Flat raindrops +1 to +4 dB, hail and snow near 0, " +
            "insects and birds very high.",
        Product.CC to "Correlation coefficient: how alike the targets are. Rain/snow 0.97+, hail or melting 0.85–0.95, " +
            "debris, birds, insects and ground clutter well below 0.8.",
        Product.PHI to "Differential phase: builds up through heavy rain. A steady climb along the beam means a lot of " +
            "liquid water; it's mostly used to estimate rainfall.",
    )

    /** Signatures worth knowing, for the radar guide. */
    val SIGNATURES: List<Pair<String, String>> = listOf(
        "Hook echo" to "A hook on the back (usually south-west) side of a storm on reflectivity, where the rotating " +
            "updraft wraps rain around itself. Check velocity there for a couplet.",
        "Velocity couplet" to "Strong inbound (green) right next to strong outbound (red). The tighter and stronger it " +
            "is, the stronger the rotation. Use SRV for moving storms.",
        "Debris ball / TDS" to "A small blob of high reflectivity with very low CC (below about 0.8) right where there's " +
            "rotation: lofted tornado debris. It means a tornado is likely already on the ground.",
        "Hail core" to "Reflectivity above 60 dBZ with ZDR near 0 and CC dipping to 0.85–0.95. The bigger the area, " +
            "the bigger the hail threat.",
        "Bow echo" to "A line of storms bulging outward. Damaging straight-line winds are likely at the tip of the bow; " +
            "look for strong inbound or outbound velocity behind it.",
        "Range folding" to "Purple speckle on velocity is range-folded data: the radar can't tell how far away those " +
            "echoes are. Nothing to worry about.",
        "Beam height" to "The beam rises with distance (and the Earth curves away), so far from the radar it overshoots " +
            "low-level rotation. The inspector shows the beam height.",
    )

    /**
     * Notes about the values at one spot. [values]: product -> value (product units: dBZ, m/s, dB, CC),
     * missing products left out. [beamFt]: beam height above the radar in feet.
     */
    fun explain(values: Map<Product, Float>, beamFt: Double? = null): List<String> {
        fun v(p: Product) = values[p]?.takeIf { it.isFinite() }
        val notes = ArrayList<String>()
        val ref = v(Product.REF); val cc = v(Product.CC); val zdr = v(Product.ZDR)
        val vel = v(Product.SRV) ?: v(Product.VEL)
        if (ref != null) notes.add(when {
            ref < 15 -> "${f0(ref)} dBZ: very weak echo – drizzle, light snow, insects or clear-air returns."
            ref < 30 -> "${f0(ref)} dBZ: light precipitation."
            ref < 45 -> "${f0(ref)} dBZ: moderate to heavy rain."
            ref < 55 -> "${f0(ref)} dBZ: very heavy rain; a thunderstorm core."
            ref < 65 -> "${f0(ref)} dBZ: intense core – hail is possible."
            else -> "${f0(ref)} dBZ: extreme – large hail is likely."
        })
        if (cc != null) notes.add(when {
            cc >= 0.97f -> "CC ${f2(cc)}: uniform targets – pure rain or snow."
            cc >= 0.90f -> "CC ${f2(cc)}: mixed sizes or phases – hail, melting snow or big drops."
            cc >= 0.80f -> "CC ${f2(cc)}: quite mixed – hail, the melting layer, or the edge of non-weather echo."
            else -> "CC ${f2(cc)}: non-weather targets." + if (ref != null && ref >= TDS_REF)
                " With reflectivity this high, it can be tornado debris if there's rotation right here."
                else " Usually birds, insects, smoke or ground clutter."
        })
        if (zdr != null) {
            notes.add(when {
                zdr < -0.5f -> "ZDR ${f1(zdr)} dB: vertically oriented targets (ice crystals in an electrified cloud) or a radar artefact."
                zdr < 0.8f -> "ZDR ${f1(zdr)} dB: round-looking targets – hail, graupel, snow or small drops."
                zdr < 3.5f -> "ZDR ${f1(zdr)} dB: flattened raindrops (bigger drops, higher ZDR)."
                else -> "ZDR ${f1(zdr)} dB: very large drops, or insects and birds."
            })
            if (ref != null && ref >= 55 && zdr < 1.0f) notes.add("High reflectivity with low ZDR is a classic hail signature.")
        }
        if (vel != null) {
            val kt = Math.abs(vel) * KTS
            val word = if (vel < 0) "toward" else "away from"
            notes.add("${f0(kt)} kt $word the radar" + if (kt >= 50) " – damaging-wind strength if it reaches the ground." else ".")
        }
        if (beamFt != null && beamFt > 10_000) notes.add(String.format(Locale.US, "The beam is %,d ft up here, so low-level features can be missed.", beamFt.toInt()))
        return notes
    }

    private fun f0(x: Float) = String.format(Locale.US, "%.0f", x)
    private fun f1(x: Float) = String.format(Locale.US, "%.1f", x)
    private fun f2(x: Float) = String.format(Locale.US, "%.2f", x)
}
