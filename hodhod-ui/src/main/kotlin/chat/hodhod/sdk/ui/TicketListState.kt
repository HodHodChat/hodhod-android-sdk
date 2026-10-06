package chat.hodhod.sdk.ui

import chat.hodhod.sdk.HodhodRepository
import chat.hodhod.sdk.TicketCounts
import chat.hodhod.sdk.TicketFilter
import chat.hodhod.sdk.TicketList
import chat.hodhod.sdk.TicketSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the «My tickets» list shows: per-filter pages (kept while the visitor opens a thread and comes back) and the counters of all filters. */
internal data class TicketListUi(
    val filter: TicketFilter = TicketFilter.ALL,
    val items: Map<TicketFilter, List<TicketSummary>> = emptyMap(),
    val hasMore: Map<TicketFilter, Boolean> = emptyMap(),
    val pages: Map<TicketFilter, Int> = emptyMap(),
    val counts: TicketCounts? = null,
    /** The default filter was chosen (first successful load). */
    val initialized: Boolean = false,
    /** Nothing to show yet for the current filter: skeleton. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val failed: Boolean = false,
) {
    val current: List<TicketSummary> get() = items[filter].orEmpty()
    val hasTickets: Boolean get() = (counts?.total ?: 0) > 0
    fun countOf(f: TicketFilter): Int? = counts?.let { when (f) { TicketFilter.OPEN -> it.open; TicketFilter.CLOSED -> it.closed; TicketFilter.ALL -> it.total } }
}

/** Loads the filtered, paginated ticket list from the repository. Default filter: open when the visitor has open tickets, else all. */
internal class TicketListController(private val repo: HodhodRepository, private val scope: CoroutineScope, private val pageSize: Int = 20) {
    private val _ui = MutableStateFlow(TicketListUi())
    val ui: StateFlow<TicketListUi> = _ui.asStateFlow()

    /** Called whenever the list screen appears: first load picks the default filter, later calls refresh quietly. */
    fun open() {
        val s = _ui.value
        if (s.initialized) refresh() else scope.launch { initialLoad() }
    }

    /** Retry after an error (skeleton again) / pull-to-refresh. */
    fun refresh() {
        scope.launch {
            val s = _ui.value
            if (!s.initialized) return@launch initialLoad()
            _ui.update { it.copy(refreshing = true, failed = false) }
            fetch(s.filter, 1, replace = true)
        }
    }

    fun selectFilter(f: TicketFilter) {
        if (_ui.value.filter == f) return
        val cached = _ui.value.items.containsKey(f)
        _ui.update { it.copy(filter = f, failed = false, loading = !cached, refreshing = cached) }
        scope.launch { fetch(f, 1, replace = true) }
    }

    fun loadMore() {
        val s = _ui.value
        if (s.loading || s.refreshing || s.loadingMore || s.hasMore[s.filter] != true) return
        _ui.update { it.copy(loadingMore = true) }
        scope.launch { fetch(s.filter, (s.pages[s.filter] ?: 1) + 1, replace = false) }
    }

    /** The summary / activity says something changed: quiet reload of the visible filter. */
    fun onRemoteChange() {
        val s = _ui.value
        if (!s.initialized || s.loading || s.refreshing || s.loadingMore) return
        scope.launch { fetch(s.filter, 1, replace = true) }
    }

    private suspend fun initialLoad() {
        _ui.update { it.copy(loading = true, failed = false) }
        val first = repo.loadTickets(TicketFilter.OPEN, 1, pageSize)
        val list = first.getOrNull() ?: return _ui.update { it.copy(loading = false, refreshing = false, failed = true) }
        if (list.counts.open > 0 || list.counts.total == 0) {
            apply(list, replace = true, chosen = TicketFilter.OPEN.takeIf { list.counts.open > 0 } ?: TicketFilter.ALL)
        } else {
            apply(list, replace = true, chosen = TicketFilter.OPEN) // keep the (empty) open page cached
            _ui.update { it.copy(filter = TicketFilter.ALL, loading = true) }
            fetch(TicketFilter.ALL, 1, replace = true)
        }
    }

    private suspend fun fetch(f: TicketFilter, page: Int, replace: Boolean) {
        val r = repo.loadTickets(f, page, pageSize)
        val list = r.getOrNull()
        if (list == null) {
            _ui.update { it.copy(loading = false, refreshing = false, loadingMore = false, failed = page == 1 && it.items[f] == null) }
            return
        }
        apply(list, replace, chosen = _ui.value.filter.takeIf { _ui.value.initialized } ?: f)
    }

    private fun apply(list: TicketList, replace: Boolean, chosen: TicketFilter) {
        _ui.update { s ->
            val old = s.items[list.filter].orEmpty()
            val merged = if (replace) list.tickets else old + list.tickets.filter { n -> old.none { it.number == n.number } }
            s.copy(
                items = s.items + (list.filter to merged),
                hasMore = s.hasMore + (list.filter to list.hasMore),
                pages = s.pages + (list.filter to list.page),
                counts = list.counts,
                // counters changed: the other filters' cached pages are stale; they reload when selected
                filter = if (s.initialized) s.filter else chosen,
                initialized = true,
                loading = false, refreshing = false, loadingMore = false, failed = false,
            )
        }
    }
}
