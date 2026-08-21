package fuck.andes.agent.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import fuck.andes.agent.runtime.AgentEvent

/**
 * ACP SessionUpdate → Eta AgentEvent 翻译。
 *
 * 借鉴 OpenOmniBot 的 AcpSessionUpdateMapper（纯函数设计），但输出目标是
 * Eta 的 [AgentEvent] 时间线：文本/思考增量走 AssistantBlock* 系列，
 * 工具调用走 ToolStarted/ToolFinished。
 *
 * [AcpEventTranslator] 是有状态的状态机：它记住当前打开的文本/思考块，
 * 以便把 ACP 的流式 chunk 折叠成 Eta UI 期望的 Start→Delta*→End 结构，
 * 并在 turn 结束时统一收尾。
 */
internal class AcpEventTranslator {

    private data class BlockState(
        val kind: AgentEvent.AssistantBlockKind,
        val index: Int,
        val blockId: String?,
        val content: StringBuilder = StringBuilder(),
        var started: Boolean = false,
    )

    private var textBlock: BlockState? = null
    private var thinkingBlock: BlockState? = null
    private val toolNames = LinkedHashSet<String>()

    /** finish() 关闭块后缓存最终文本/思考，供 accumulatedText() 读取。 */
    private var finishedText: String = ""
    private var finishedReasoning: String = ""

    /** 处理一条 SessionUpdate，产出 0..n 个 UI 事件。 */
    fun onUpdate(update: SessionUpdate, round: Int): List<AgentEvent> = when (update) {
        is SessionUpdate.AgentMessageChunk -> {
            val block = textBlock ?: BlockState(
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = nextIndex(),
                blockId = update.messageId?.value,
            ).also { textBlock = it }
            block.content.append(update.content.textPayload())
            val events = mutableListOf<AgentEvent>()
            if (!block.started) {
                block.started = true
                events += AgentEvent.AssistantBlockStart(
                    round = round,
                    kind = block.kind,
                    index = block.index,
                    blockId = block.blockId,
                )
            }
            events += AgentEvent.AssistantBlockDelta(
                round = round,
                kind = block.kind,
                index = block.index,
                deltaChars = update.content.textPayload().length,
                delta = update.content.textPayload(),
            )
            events
        }

        is SessionUpdate.AgentThoughtChunk -> {
            val block = thinkingBlock ?: BlockState(
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = nextIndex(),
                blockId = update.messageId?.value,
            ).also { thinkingBlock = it }
            val delta = update.content.textPayload()
            block.content.append(delta)
            val events = mutableListOf<AgentEvent>()
            if (!block.started) {
                block.started = true
                events += AgentEvent.AssistantBlockStart(
                    round = round,
                    kind = block.kind,
                    index = block.index,
                    blockId = block.blockId,
                )
            }
            if (delta.isNotEmpty()) {
                events += AgentEvent.AssistantBlockDelta(
                    round = round,
                    kind = block.kind,
                    index = block.index,
                    deltaChars = delta.length,
                    delta = delta,
                )
            }
            events
        }

        is SessionUpdate.ToolCall -> {
            val name = update.title ?: update.kind?.name ?: "tool"
            toolNames += name
            listOf(
                AgentEvent.ToolStarted(
                    round = round,
                    toolCallId = update.toolCallId.value,
                    name = name,
                    argsPreview = update.content.toolContentPreview(),
                )
            )
        }

        is SessionUpdate.ToolCallUpdate -> {
            val name = update.title ?: update.kind?.name ?: "tool"
            toolNames += name
            val finished = update.status == ToolCallStatus.COMPLETED ||
                update.status == ToolCallStatus.FAILED
            if (finished) {
                listOf(
                    AgentEvent.ToolFinished(
                        round = round,
                        toolCallId = update.toolCallId.value,
                        name = name,
                        resultSummary = update.resultSummary(),
                        imageCount = 0,
                        imageBytes = 0,
                    )
                )
            } else {
                emptyList()
            }
        }

        else -> emptyList()
    }

    /** turn 结束：关闭所有打开的块并产出收尾事件。 */
    fun finish(round: Int): List<AgentEvent> {
        val events = mutableListOf<AgentEvent>()
        val text = textBlock
        if (text != null && text.started) {
            events += AgentEvent.AssistantBlockEnd(
                round = round,
                kind = text.kind,
                index = text.index,
                blockId = text.blockId,
                contentChars = text.content.length,
            )
        }
        val thinking = thinkingBlock
        if (thinking != null && thinking.started) {
            events += AgentEvent.AssistantBlockEnd(
                round = round,
                kind = thinking.kind,
                index = thinking.index,
                blockId = thinking.blockId,
                contentChars = thinking.content.length,
            )
        }
        // 缓存最终文本，供 accumulatedText() 在 finish() 后仍能读到（RunResult.content）。
        finishedText = text?.content?.toString().orEmpty()
        finishedReasoning = thinking?.content?.toString().orEmpty()
        textBlock = null
        thinkingBlock = null
        if (text != null || thinking != null) {
            events += AgentEvent.AssistantReceived(
                round = round,
                contentChars = finishedText.length,
                reasoningContent = finishedReasoning,
                toolNames = toolNames.toList(),
            )
        }
        toolNames.clear()
        return events
    }

    /**
     * 当前 turn 累积的 assistant 文本（供 RunResult.content 使用）。
     *
     * ACP agent（尤其 DeepSeek Harness）可能只输出思考（AgentThoughtChunk）而
     * 正文（AgentMessageChunk）为空。此时用思考兜底作为正文，避免界面提示
     * "有思考却无正文"。
     */
    fun accumulatedText(): String = finishedText.ifBlank { finishedReasoning }

    private var blockCounter = 0

    private fun nextIndex(): Int = blockCounter++

    private fun resetIndices() {
        blockCounter = 0
    }

    fun reset() {
        textBlock = null
        thinkingBlock = null
        toolNames.clear()
        finishedText = ""
        finishedReasoning = ""
        resetIndices()
    }
}

private fun ContentBlock.textPayload(): String = when (this) {
    is ContentBlock.Text -> text
    else -> ""
}

private fun List<ToolCallContent>.toolContentPreview(): String {
    val firstText = firstNotNullOfOrNull { content ->
        (content as? ToolCallContent.Content)?.content?.textPayload()
    }
    val raw = firstText ?: return ""
    return raw.take(MAX_PREVIEW_CHARS)
}

private fun SessionUpdate.ToolCallUpdate.resultSummary(): String {
    rawOutput?.toString()?.takeIf { it.isNotBlank() }?.let {
        return it.take(MAX_PREVIEW_CHARS)
    }
    content?.let { blocks ->
        val text = blocks.firstNotNullOfOrNull { block ->
            (block as? ToolCallContent.Content)?.content?.textPayload()
        }
        if (!text.isNullOrBlank()) return text.take(MAX_PREVIEW_CHARS)
    }
    return if (status == ToolCallStatus.FAILED) "failed" else "completed"
}

private const val MAX_PREVIEW_CHARS = 300
