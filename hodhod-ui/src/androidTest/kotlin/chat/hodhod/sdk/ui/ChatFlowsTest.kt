package chat.hodhod.sdk.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import chat.hodhod.sdk.ContactMode
import chat.hodhod.sdk.DarkMode
import chat.hodhod.sdk.FakeHodhodRepository
import chat.hodhod.sdk.HodhodConfig
import org.junit.Rule
import org.junit.Test

/** Key flows of the chat UI against the in-memory [FakeHodhodRepository] (English locale forced so texts are stable). */
class ChatFlowsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val cfg = HodhodConfig("http://localhost", "token", locale = "en", darkMode = DarkMode.LIGHT)

    private fun show(repo: FakeHodhodRepository) = rule.setContent { HodhodChatContent(repo, cfg, onClose = {}) }

    @Test fun startChat_sendMessage_and_receiveAgentReply() {
        val repo = FakeHodhodRepository(FakeHodhodRepository.sampleConfig(ContactMode.CHAT))
        show(repo)
        rule.onNodeWithText("Start Conversation").performClick()
        rule.onNodeWithText("Type your message").performTextInput("hello there")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("hello there").fetchSemanticsNodes().isNotEmpty() }
        repo.simulateAgentMessage("Hi, how can I help?")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Hi, how can I help?", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun failedMessage_canBeRetried() {
        val repo = FakeHodhodRepository(FakeHodhodRepository.sampleConfig(ContactMode.CHAT)).apply { failNextSend = true }
        show(repo)
        rule.onNodeWithText("Start Conversation").performClick()
        rule.onNodeWithText("Type your message").performTextInput("will fail")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Failed to send", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Failed to send", substring = true).performClick()
    }

    @Test fun bothMode_offersTicketAndValidatesForm() {
        val repo = FakeHodhodRepository(FakeHodhodRepository.sampleConfig(ContactMode.BOTH))
        show(repo)
        rule.onNodeWithText("Start Conversation").assertIsDisplayed()
        rule.onAllNodesWithText("Submit a ticket")[0].assertIsDisplayed()
        rule.onNode(hasClickAction() and hasText("Submit a ticket")).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Subject", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Submit ticket").performScrollTo().performClick()
        rule.onNodeWithText("Please enter a subject.").assertExists()
        rule.onNodeWithText("Please describe your request.").assertExists()
    }

    @Test fun ticketMode_createsTicketAndShowsNumber() {
        val repo = FakeHodhodRepository(FakeHodhodRepository.sampleConfig(ContactMode.TICKET))
        show(repo)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Subject", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Broken order")
        fields[1].performTextInput("The package never arrived")
        fields[fields.fetchSemanticsNodes().size - 1].performTextInput("a@b.co")
        rule.onNodeWithText("Submit ticket").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Your ticket was submitted").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun resolvedConversation_showsEndScreen_thenNewConversationReturnsHome() {
        val repo = FakeHodhodRepository(FakeHodhodRepository.sampleConfig(ContactMode.CHAT))
        show(repo)
        rule.onNodeWithText("Start Conversation").performClick()
        rule.onNodeWithText("Type your message").performTextInput("bye")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("bye").fetchSemanticsNodes().isNotEmpty() }
        repo.simulateResolved()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("This conversation has ended.").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Start a new conversation").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Start Conversation").fetchSemanticsNodes().isNotEmpty() }
    }
}
