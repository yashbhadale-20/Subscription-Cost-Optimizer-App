package com.subscriptionoptimizer.app.ui.assistant

import com.subscriptionoptimizer.app.data.model.ChatMessage
import com.subscriptionoptimizer.app.data.model.ChatReply
import com.subscriptionoptimizer.app.data.model.ChatRole
import com.subscriptionoptimizer.app.data.model.ConversationSummary
import com.subscriptionoptimizer.app.data.model.PolicyEvidence
import com.subscriptionoptimizer.app.data.repository.ChatRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {

    private class FakeChat : ChatRepository {
        val pending = mutableListOf<Pair<String, CompletableDeferred<Result<ChatReply>>>>()
        var transcript: Result<List<ChatMessage>> = Result.success(emptyList())

        override suspend fun send(threadId: String, message: String): Result<ChatReply> {
            val reply = CompletableDeferred<Result<ChatReply>>()
            pending += threadId to reply
            return reply.await()
        }

        override suspend fun conversations() = Result.success(emptyList<ConversationSummary>())
        override suspend fun messages(threadId: String) = transcript
        override suspend fun delete(threadId: String) = Result.success(Unit)
    }

    private val evidence = PolicyEvidence(4, "Taxes and fees", "text", "subscription_policy.txt", 0.7, true, cited = true)

    private fun reply(text: String, restored: Boolean = false) =
        Result.success(ChatReply(text, listOf(evidence), listOf(4), emptyList(), restored))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `reply is shown with its evidence`() {
        val chat = FakeChat()
        val vm = AssistantViewModel(chat, clock = { 0L })

        vm.send("Are taxes included?")
        assertTrue(vm.uiState.value.isSending)
        chat.pending.single().second.complete(reply("Taxes may apply [Chunk 4]."))

        val messages = vm.uiState.value.messages
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), messages.map { it.role })
        assertEquals(listOf(evidence), messages.last().evidence)
        assertFalse(vm.uiState.value.isSending)
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun `restored history is announced`() {
        val chat = FakeChat()
        val vm = AssistantViewModel(chat)
        vm.send("What did I say?")
        chat.pending.single().second.complete(reply("You said X.", restored = true))
        assertNotNull(vm.uiState.value.notice)
    }

    @Test
    fun `a late reply never lands in a different conversation`() {
        val chat = FakeChat()
        val vm = AssistantViewModel(chat)
        vm.send("First thread question")
        val firstThread = vm.uiState.value.threadId

        vm.newConversation()
        val secondThread = vm.uiState.value.threadId
        assertFalse(vm.uiState.value.isSending)
        chat.pending.single { it.first == firstThread }.second.complete(reply("Answer for the first thread"))

        assertEquals(secondThread, vm.uiState.value.threadId)
        assertTrue(vm.uiState.value.messages.isEmpty())
    }

    @Test
    fun `opening a saved thread mid-send doesn't leave the sending indicator stuck`() {
        val chat = FakeChat()
        val vm = AssistantViewModel(chat)
        vm.send("hello")
        chat.transcript = Result.success(
            listOf(ChatMessage("server-1", ChatRole.USER, "old question", 0L)),
        )

        vm.openConversation("saved-thread")
        chat.pending.single().second.complete(reply("late reply"))

        val state = vm.uiState.value
        assertEquals("saved-thread", state.threadId)
        assertFalse(state.isSending)
        assertEquals(listOf("old question"), state.messages.map { it.text })
    }

    @Test
    fun `failure keeps the unanswered message and offers retry`() {
        val chat = FakeChat()
        val vm = AssistantViewModel(chat)
        vm.send("hi")
        chat.pending.single().second.complete(Result.failure(RepositoryError.OllamaUnavailable("Ollama is down")))

        val error = vm.uiState.value.error!!
        assertEquals("Message not answered: AI model service unavailable", error.title)
        assertEquals(vm.uiState.value.messages.single().id, error.failedMessageId)

        vm.retry()
        assertEquals(2, chat.pending.size)
        assertEquals("hi", vm.uiState.value.messages.single().text)
    }

    @Test
    fun `messages over the backend limit are not sent`() {
        val chat = FakeChat()
        val vm = AssistantViewModel(chat)
        vm.onInputChange("x".repeat(AssistantUiState.MAX_MESSAGE + 1))
        assertFalse(vm.uiState.value.canSend)
        vm.send()
        assertTrue(chat.pending.isEmpty())
        vm.send("   ")
        assertTrue(chat.pending.isEmpty())
    }
}
