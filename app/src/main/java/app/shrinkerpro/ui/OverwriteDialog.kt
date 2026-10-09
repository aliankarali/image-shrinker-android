package app.shrinkerpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.shrinkerpro.R
import app.shrinkerpro.core.OverwriteAnswer
import app.shrinkerpro.core.OverwriteCategory

private const val LISTED_NAME_LIMIT = 5

/**
 * Asked before anything is overwritten. Skip declines only the files listed;
 * the rest of the batch still runs. Dismissing the dialog counts as Skip.
 */
@Composable
fun OverwriteDialog(prompt: OverwritePrompt, onAnswer: (OverwriteAnswer) -> Unit) {
    val res = LocalResources.current
    val count = prompt.names.size
    val original = prompt.category == OverwriteCategory.ORIGINAL

    val title = when {
        prompt.folderName != null && original ->
            res.getQuantityString(R.plurals.overwrite_title_originals_in, count, count, prompt.folderName)
        prompt.folderName != null ->
            res.getQuantityString(R.plurals.overwrite_title_existing_in, count, count, prompt.folderName)
        original -> res.getQuantityString(R.plurals.overwrite_title_originals, count, count)
        else -> res.getQuantityString(R.plurals.overwrite_title_existing, count, count)
    }

    val listed = prompt.names.take(LISTED_NAME_LIMIT).joinToString(stringResource(R.string.filename_separator))
    val rest = count - LISTED_NAME_LIMIT
    val names = if (rest > 0) res.getQuantityString(R.plurals.filenames_and_more, rest, listed, rest) else listed
    val body = buildList {
        add(
            res.getQuantityString(
                if (original) R.plurals.overwrite_message_originals else R.plurals.overwrite_message_existing,
                count, count, names,
            ),
        )
        if (prompt.unaffectedCount > 0) {
            add(res.getQuantityString(R.plurals.overwrite_unaffected, prompt.unaffectedCount, prompt.unaffectedCount))
        }
    }.joinToString("\n\n")

    AlertDialog(
        onDismissRequest = { onAnswer(OverwriteAnswer.SKIP) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onAnswer(OverwriteAnswer.SKIP) }) {
                    Text(stringResource(if (count == 1) R.string.action_skip_this else R.string.action_skip_these))
                }
                TextButton(onClick = { onAnswer(OverwriteAnswer.KEEP_BOTH) }) {
                    Text(stringResource(R.string.action_keep_both))
                }
                TextButton(
                    onClick = { onAnswer(OverwriteAnswer.REPLACE) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.action_replace))
                }
            }
        },
    )
}
