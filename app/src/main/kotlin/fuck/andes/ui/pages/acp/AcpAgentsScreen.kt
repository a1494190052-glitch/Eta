package fuck.andes.ui.pages.acp

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.R
import fuck.andes.agent.acp.AcpAgentProfile
import fuck.andes.agent.acp.AcpProfileStore
import fuck.andes.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import fuck.andes.ui.components.MiuixDialogActions

/** ACP Agent 配置管理页。列表与编辑表单共用 Scaffold，通过 [editTarget] 切换。 */
@Composable
internal fun AcpAgentsScreen(
    context: Context,
    onBack: () -> Unit,
) {
    var profiles by remember { mutableStateOf(AcpProfileStore.candidates(context)) }
    var selectedId by remember { mutableStateOf(AcpProfileStore.selectedId(context)) }
    var enabled by remember { mutableStateOf(AcpProfileStore.isEnabled(context)) }
    // null = 列表模式；OTHER = 新建；否则编辑该 profile
    var editTarget by remember { mutableStateOf<AcpAgentProfile?>(null) }
    var editingNew by remember { mutableStateOf(false) }

    fun refresh() {
        profiles = AcpProfileStore.candidates(context)
        selectedId = AcpProfileStore.selectedId(context)
        enabled = AcpProfileStore.isEnabled(context)
    }

    val editing = editTarget != null || editingNew

    MiuixScaffoldPage(
        title = stringResource(
            if (editingNew) R.string.acp_add_agent
            else if (editing) R.string.acp_edit_agent
            else R.string.route_acp_agents
        ),
        onBack = {
            if (editing) {
                editTarget = null
                editingNew = false
            } else {
                onBack()
            }
        },
    ) {
        if (editing) {
            item(key = "editor") {
                AcpAgentEditorContent(
                    context = context,
                    existing = editTarget,
                    onSaved = {
                        editTarget = null
                        editingNew = false
                        refresh()
                    },
                )
            }
        } else {
            // ── 一键配置环境（Agent 模式环境设置）─────────────────────
            AcpSetupSection(
                context = context,
                onProfilesChanged = { refresh() },
            )
            // ── 列表模式 ──────────────────────────────────────────────
            item(key = "intro") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.acp_page_intro),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.acp_enable_switch),
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.acp_enable_switch_summary),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { value ->
                                AcpProfileStore.setEnabled(context, value)
                                enabled = value
                            },
                        )
                    }
                }
            }

            item(key = "list") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (profiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.acp_empty),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    } else {
                        profiles.forEach { profile ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (profile.isUsable()) {
                                                AcpProfileStore.select(context, profile.id)
                                                selectedId = profile.id
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (profile.id == selectedId) {
                                                LucideR.drawable.lucide_ic_check
                                            } else {
                                                LucideR.drawable.lucide_ic_circle
                                            }
                                        ),
                                        contentDescription = null,
                                        tint = if (profile.id == selectedId) {
                                            Color(0xFF00BD13)
                                        } else {
                                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 12.dp),
                                    ) {
                                        Text(
                                            text = profile.shellDisplayName(),
                                            style = MiuixTheme.textStyles.body1,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            text = profile.command +
                                                profile.arguments.joinToString(" ") { " $it" },
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1,
                                        )
                                        if (profile.description.isNotBlank()) {
                                            Text(
                                                text = profile.description,
                                                style = MiuixTheme.textStyles.body2,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                maxLines = 2,
                                            )
                                        }
                                        if (profile.id == selectedId && enabled) {
                                            Text(
                                                text = stringResource(R.string.acp_in_use),
                                                style = MiuixTheme.textStyles.body2,
                                                color = Color(0xFF00BD13),
                                            )
                                        }
                                    }
                                    Icon(
                                        painter = painterResource(LucideR.drawable.lucide_ic_pencil),
                                        contentDescription = stringResource(R.string.acp_edit_agent),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable {
                                                editTarget = profile
                                                editingNew = false
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "add") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                editTarget = null
                                editingNew = true
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.acp_add_agent),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 全屏编辑表单。 */
@Composable
private fun AcpAgentEditorContent(
    context: Context,
    existing: AcpAgentProfile?,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var command by remember { mutableStateOf(existing?.command.orEmpty()) }
    var arguments by remember { mutableStateOf(existing?.arguments?.joinToString(" ").orEmpty()) }
    var cwd by remember { mutableStateOf(existing?.cwd.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var allowTools by remember { mutableStateOf(existing?.allowToolsWithoutPrompt ?: true) }
    var useRoot by remember { mutableStateOf(existing?.useRoot ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InputField(
                query = name,
                onQueryChange = { name = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.acp_field_name),
                modifier = Modifier.fillMaxWidth(),
            )
            InputField(
                query = command,
                onQueryChange = { command = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.acp_field_command),
                modifier = Modifier.fillMaxWidth(),
            )
            InputField(
                query = arguments,
                onQueryChange = { arguments = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.acp_field_arguments),
                modifier = Modifier.fillMaxWidth(),
            )
            InputField(
                query = cwd,
                onQueryChange = { cwd = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.acp_field_cwd),
                modifier = Modifier.fillMaxWidth(),
            )
            InputField(
                query = description,
                onQueryChange = { description = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.acp_field_description),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.acp_allow_tools),
                        style = MiuixTheme.textStyles.body1,
                    )
                    Text(
                        text = stringResource(R.string.acp_allow_tools_summary),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Switch(
                    checked = allowTools,
                    onCheckedChange = { allowTools = it },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.acp_use_root),
                        style = MiuixTheme.textStyles.body1,
                    )
                    Text(
                        text = stringResource(R.string.acp_use_root_summary),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Switch(
                    checked = useRoot,
                    onCheckedChange = { useRoot = it },
                )
            }
            if (error != null) {
                Text(
                    text = error.orEmpty(),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(modifier = Modifier.size(4.dp))
            Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (command.isBlank()) {
                            error = context.getString(R.string.acp_error_blank_command)
                            return@clickable
                        }
                        val profile = AcpAgentProfile(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.ifBlank { command },
                            description = description.trim(),
                            command = command.trim(),
                            arguments = arguments.split(Regex("\\s+")).filter { it.isNotBlank() },
                            cwd = cwd.trim(),
                            enabled = true,
                            allowToolsWithoutPrompt = allowTools,
                            useRoot = useRoot,
                        )
                        AcpProfileStore.save(context, profile)
                        onSaved()
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.acp_save),
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            if (existing != null) {
                Spacer(modifier = Modifier.size(4.dp))
                Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDeleteConfirm = true }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.acp_delete_agent),
                            style = MiuixTheme.textStyles.body1,
                            color = Color(0xFFEB3B2F),
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.acp_delete_agent),
            summary = stringResource(R.string.acp_confirm_delete),
            onDismissRequest = { showDeleteConfirm = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.acp_delete_agent),
                destructive = true,
                onCancel = { showDeleteConfirm = false },
                onConfirm = {
                    existing?.let { AcpProfileStore.delete(context, it.id) }
                    showDeleteConfirm = false
                    onSaved()
                },
            )
        }
    }
}
