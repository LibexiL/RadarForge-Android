package com.libexil.radarforge.core

class Site(
    val id: String,
    val lat: Double,
    val lon: Double,
    val elevFt: Double,
    val place: String,
    val state: String,
    val tz: String,
) {
    val title: String get() = "$place, $state"
}

object Sites {
    /** WSR-88D sites (the ones with Level II data on AWS). */
    fun parse(json: String): List<Site> = Json.parse(json).arr().mapNotNull { e ->
        val o = e.obj()
        if (o["type"].str() != "wsr88d") return@mapNotNull null
        Site(o["id"].str(), o["lat"].num(), o["lon"].num(), o["elev_ft"].num(0.0),
            o["place"].str(), o["state"].str(), o["tz"].str("UTC"))
    }.sortedBy { it.id }

    fun nearest(sites: List<Site>, lat: Double, lon: Double): Site? =
        sites.minByOrNull { Geo.distanceKm(lat, lon, it.lat, it.lon) }
}
