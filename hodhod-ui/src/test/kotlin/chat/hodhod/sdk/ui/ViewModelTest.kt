package chat.hodhod.sdk.ui

import androidx.lifecycle.SavedStateHandle
import chat.hodhod.sdk.ConversationState
import chat.hodhod.sdk.FakeHodhodRepository
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
}
