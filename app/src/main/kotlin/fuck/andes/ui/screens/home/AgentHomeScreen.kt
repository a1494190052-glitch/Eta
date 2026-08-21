package fuck.andes.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import fuck.andes.ui.components.AgentChatBody
import fuck.andes.ui.components.chatConversationCompositionKey
import fuck.andes.ui.model.AgentChatHomeUiState
import fuck.andes.ui.model.AgentHomeAction
import fuck.andes.ui.model.AgentModelPickerUiState

/**
 * AgentChatHome：首屏为聊天主舞台。
 *
 * 顶部入口统一由 [fuck.andes.ui.app.AgentAppShell] 提供，
 * 本 Screen 只负责消息流、Run trace、工具摘要和底部输入框。
 */
@Composable
internal fun AgentHomeScreen(
    state: AgentChatHomeUiState,
    modelPickerState: AgentModelPickerUiState,
    conversationKey: String?,
    onAction: (AgentHomeAction) -> Unit,
    isDrawerOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    key(chatConversationCompositionKey(conversationKey)) {
        AgentChatBody(
            messages = state.messages,
            modelPickerState = modelPickerState,
            input = state.input,
            isStreaming = state.isStreaming,
            reasoningEffort = state.reasoningEffort,
            availableReasoningEfforts = state.availableReasoningEfforts,
            pendingImages = state.pendingImages,
            pendingFileReferences = state.pendingFileReferences,
            messageEdit = state.messageEdit,
            onReasoningEffortChange = { onAction(AgentHomeAction.ReasoningEffortChanged(it)) },
            onModelSelected = { onAction(AgentHomeAction.ModelSelected(it)) },
            onSubmit = { text -> onAction(AgentHomeAction.SubmitMessage(text)) },
            onStop = { onAction(AgentHomeAction.StopRun) },
            onAttachImage = { uri -> onAction(AgentHomeAction.ImageAttached(uri)) },
            onRemoveImage = { id -> onAction(AgentHomeAction.RemoveImage(id)) },
            onAttachFiles = { uris -> onAction(AgentHomeAction.FilesAttached(uris)) },
            onAttachFolder = { uri -> onAction(AgentHomeAction.FolderAttached(uri)) },
            onAttachFilePath = { path -> onAction(AgentHomeAction.FilePathAttached(path)) },
            onRemoveFileReference = { id -> onAction(AgentHomeAction.RemoveFileReference(id)) },
            onEditMessage = { id -> onAction(AgentHomeAction.EditMessage(id)) },
            onCancelMessageEdit = { onAction(AgentHomeAction.CancelMessageEdit) },
            onDeleteMessage = { id -> onAction(AgentHomeAction.DeleteMessage(id)) },
            onRegenerateMessage = { id -> onAction(AgentHomeAction.RegenerateMessage(id)) },
            onSuggestionClick = { prompt ->
                onAction(AgentHomeAction.SubmitMessage(prompt))
            },
            onRunTraceClick = { onAction(AgentHomeAction.ExpandRunTrace) },
            onOpenBrowser = { onAction(AgentHomeAction.OpenBrowser) },
            acpAgentEnabled = state.acpAgentEnabled,
            acpAgentName = state.acpAgentName,
            onToggleAcpAgent = { enabled -> onAction(AgentHomeAction.ToggleAcpAgent(enabled)) },
            isDrawerOpen = isDrawerOpen,
            modifier = modifier,
        )
    }
}
