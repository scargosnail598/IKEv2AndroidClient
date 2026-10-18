package com.saeed.ikev2vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.saeed.ikev2vpn.validation.ProfileField
import com.saeed.ikev2vpn.validation.ProfileValidator

@Composable
fun SetupScreen(
    state: VpnUiState,
    onProfileNameChanged: (String) -> Unit, onServerChanged: (String) -> Unit, onUsernameChanged: (String) -> Unit,
    onAppRoutingModeChanged: (com.saeed.ikev2vpn.data.AppRoutingMode) -> Unit, onToggleApplication: (String) -> Unit,
    onImportIkevProfile: () -> Unit, onImportLink: () -> Unit, onScanQr: () -> Unit, onImportQrImage: () -> Unit,
    onReplaceImportedProfile: (String?) -> Unit, onImportCertificate: () -> Unit,
    onProvision: (String) -> Unit, onBack: () -> Unit, onDismissError: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var manual by rememberSaveable(state.draftRevision) { mutableStateOf(state.isEditingProfile) }
    var connectionExpanded by rememberSaveable(state.draftRevision) { mutableStateOf(state.isEditingProfile) }
    var appsExpanded by rememberSaveable(state.draftRevision) { mutableStateOf(false) }
    var certificateExpanded by rememberSaveable(state.draftRevision) { mutableStateOf(false) }
    var methodsOpen by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var chooseReplacement by remember { mutableStateOf(false) }
    val ready = manual || state.importedProfileInfo != null || state.isEditingProfile || state.serverAddress.isNotBlank()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.draftRevision) { password = ""; confirmSave = false; chooseReplacement = false; methodsOpen = false }
    LaunchedEffect(state.fieldErrors) {
        if (state.fieldErrors.keys.any { it in setOf(ProfileField.SERVER_ADDRESS, ProfileField.USERNAME) }) connectionExpanded = true
        if (ProfileField.CERTIFICATE in state.fieldErrors) certificateExpanded = true
        if (ProfileField.APPLICATIONS in state.fieldErrors) appsExpanded = true
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { password = ""; confirmSave = false; methodsOpen = false }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); password = "" }
    }
    fun save() {
        val value = password
        password = ""; confirmSave = false
        onProvision(value)
    }
    fun reviewSave() {
        val validation = ProfileValidator.validate(state.profileName, state.serverAddress, state.username,
            password.ifEmpty { if (state.hasImportedPassword || state.canReuseSavedPassword) "saved-credential" else "" },
            state.certificateInfo != null || state.usesPublicCertificateTrust, state.appRouting)
        if (!validation.isValid) { onProvision(password); return }
        val trusted = state.profiles.any { it.certificate?.info?.sha256Fingerprint == state.certificateInfo?.sha256Fingerprint }
        if (state.importReplacementProfileId != null || (state.certificateInfo != null && (!trusted || state.importedProfileInfo != null))) confirmSave = true
        else save()
    }
    if (confirmSave) AlertDialog(
        onDismissRequest = { confirmSave = false }, title = { Text(if (state.importReplacementProfileId != null) "Replace this profile?" else "Trust this profile?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow("Server", state.serverAddress); DetailRow("Account", state.username)
                if (state.certificateInfo != null) {
                    Text("Only continue if you trust the sender. Compare this CA fingerprint with a trusted source.", style = MaterialTheme.typography.bodyMedium)
                    Text(state.certificateInfo.sha256Fingerprint, style = MaterialTheme.typography.bodySmall)
                }
                if (state.importReplacementProfileId != null) Text("The saved configuration and password will be replaced.")
            }
        }, confirmButton = { TextButton(onClick = ::save) { Text("Confirm and save") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Cancel") } },
    )
    if (chooseReplacement) AlertDialog(onDismissRequest = { chooseReplacement = false }, title = { Text("Replace a saved profile") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            state.profiles.filter { it.config.serverAddress == state.serverAddress && it.config.username == state.username }.forEach { profile ->
                TextButton(onClick = { onReplaceImportedProfile(profile.config.id); chooseReplacement = false }) { Text(profile.config.profileName) }
            }
        } }, confirmButton = { TextButton(onClick = { chooseReplacement = false }) { Text("Cancel") } })
    if (methodsOpen) AlertDialog(onDismissRequest = { methodsOpen = false }, title = { Text("Import a profile") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ImportMethods(onImportLink = { methodsOpen = false; onImportLink() }, onScanQr = { methodsOpen = false; onScanQr() },
                onImportFile = { methodsOpen = false; onImportIkevProfile() }, onImportImage = { methodsOpen = false; onImportQrImage() }, enabled = !state.isBusy)
        } }, confirmButton = { TextButton(onClick = { methodsOpen = false }) { Text("Cancel") } })

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ScreenHeader(when { state.isEditingProfile -> "Edit profile"; ready -> "Review profile"; else -> "Add profile" },
                onBack = onBack, backEnabled = !state.isBusy) {
                if (ready && !state.isEditingProfile) TextButton(onClick = { methodsOpen = true }, enabled = !state.isBusy) { Text("Import another") }
            }
            state.error?.let { ErrorCard(it, onDismissError) }
            if (!state.platformSupported) Text("The VPN engine is unavailable on this device.", color = MaterialTheme.colorScheme.error)
            if (!ready) {
                Text("Add the configuration provided by your VPN administrator.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ImportMethods(onImportLink, onScanQr, onImportIkevProfile, onImportQrImage, !state.isBusy)
                TextButton(onClick = { manual = true; connectionExpanded = true }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Set up manually")
                }
                if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(state.profileName, onProfileNameChanged, Modifier.fillMaxWidth(), label = { Text("Profile name") },
                    singleLine = true, enabled = !state.isBusy, isError = ProfileField.PROFILE_NAME in state.fieldErrors,
                    supportingText = state.fieldErrors[ProfileField.PROFILE_NAME]?.let { { Text(it) } })
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Connection", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { connectionExpanded = !connectionExpanded }, enabled = !state.isBusy) {
                                Text(if (connectionExpanded) "Done" else "Edit")
                            }
                        }
                        if (connectionExpanded) {
                            OutlinedTextField(state.serverAddress, onServerChanged, Modifier.fillMaxWidth(), label = { Text("Server address") },
                                placeholder = { Text("vpn.example.com") }, singleLine = true, enabled = !state.isBusy,
                                isError = ProfileField.SERVER_ADDRESS in state.fieldErrors,
                                supportingText = state.fieldErrors[ProfileField.SERVER_ADDRESS]?.let { { Text(it) } })
                            OutlinedTextField(state.username, onUsernameChanged, Modifier.fillMaxWidth(), label = { Text("Username") },
                                singleLine = true, enabled = !state.isBusy, isError = ProfileField.USERNAME in state.fieldErrors,
                                supportingText = state.fieldErrors[ProfileField.USERNAME]?.let { { Text(it) } })
                        } else {
                            DetailRow("Server", state.serverAddress.ifBlank { "Not set" })
                            DetailRow("Account", state.username.ifBlank { "Not set" })
                        }
                    }
                }
                PasswordSection(password, { password = it }, state.fieldErrors[ProfileField.PASSWORD], state.importedProfileInfo != null,
                    state.hasImportedPassword, state.canReuseSavedPassword, enabled = !state.isBusy)
                Disclosure("Apps using VPN", state.appRouting.summary(), VpnIcons.Apps, appsExpanded, { appsExpanded = !appsExpanded }) {
                    AppRoutingSection(state, onAppRoutingModeChanged, onToggleApplication)
                }
                Disclosure("Server certificate", if (state.usesPublicCertificateTrust) "Public certificate authorities" else state.certificateInfo?.displayName ?: "Certificate required",
                    VpnIcons.Lock, certificateExpanded, { certificateExpanded = !certificateExpanded }) { CertificateSection(state, onImportCertificate) }
                state.importedProfileInfo?.let {
                    if (state.profiles.any { p -> p.config.serverAddress == state.serverAddress && p.config.username == state.username }) {
                        Text(if (state.importReplacementProfileId == null) "A profile for this account already exists. This import will be saved as a new profile." else "This import will replace the selected saved profile.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { if (state.importReplacementProfileId == null) chooseReplacement = true else onReplaceImportedProfile(null) }) {
                            Text(if (state.importReplacementProfileId == null) "Replace existing profile instead" else "Save as new instead")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (ready) Surface(shadowElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = ::reviewSave, enabled = state.platformSupported && !state.isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (state.isBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(if (state.isEditingProfile && state.editingProfileId != state.selectedProfileId) "Save and select profile" else "Save profile")
                }
            }
        }
    }
}

@Composable
private fun ImportMethods(onImportLink: () -> Unit, onScanQr: () -> Unit, onImportFile: () -> Unit, onImportImage: () -> Unit, enabled: Boolean) {
    ActionRow(VpnIcons.Link, "Paste a link", "Import an ikev:// configuration link", onImportLink, enabled)
    ActionRow(VpnIcons.Qr, "Scan a QR code", "Use your camera", onScanQr, enabled)
    ActionRow(VpnIcons.File, "Choose a file", "Import an .ikev profile", onImportFile, enabled)
    ActionRow(VpnIcons.Photo, "Choose a QR image", "Use a saved image or screenshot", onImportImage, enabled)
}

@Composable
private fun Disclosure(title: String, subtitle: String, icon: ImageVector, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(onClick = onToggle, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "Hide" else "Edit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (expanded) content()
    }
}

@Composable
private fun PasswordSection(
    password: String,
    onPasswordChanged: (String) -> Unit,
    error: String?,
    importedProfileReady: Boolean,
    hasImportedPassword: Boolean,
    canReuseSavedPassword: Boolean,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Password", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (canReuseSavedPassword) {
                "Leave empty to keep your saved password, or enter a new one."
            } else if (hasImportedPassword) {
                "Password included. Leave empty to use it, or enter a replacement."
            } else if (importedProfileReady) {
                "Enter the VPN password provided with this profile."
            } else {
                "Your password is stored securely on this device."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChanged,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("VPN Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
        )
    }
}

@Composable
private fun CertificateSection(
    state: VpnUiState,
    onImportCertificate: () -> Unit,
) {
    val certificate = state.certificateInfo
    var detailsExpanded by rememberSaveable(certificate?.sha256Fingerprint) {
        mutableStateOf(false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CA Certificate", style = MaterialTheme.typography.titleMedium)
        if (certificate == null) {
            Text(
                if (state.usesPublicCertificateTrust) {
                    "This profile uses Android's trusted public certificate authorities."
                } else {
                    "A CA certificate is required to verify the VPN server."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onImportCertificate,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy,
            ) {
                Text(if (state.usesPublicCertificateTrust) "Import Private CA Instead" else "Import CA Certificate")
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = certificate.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (certificate.isCertificateAuthority) {
                                    "✓ Valid CA certificate"
                                } else {
                                    "Not marked as a CA certificate"
                                },
                                color = if (certificate.isCertificateAuthority) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                            Text(if (detailsExpanded) "Hide details" else "View details")
                        }
                    }

                    if (detailsExpanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        CertificateDetail("Subject", certificate.subject)
                        CertificateDetail("Issuer", certificate.issuer)
                        CertificateDetail("SHA-256", certificate.sha256Fingerprint)
                    }

                    TextButton(
                        onClick = onImportCertificate,
                        modifier = Modifier.align(Alignment.End),
                        enabled = !state.isBusy,
                    ) {
                        Text("Replace CA Certificate")
                    }
                }
            }
        }

        state.fieldErrors[ProfileField.CERTIFICATE]?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CertificateDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
