package com.bod3.app.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** Clipboard and share intents carry text through a ~1 MB IPC buffer: keep well below it. */
private const val MAX_SHARE_CHARS = 400_000

internal fun limitForSharing(text: String): String =
    if (text.length <= MAX_SHARE_CHARS) {
        text
    } else {
        text.take(MAX_SHARE_CHARS) + "\n… truncated (${text.length - MAX_SHARE_CHARS} more characters)"
    }

/**
 * "Copy", "Share" and (with [onClear]) "Clear" buttons for a log. [text] is only built when a button
 * is tapped. The buttons wrap onto a second line on narrow screens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RawLogActions(
    text: () -> String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClear: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = enabled,
            onClick = {
                clipboard.setText(AnnotatedString(limitForSharing(text())))
                Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
            },
        ) { Text("Copy $label") }
        OutlinedButton(enabled = enabled, onClick = { shareText(context, label, limitForSharing(text())) }) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Text(" Share")
        }
        if (onClear != null) {
            TextButton(enabled = enabled, onClick = onClear) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text(" Clear")
            }
        }
    }
}

private fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "bod3 $subject")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share $subject"))
}
