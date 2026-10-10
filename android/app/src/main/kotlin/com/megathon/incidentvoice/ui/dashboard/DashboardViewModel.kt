package com.megathon.incidentvoice.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DashboardViewModel(private val repo: IncidentRepository, private val now: () -> Long = { System.currentTimeMillis() }) : ViewModel() {
    val ui = MutableStateFlow(DashboardUi())
    val counts: StateFlow<DashboardCounts> = repo.observeCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardCounts())

    /** Debounce only the text (250 ms, PRD §23.1); filter changes apply immediately. */
    val items: StateFlow<List<IncidentListItem>?> = ui
        .map { it }
        .let { f ->
            combine(f.map { it.query }.debounce(250).onStart { emit("") }.distinctUntilChanged(), f) { _, u -> u }
        }
        .flatMapLatest { u ->
            val from = u.preset.millis?.let { now() - it }
            val filter = DashboardFilter(types = u.types, severities = u.severities, fromMs = from)
            repo.search(u.query, filter)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setQuery(q: String) = ui.update { it.copy(query = q) }
    fun toggleType(t: String) = ui.update { it.copy(types = it.types.toggle(t)) }
    fun toggleSeverity(s: String) = ui.update { it.copy(severities = it.severities.toggle(s)) }
    fun setPreset(p: DatePreset) = ui.update { it.copy(preset = p) }
    fun clearFilters() = ui.update { DashboardUi(query = it.query) }

    private fun Set<String>.toggle(x: String) = if (x in this) this - x else this + x
}
