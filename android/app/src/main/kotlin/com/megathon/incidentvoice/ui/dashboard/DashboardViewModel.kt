package com.megathon.incidentvoice.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megathon.incidentvoice.core.HotspotStat
import com.megathon.incidentvoice.data.DashboardCounts
import com.megathon.incidentvoice.data.DashboardFilter
import com.megathon.incidentvoice.data.IncidentListItem
import com.megathon.incidentvoice.data.IncidentRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

enum class DatePreset(val label: String, val millis: Long?) {
    TODAY("Today", 24L * 3600_000), D7("7 days", 7L * 24 * 3600_000), D30("30 days", 30L * 24 * 3600_000), ALL("All", null)
}

data class DashboardUi(
    val query: String = "", val types: Set<String> = emptySet(), val severities: Set<String> = emptySet(),
    val preset: DatePreset = DatePreset.ALL,
    /** A hotspot was tapped: only the reports at that place are listed. */
    val locationKey: String? = null, val locationLabel: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DashboardViewModel(private val repo: IncidentRepository, private val now: () -> Long = { System.currentTimeMillis() }) : ViewModel() {
    val ui = MutableStateFlow(DashboardUi())
    val counts: StateFlow<DashboardCounts> = repo.observeCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardCounts())

    /** "This is where things happen": the top hotspot locations, counted by rules on the local database. */
    val hotspots: StateFlow<List<HotspotStat>> = repo.observeHotspots().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Debounce only the text (250 ms, PRD §23.1); filter changes apply immediately. */
    val items: StateFlow<List<IncidentListItem>?> = ui
        .map { it }
        .let { f ->
            combine(f.map { it.query }.debounce(250).onStart { emit("") }.distinctUntilChanged(), f) { _, u -> u }
        }
        .flatMapLatest { u ->
            val from = u.preset.millis?.let { now() - it }
            val filter = DashboardFilter(types = u.types, severities = u.severities, fromMs = from, locationKey = u.locationKey)
            repo.search(u.query, filter)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setQuery(q: String) = ui.update { it.copy(query = q) }
    fun toggleType(t: String) = ui.update { it.copy(types = it.types.toggle(t)) }
    fun toggleSeverity(s: String) = ui.update { it.copy(severities = it.severities.toggle(s)) }
    fun setPreset(p: DatePreset) = ui.update { it.copy(preset = p) }
    /** Tapping a hotspot filters the list to it; tapping it again shows everything. */
    fun toggleLocation(h: HotspotStat) = ui.update { if (it.locationKey == h.key) it.copy(locationKey = null, locationLabel = null) else it.copy(locationKey = h.key, locationLabel = h.location) }
    fun clearLocation() = ui.update { it.copy(locationKey = null, locationLabel = null) }
    fun clearFilters() = ui.update { DashboardUi(query = it.query) }

    private fun Set<String>.toggle(x: String) = if (x in this) this - x else this + x
}
