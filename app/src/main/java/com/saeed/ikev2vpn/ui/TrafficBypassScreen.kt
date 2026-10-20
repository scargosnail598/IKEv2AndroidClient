package com.saeed.ikev2vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saeed.ikev2vpn.bypass.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TrafficBypassRoute(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { BypassViewModel.Factory(BypassStore(context), IpdenyCountrySource()) }
    val model: BypassViewModel = viewModel(factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val runtimeIssue by BypassRuntime.issue.collectAsStateWithLifecycle()
    TrafficBypassScreen(state, runtimeIssue, onBack, model::enableDomains, model::updateSuffixes,
        model::enableCountries, model::downloadCountry, model::removeCountry, model::save)
}

@Composable
internal fun TrafficBypassScreen(
    state: BypassUiState, runtimeIssue: String?, onBack: () -> Unit,
    onDomainsEnabled: (Boolean) -> Unit, onSuffixesChanged: (String) -> Unit,
    onCountriesEnabled: (Boolean) -> Unit, onDownloadCountry: (String) -> Unit,
    onRemoveCountry: (String) -> Unit, onSave: () -> Unit,
) {
    var picker by remember { mutableStateOf(false) }
    val enabled = state.initialized && !state.busy
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenHeader("Traffic bypass", onBack = onBack)
            Text("Choose destinations that use your regular internet connection. Rules apply to all profiles on the next connection.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy || !state.initialized) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            runtimeIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Domain suffixes", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(checked = state.config.domainsEnabled, onCheckedChange = onDomainsEnabled, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = "Bypass domain suffixes" })
                    }
                    if (state.config.domainsEnabled) {
                        OutlinedTextField(state.suffixText, onSuffixesChanged, Modifier.fillMaxWidth(), enabled = enabled,
                            label = { Text("Suffixes") }, placeholder = { Text(".ir .ru .co.uk") }, minLines = 2, maxLines = 4,
                            supportingText = { Text("Separate suffixes with spaces or commas. Subdomains are included.") })
                        Text("Matches regular DNS requests seen by the VPN. Private DNS, encrypted DNS and DNS inside apps may prevent matching. Reopen apps after reconnecting.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("A shared IP can also carry other websites; those connections may bypass the VPN too. IP routes normally expire with DNS records, up to 10 minutes. Route refresh failures are shown here; reconnect to clear them.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Country IP ranges", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(checked = state.config.countriesEnabled, onCheckedChange = onCountriesEnabled, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = "Bypass country IP ranges" })
                    }
                    if (state.config.countriesEnabled) {
                        Text("Bypass destinations in a selected country's downloaded IP list, regardless of domain suffix. Lists reflect IP allocations and can differ from a server's physical location.",
                            style = MaterialTheme.typography.bodySmall)
                        state.config.countries.forEach { country ->
                            HorizontalDivider()
                            Text(countryLabel(country.code), style = MaterialTheme.typography.titleSmall)
                            val ipv4 = country.prefixes.count { it.bits == 32 }
                            val ipv6 = country.prefixes.size - ipv4
                            Text("$ipv4 IPv4 · $ipv6 IPv6 ranges\nDownloaded ${DateFormat.getDateInstance().format(Date(country.downloadedAt))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(enabled = enabled, onClick = { onDownloadCountry(country.code) }) { Text("Update list") }
                                TextButton(enabled = enabled, onClick = { onRemoveCountry(country.code) }) { Text("Remove") }
                            }
                        }
                        OutlinedButton(onClick = { picker = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Add country") }
                        if (android.os.Build.VERSION.SDK_INT < 33) Text(
                            "On Android 11–12, large country lists or IPv6 combinations can exceed the route budget and prevent connection. Use fewer countries if that happens.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Lists download from IPdeny only when you add or update a country, then work offline. Downloads use the regular network available to this app. Updates are available once per day.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Reconnect after saving. Android's “Block connections without VPN” setting blocks bypassed traffic. DNS servers retain the profile's VPN path.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        Surface(shadowElevation = 2.dp) {
            Button(onClick = onSave, enabled = enabled && state.dirty,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp)) {
                Text("Save bypass rules")
            }
        }
    }
    if (picker) CountryPicker(state.config.countries.map { it.code }.toSet(), { code -> picker = false; onDownloadCountry(code) }, { picker = false })
}

private fun countryLabel(code: String) = "${Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())} ($code)"

@Composable
private fun CountryPicker(selected: Set<String>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val countries = remember { Locale.getISOCountries().map { it to countryLabel(it) }.sortedBy { it.second } }
    val visible = countries.filter { it.first !in selected && (query.isBlank() || it.second.contains(query, ignoreCase = true)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add country") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Search country or code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(visible, key = { it.first }) { (code, label) ->
                    TextButton(onClick = { onSelect(code) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            }
            if (visible.isEmpty()) Text("No matching countries.")
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
