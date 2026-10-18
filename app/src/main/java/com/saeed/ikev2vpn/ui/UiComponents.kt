package com.saeed.ikev2vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.saeed.ikev2vpn.data.AppRoutingConfig
import com.saeed.ikev2vpn.data.AppRoutingMode

@Composable
internal fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) IconButton(onClick = onBack, enabled = backEnabled) { Icon(VpnIcons.Back, "Back") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun IconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier.size(44.dp), shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
    }
}

@Composable
internal fun ActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(VpnIcons.Chevron, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun AppRoutingConfig.summary(): String = when (mode) {
    AppRoutingMode.ALL -> "All apps"
    AppRoutingMode.ONLY_SELECTED -> "${packages.size} selected apps"
    AppRoutingMode.EXCLUDE_SELECTED -> "${packages.size} apps bypass VPN"
}
