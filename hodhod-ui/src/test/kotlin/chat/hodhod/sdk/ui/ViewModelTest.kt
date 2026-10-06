package chat.hodhod.sdk.ui

import androidx.lifecycle.SavedStateHandle
import chat.hodhod.sdk.ConversationState
import chat.hodhod.sdk.FakeHodhodRepository
import chat.hodhod.sdk.TicketFilter
import chat.hodhod.sdk.TicketStatus
import chat.hodhod.sdk.TicketSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun navigationSurvivesRecreationThroughSavedState() {
        val saved = SavedStateHandle()
        val vm = HodhodChatViewModel(FakeHodhodRepository(), saved)
        assertEquals(Route.HOME, vm.route.value)
        vm.go(Route.CHAT); vm.setShowTickets(true); vm.setTicketView(TicketView.THREAD, 42)
        val restored = HodhodChatViewModel(FakeHodhodRepository(), saved) // process death: same SavedStateHandle contents
        assertEquals(Route.CHAT, restored.route.value)
        assertTrue(restored.showTickets.value)
        assertEquals(TicketView.THREAD, restored.ticketView.value)
        assertEquals(42, restored.ticketNumber.value)
    }

    @Test fun sendTextClearsDraftAndAddsOptimisticMessage() = runTest(dispatcher) {
        val repo = FakeHodhodRepository()
        val vm = HodhodChatViewModel(repo, SavedStateHandle())
        vm.draft = "hello"
        vm.sendText("  hello  ")
        advanceUntilIdle()
        assertEquals("", vm.draft)
        assertEquals("hello", repo.messages.value.last().content)
    }

    @Test fun blankTextIsIgnored() = runTest(dispatcher) {
        val repo = FakeHodhodRepository()
        val vm = HodhodChatViewModel(repo, SavedStateHandle())
        vm.sendText("   "); advanceUntilIdle()
        assertTrue(repo.messages.value.isEmpty())
    }

    @Test fun startNewConversationResetsToHome() = runTest(dispatcher) {
        val repo = FakeHodhodRepository()
        val vm = HodhodChatViewModel(repo, SavedStateHandle())
        vm.sendText("x"); advanceUntilIdle(); vm.go(Route.CHAT)
        repo.simulateResolved()
        vm.startNewConversation()
        assertEquals(Route.HOME, vm.route.value)
        assertTrue(repo.conversation.value is ConversationState.None)
        assertTrue(repo.messages.value.isEmpty())
    }

    private fun ticket(n: Int, status: TicketStatus, source: String? = "widget") =
        TicketSummary(n, "S$n", status, null, 100L + n, 200L + n, null, 300 + n, source = source)

    @Test fun ticketListDefaultsToOpenWhenThereAreOpenTickets() = runTest(dispatcher) {
        val repo = FakeHodhodRepository().apply { setTickets(listOf(ticket(3, TicketStatus.OPEN), ticket(2, TicketStatus.CLOSED), ticket(1, TicketStatus.RESOLVED))) }
        val vm = HodhodChatViewModel(repo, SavedStateHandle())
        vm.ticketList.open(); advanceUntilIdle()
        val ui = vm.ticketList.ui.value
        assertEquals(TicketFilter.OPEN, ui.filter)
        assertEquals(listOf(3), ui.current.map { it.number })
        assertEquals(1, ui.countOf(TicketFilter.OPEN)); assertEquals(2, ui.countOf(TicketFilter.CLOSED)); assertEquals(3, ui.countOf(TicketFilter.ALL))
        vm.ticketList.selectFilter(TicketFilter.CLOSED); advanceUntilIdle()
        assertEquals(listOf(2, 1), vm.ticketList.ui.value.current.map { it.number })
        vm.ticketList.selectFilter(TicketFilter.ALL); advanceUntilIdle()
        assertEquals(3, vm.ticketList.ui.value.current.size)
    }

    @Test fun ticketListDefaultsToAllWhenNothingIsOpen() = runTest(dispatcher) {
        val repo = FakeHodhodRepository().apply { setTickets(listOf(ticket(2, TicketStatus.CLOSED), ticket(1, TicketStatus.RESOLVED))) }
        val vm = HodhodChatViewModel(repo, SavedStateHandle())
        vm.ticketList.open(); advanceUntilIdle()
        assertEquals(TicketFilter.ALL, vm.ticketList.ui.value.filter)
        assertEquals(2, vm.ticketList.ui.value.current.size)
        vm.ticketList.selectFilter(TicketFilter.OPEN); advanceUntilIdle()
        assertTrue(vm.ticketList.ui.value.current.isEmpty()) // per-filter empty state
    }

    @Test fun ticketListPaginatesAndKeepsErrorRecoverable() = runTest(dispatcher) {
        val repo = FakeHodhodRepository().apply { setTickets((1..45).map { ticket(100 - it, TicketStatus.OPEN) }) }
        val vm = HodhodChatViewModel(repo, SavedStateHandle())
        repo.failNextTicketLoad = true
        vm.ticketList.open(); advanceUntilIdle()
        assertTrue(vm.ticketList.ui.value.failed)
        vm.ticketList.refresh(); advanceUntilIdle()
        assertTrue(!vm.ticketList.ui.value.failed)
        assertEquals(20, vm.ticketList.ui.value.current.size); assertTrue(vm.ticketList.ui.value.hasMore[TicketFilter.OPEN] == true)
        vm.ticketList.loadMore(); advanceUntilIdle()
        vm.ticketList.loadMore(); advanceUntilIdle()
        assertEquals(45, vm.ticketList.ui.value.current.size)
        assertEquals(false, vm.ticketList.ui.value.hasMore[TicketFilter.OPEN])
    }

    @Test fun leavingTheTicketsPanelResetsTheSubView() {
        val vm = HodhodChatViewModel(FakeHodhodRepository(), SavedStateHandle())
        vm.setShowTickets(true); vm.setTicketView(TicketView.THREAD, 5)
        vm.setShowTickets(false)
        assertEquals(null, vm.ticketView.value)
    }
}
