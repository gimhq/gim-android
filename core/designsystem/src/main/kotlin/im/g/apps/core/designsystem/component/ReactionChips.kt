package im.g.apps.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.R
import im.g.apps.core.designsystem.theme.GimTheme

@Immutable
data class ReactionChipModel(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionChips(
    reactions: List<ReactionChipModel>,
    onClick: (ReactionChipModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.forEach { reaction ->
            val counted = pluralStringResource(R.plurals.gim_reaction, reaction.count, reaction.emoji, reaction.count)
            val description = if (reaction.mine) stringResource(R.string.gim_reaction_mine, counted) else counted
            FilterChip(
                selected = reaction.mine,
                onClick = { onClick(reaction) },
                label = { Text("${reaction.emoji} ${reaction.count}") },
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

@GimPreviews
@Composable
private fun ReactionChipsPreview() {
    GimTheme {
        ReactionChips(
            reactions = listOf(
                ReactionChipModel("👍", 3, mine = true),
                ReactionChipModel("⚓", 1, mine = false),
            ),
            onClick = {},
            modifier = Modifier.padding(8.dp),
        )
    }
}
