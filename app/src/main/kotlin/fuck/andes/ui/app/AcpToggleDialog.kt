package fuck.andes.ui.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import fuck.andes.agent.acp.AcpProfileStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 首页顶栏弹出的"使用 ACP 智能体"开关浮层。
 *
 * 参照 open-omnibot：右上角机器人图标点击展开，顶部一个 Switch 决定是否
 * 将对话交给 ACP 智能体，下方列出可选智能体供点选。开关与选中项均写入
 * [AcpProfileStore]，与 ACP 配置页共享同一份持久化状态。
 */
@Composable
internal fun AcpToggleDialog(
    context: Context,
    show: Boolean,
    onDismiss: () -> Unit,
) {
    var enabled by remember { mutableStateOf(AcpProfileStore.isEnabled(context)) }
    var selectedId by remember { mutableStateOf(AcpProfileStore.selectedId(context)) }
    val profiles = remember { AcpProfileStore.candidates(context) }

    WindowDialog(
        show = show,
        title = stringResource(R.string.acp_toggle_title),
        summary = stringResource(R.string.acp_toggle_summary),
        onDismissRequest = onDismiss,
    ) {
        Column {
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
                        text = stringResource(R.string.acp_toggle_hint),
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

            if (profiles.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                    modifier = Modifier.size(18.dp),
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 10.dp),
                                ) {
                                    Text(
                                        text = profile.shellDisplayName(),
                                        style = MiuixTheme.textStyles.body1,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (profile.description.isNotBlank()) {
                                        Text(
                                            text = profile.description,
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.acp_empty),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}
