package com.megathon.incidentvoice.quick

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.megathon.incidentvoice.MainActivity

/**
 * Extra 2, Level A — one-tap launch (flag `features.quick_launch`): a Quick Settings tile, an app shortcut and a
 * home-screen widget. All three send the same intent; MainActivity opens the conversation with the mic listening.
 */
object QuickLaunch {
    const val ACTION_REPORT = "com.megathon.incidentvoice.REPORT"

    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java).setAction(ACTION_REPORT)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Tile and widget are switched off with the flag so they do not linger in the system pickers. */
    fun applyFlag(context: Context, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (cls in listOf(ReportTileService::class.java, ReportWidgetReceiver::class.java)) {
            runCatching { context.packageManager.setComponentEnabledSetting(ComponentName(context, cls), state, PackageManager.DONT_KILL_APP) }
        }
    }
}

class ReportTileService : TileService() {
    override fun onClick() {
        val i = QuickLaunch.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+: the Intent overload is no longer allowed; a PendingIntent must be used
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }
}

class ReportWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Column(
                modifier = GlanceModifier.fillMaxSize().background(Color(0xFFC62828)).cornerRadius(24.dp).padding(8.dp)
                    .clickable(actionStartActivity(QuickLaunch.intent(context))),
                horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("REPORT", style = TextStyle(color = ColorProvider(Color.White), fontSize = 22.sp, fontWeight = FontWeight.Bold))
                Text("incident", style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp))
            }
        }
    }
}

class ReportWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReportWidget()
}
