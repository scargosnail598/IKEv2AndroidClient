package com.saeed.ikev2vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.saeed.ikev2vpn.data.AppTheme
import com.saeed.ikev2vpn.ui.theme.colors
import com.saeed.ikev2vpn.ui.theme.typography

@Composable
internal fun ThemePickerDialog(
    selectedTheme: AppTheme,
    darkMode: Boolean,
    onThemeChanged: (AppTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Colors and fonts. Light or dark appearance stays as selected.",
                    style = MaterialTheme.typography.bodyMedium)
                AppTheme.entries.forEach { theme ->
                    val colors = theme.colors(darkMode)
                    val typography = theme.typography()
                    Surface(shape = MaterialTheme.shapes.small, color = colors.surface, contentColor = colors.onSurface) {
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = theme == selectedTheme,
                                role = Role.RadioButton,
                                onClick = { onThemeChanged(theme) },
                            ).padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(24.dp).background(colors.primary, CircleShape)
                                .border(1.dp, colors.outlineVariant, CircleShape))
                            Column(Modifier.weight(1f)) {
                                Text(theme.title, style = typography.titleSmall)
                                Text(theme.description, style = typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            RadioButton(selected = theme == selectedTheme, onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = colors.primary,
                                    unselectedColor = colors.onSurfaceVariant))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
