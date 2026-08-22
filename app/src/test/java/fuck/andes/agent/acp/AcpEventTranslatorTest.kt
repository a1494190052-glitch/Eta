package fuck.andes.agent.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import fuck.andes.agent.runtime.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpEventTranslatorTest {

    private fun textChunk(text: String) =
        SessionUpdate.AgentMessageChunk(content = ContentBlock.Text(text))

    private fun thoughtChunk(text: String) =
        SessionUpdate.AgentThoughtChunk(content = ContentBlock.Text(text))

    private fun toolCall(id: String, title: String) =
        SessionUpdate.ToolCall(
            toolCallId = com.agentclientprotocol.model.ToolCallId(id),
            title = title,
            content = emptyList(),
        )

    private fun toolCallUpdate(id: String, title: String, status: ToolCallStatus) =
        SessionUpdate.ToolCallUpdate(
            toolCallId = com.agentclientprotocol.model.ToolCallId(id),
            title = title,
            status = status,
            content = emptyList(),
        )

    @Test
    fun `streamed text survives finish`() {
        val t = AcpEventTranslator()
        t.onUpdate(textChunk("hello, "), round = 1)
        t.onUpdate(textChunk("world"), round = 1)

        t.finish(round = 1)

        assertEquals("hello, world", t.accumulatedText())
    }

    @Test
    fun `thinking is used as fallback when text is empty`() {
        val t = AcpEventTranslator()
        t.onUpdate(thoughtChunk("deep reasoning"), round = 1)

        val events = t.finish(round = 1)

        assertEquals("deep reasoning", t.accumulatedText())
        val received = events.filterIsInstance<AgentEvent.AssistantReceived>().single()
        assertEquals("deep reasoning", received.reasoningContent)
    }

    @Test
    fun `text wins over thinking fallback`() {
        val t = AcpEventTranslator()
        t.onUpdate(thoughtChunk("thinking"), round = 1)
        t.onUpdate(textChunk("visible answer"), round = 1)

        t.finish(round = 1)

        assertEquals("visible answer", t.accumulatedText())
    }

    @Test
    fun `turn with no assistant output yields empty accumulated text`() {
        val t = AcpEventTranslator()

        val events = t.finish(round = 1)

        assertEquals("", t.accumulatedText())
        assertTrue(events.none { it is AgentEvent.AssistantReceived })
    }

    @Test
    fun `text blocks are folded as start then delta then end`() {
        val t = AcpEventTranslator()
        t.onUpdate(textChunk("hi"), round = 1)

        val events = t.finish(round = 1)

        assertTrue(events.any { it is AgentEvent.AssistantBlockStart })
        assertTrue(events.any { it is AgentEvent.AssistantBlockDelta })
        assertTrue(events.any { it is AgentEvent.AssistantBlockEnd })
    }

    @Test
    fun `tool call completion emits finished event`() {
        val t = AcpEventTranslator()
        t.onUpdate(toolCall("t1", "search"), round = 1)

        val events = t.onUpdate(
            toolCallUpdate("t1", "search", ToolCallStatus.COMPLETED),
            round = 1,
        )

        assertTrue(events.any { it is AgentEvent.ToolFinished })
        assertTrue(t.finish(round = 1).any { it is AgentEvent.AssistantReceived })
    }

    @Test
    fun `reset clears cached text`() {
        val t = AcpEventTranslator()
        t.onUpdate(textChunk("hi"), round = 1)
        t.finish(round = 1)
        assertEquals("hi", t.accumulatedText())

        t.reset()

        assertEquals("", t.accumulatedText())
    }

    @Test
    fun `empty text chunk does not emit an empty block`() {
        val t = AcpEventTranslator()

        val events = t.onUpdate(
            SessionUpdate.AgentMessageChunk(content = ContentBlock.Text("")),
            round = 1,
        )

        assertTrue(events.filterIsInstance<AgentEvent.AssistantBlockStart>().isEmpty())
        assertEquals("", t.accumulatedText())
    }
}
