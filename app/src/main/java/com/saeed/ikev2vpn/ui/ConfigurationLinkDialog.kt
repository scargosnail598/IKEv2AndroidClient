package com.saeed.ikev2vpn.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun ConfigurationLinkDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val context = LocalContext.current
    // Configuration links may include a password: never put this text in saved state.
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(value: String) {
        if (value.length > 8192) {
            text = ""
            error = "This configuration link is too large."
        } else {
            text = value
            error = null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import configuration link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste the ikev:// link from your VPN panel.")
                OutlinedTextField(
                    value = text,
                    onValueChange = ::update,
                    label = { Text("Configuration link") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    // Do not resolve clipboard URIs or launch remote content.
                    update(item?.text?.toString().orEmpty())
                }) { Text("Paste from clipboard") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = {
            val input = text
            text = ""
            onImport(input)
        }) { Text("Review profile") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
