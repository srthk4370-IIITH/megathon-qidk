package com.megathon.incidentvoice.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val ist: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

fun fmtDateTime(ms: Long): String = SimpleDateFormat("dd MMM, HH:mm", Locale.ENGLISH).apply { timeZone = ist }.format(Date(ms))
fun fmtTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.ENGLISH).apply { timeZone = ist }.format(Date(ms))

fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - ms) / 1000
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86400 -> "${d / 3600} h ago"
        d < 7 * 86400 -> "${d / 86400} d ago"
        else -> fmtDateTime(ms)
    }
}
