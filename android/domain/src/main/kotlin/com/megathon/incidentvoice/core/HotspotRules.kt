package com.megathon.incidentvoice.core

/** Every threshold of "this keeps happening" (repeat incidents) and "this is where things happen" (hotspots). Rules only, no model. */
object HotspotRules {
    /** REPEAT: at least this many reports of the same type at the same location ... */
    const val REPEAT_MIN_REPORTS = 3
    /** ... within this many days up to and including the report. */
    const val REPEAT_WINDOW_DAYS = 7

    /** HOTSPOT: at least this many reports of any type at the location ... */
    const val HOTSPOT_MIN_REPORTS = 5
    /** ... within this many days, */
    const val HOTSPOT_WINDOW_DAYS = 30
    /** or the location is one of the top N by count in that window ... */
    const val HOTSPOT_TOP_N = 3
    /** ... with at least this many reports (a single report does not make a place a hotspot on an almost empty device). */
    const val HOTSPOT_TOP_MIN_REPORTS = 2

    /** Trend on the dashboard: the last N days against the N days before. */
    const val TREND_DAYS = 7

    const val DAY_MS = 86_400_000L
}
