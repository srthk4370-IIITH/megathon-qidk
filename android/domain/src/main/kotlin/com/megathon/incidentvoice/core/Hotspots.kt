package com.megathon.incidentvoice.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One submitted report as the hotspot rules see it. [locationKey] comes from [LocationKeys]. */
data class Occurrence(val id: String, val type: String?, val locationKey: String?, val location: String?, val atMs: Long)

/** "[n]th [type] here in [days] days". */
@Serializable
data class RepeatTag(val n: Int, val type: String, val days: Int)

@Serializable
data class ReportTags(val repeat: RepeatTag? = null, val hotspot: Boolean = false, val location: String? = null) {
    fun toJson(): String = json.encodeToString(serializer(), this)
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String?): ReportTags? = text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

enum class Trend { MORE, FEWER, SAME }

/** A location on the dashboard: reports in the hotspot window, the most common type, the last 7 days against the 7 before. */
data class HotspotStat(val key: String, val location: String, val total: Int, val topType: String?, val last7: Int, val prev7: Int, val latestMs: Long) {
    val trend: Trend get() = when { last7 > prev7 -> Trend.MORE; last7 < prev7 -> Trend.FEWER; else -> Trend.SAME }
}

/**
 * One key per place, however it was said: the site-registry id when the place is known ("conf room 2", "near
 * Conference Room two" and "Conference Room 2" are all A-1-CR2), otherwise the cleaned-up words.
 */
class LocationKeys(private val registry: SiteRegistry) {
    private val matcher = LocationMatcher(registry)
    private val byId = registry.locations.associateBy { it.id }
    private val cache = HashMap<String, Pair<String, String>?>()

    /** (key, name to show), or null when the report has no place. */
    fun of(locationId: String?, text: String?): Pair<String, String>? {
        byId[locationId]?.let { return it.id to it.name }
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        return cache.getOrPut(t.lowercase()) {
            val m = matcher.match(t)
            if (m.kind == MatchKind.AUTO) return@getOrPut m.location!!.id to m.location.name
            val norm = matcher.normalise(t).replace(Regex("^(?:(?:in|on|by|from|around|opposite) )+"), "")
            if (norm.isEmpty()) null else "text:$norm" to norm.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }
    }
}

/** Repeat incidents and hotspots, by counting. Thresholds: [HotspotRules]. */
object Hotspots {
    private const val DAY = HotspotRules.DAY_MS

    /** Counts [of] and the earlier reports of the same type at the same place in the 7 days up to it (the edge of the window is inside). */
    fun repeat(of: Occurrence, all: List<Occurrence>): RepeatTag? {
        val type = of.type ?: return null
        val key = of.locationKey ?: return null
        val from = of.atMs - HotspotRules.REPEAT_WINDOW_DAYS * DAY
        val n = all.count { it.id != of.id && it.type == type && it.locationKey == key && it.atMs in from..of.atMs } + 1
        return if (n >= HotspotRules.REPEAT_MIN_REPORTS) RepeatTag(n, type, HotspotRules.REPEAT_WINDOW_DAYS) else null
    }

    /** Every location with a report in the last 30 days, busiest first. */
    fun stats(all: List<Occurrence>, nowMs: Long): List<HotspotStat> {
        val from = nowMs - HotspotRules.HOTSPOT_WINDOW_DAYS * DAY
        val t = HotspotRules.TREND_DAYS * DAY
        return all.filter { it.locationKey != null && it.atMs in from..nowMs }.groupBy { it.locationKey!! }.map { (key, l) ->
            val topType = l.mapNotNull { it.type }.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
            HotspotStat(
                key, l.maxBy { it.atMs }.location ?: key, l.size, topType,
                last7 = l.count { it.atMs > nowMs - t }, prev7 = l.count { it.atMs > nowMs - 2 * t && it.atMs <= nowMs - t }, latestMs = l.maxOf { it.atMs },
            )
        }.sortedWith(compareByDescending<HotspotStat> { it.total }.thenByDescending { it.last7 }.thenByDescending { it.latestMs }.thenBy { it.key })
    }

    /** HOTSPOT: 5 or more reports in 30 days, or one of the top 3 locations by count (with at least 2 reports). */
    fun hotspots(all: List<Occurrence>, nowMs: Long): List<HotspotStat> =
        stats(all, nowMs).filterIndexed { i, s -> s.total >= HotspotRules.HOTSPOT_MIN_REPORTS || (i < HotspotRules.HOTSPOT_TOP_N && s.total >= HotspotRules.HOTSPOT_TOP_MIN_REPORTS) }

    /** The dashboard section: the top 3 hotspots. */
    fun top(all: List<Occurrence>, nowMs: Long): List<HotspotStat> = hotspots(all, nowMs).take(HotspotRules.HOTSPOT_TOP_N)

    fun tags(of: Occurrence, all: List<Occurrence>, hotspotKeys: Set<String>): ReportTags =
        ReportTags(repeat(of, all), of.locationKey != null && of.locationKey in hotspotKeys, of.location)

    /** Tags of every report. */
    fun tags(all: List<Occurrence>, nowMs: Long): Map<String, ReportTags> {
        val hot = hotspots(all, nowMs).map { it.key }.toSet()
        val byPlace = all.groupBy { it.type to it.locationKey }
        return all.associate { o -> o.id to tags(o, byPlace[o.type to o.locationKey].orEmpty(), hot) }
    }

    /** "Water Leak / Flooding" → "water leak". */
    fun shortType(label: String): String = label.substringBefore('/').trim().lowercase()

    fun ordinal(n: Int): String = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

    /** Chip text: "3rd water leak here in 7 days". */
    fun chip(tag: RepeatTag, typeLabel: String): String = "${ordinal(tag.n)} ${shortType(typeLabel)} here in ${tag.days} days"

    /** Card after submit. */
    fun recurring(tag: RepeatTag, typeLabel: String, location: String): String =
        "Recurring hazard: ${tag.n} ${shortType(typeLabel)} reports at $location in ${tag.days} days. Recommend inspection of $location."

    fun trendWord(t: Trend): String = when (t) { Trend.MORE -> "more"; Trend.FEWER -> "fewer"; Trend.SAME -> "same" }
}
