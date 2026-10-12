package com.saeed.ikev2vpn.data

import org.json.JSONArray
import org.json.JSONObject
import androidx.datastore.preferences.core.mutablePreferencesOf
import android.content.Context
import android.util.Base64
import android.util.AtomicFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saeed.ikev2vpn.certificate.CertificateLoader
import com.saeed.ikev2vpn.certificate.LoadedCertificate
import com.saeed.ikev2vpn.diagnostics.DiagnosticSanitizer
import com.saeed.ikev2vpn.vpn.VpnLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val Context.profileDataStore by preferencesDataStore(name = "vpn_profile")

data class StoredProfile(
    val config: VpnProfileConfig,
    val certificate: LoadedCertificate?,
    val status: ProvisioningStatus,
)

data class StoredDiagnostics(
    val lastError: String? = null,
    val lastTechnicalError: String? = null,
    val lastErrorTimestamp: Long? = null,
    val repositoryError: String? = null,
    val connectionEvents: List<VpnLogEntry> = emptyList(),
)

data class RepositorySnapshot(
    val profile: StoredProfile? = null,
    val pendingProfile: StoredProfile? = null,
    val diagnostics: StoredDiagnostics = StoredDiagnostics(),
    val profiles: List<StoredProfile> = emptyList(),
)

interface ProfileRepository {
    val snapshots: Flow<RepositorySnapshot>

    suspend fun saveProfile(
        config: VpnProfileConfig,
        certificate: LoadedCertificate?,
        status: ProvisioningStatus,
    )

    suspend fun stageProfile(config: VpnProfileConfig, certificate: LoadedCertificate?)
    suspend fun commitStagedProfile()
    suspend fun abortStagedProfile(platformProfileReplaced: Boolean)
    suspend fun setProvisioningStatus(status: ProvisioningStatus)
    suspend fun recordError(userMessage: String, technicalMessage: String)
    suspend fun clearError()
    suspend fun recordConnectionEvents(events: List<VpnLogEntry>) = Unit
    suspend fun clearConnectionEvents() = Unit
}

class DataStoreProfileRepository(
    context: Context,
    private val certificateLoader: CertificateLoader,
) : ProfileRepository {
    private val applicationContext = context.applicationContext

    override val snapshots: Flow<RepositorySnapshot> = applicationContext.profileDataStore.data
        .map(::toSnapshot)
        .catch { exception ->
            if (exception is IOException) {
                emit(
                    RepositorySnapshot(
                        diagnostics = StoredDiagnostics(
                            repositoryError = "The stored VPN profile could not be read.",
                        ),
                    ),
                )
            } else {
                throw exception
            }
        }
        .flowOn(Dispatchers.IO)

    override suspend fun saveProfile(
        config: VpnProfileConfig,
        certificate: LoadedCertificate?,
        status: ProvisioningStatus,
    ) {
        certificate?.let { saveCertificate(it) }
        applicationContext.profileDataStore.edit { preferences ->
            archiveCurrent(preferences, config.id)
            preferences[PROFILE_ID] = config.id
            preferences[PROFILE_NAME] = config.profileName
            preferences[SERVER_ADDRESS] = config.serverAddress
            preferences[USERNAME] = config.username
            setCertificateFingerprint(preferences, CERTIFICATE_FINGERPRINT, certificate)
            preferences[STATUS] = status.name
            removePendingValues(preferences)
        }
        removeUnreferencedCertificatesForFingerprints(setOfNotNull(certificate?.info?.sha256Fingerprint))
    }

    override suspend fun stageProfile(
        config: VpnProfileConfig,
        certificate: LoadedCertificate?,
    ) {
        certificate?.let { saveCertificate(it) }
        val activeFingerprints = mutableSetOf<String>()
        certificate?.info?.sha256Fingerprint?.let(activeFingerprints::add)
        applicationContext.profileDataStore.edit { preferences ->
            preferences[CERTIFICATE_FINGERPRINT]?.let(activeFingerprints::add)
            preferences[PENDING_PROFILE_ID] = config.id
            preferences[PENDING_PROFILE_NAME] = config.profileName
            preferences[PENDING_SERVER_ADDRESS] = config.serverAddress
            preferences[PENDING_USERNAME] = config.username
            setCertificateFingerprint(preferences, PENDING_CERTIFICATE_FINGERPRINT, certificate)
        }
        removeUnreferencedCertificatesForFingerprints(activeFingerprints)
    }

    override suspend fun commitStagedProfile() {
        var activeFingerprint: String? = null
        applicationContext.profileDataStore.edit { preferences ->
            val pending = pendingValues(preferences)
                ?: throw IOException("No staged VPN profile is available to commit.")
            archiveCurrent(preferences, pending.config.id)
            preferences[PROFILE_ID] = pending.config.id
            preferences[PROFILE_NAME] = pending.config.profileName
            preferences[SERVER_ADDRESS] = pending.config.serverAddress
            preferences[USERNAME] = pending.config.username
            setCertificateFingerprint(preferences, CERTIFICATE_FINGERPRINT, pending.certificateFingerprint)
            preferences[STATUS] = ProvisioningStatus.PROVISIONED.name
            activeFingerprint = pending.certificateFingerprint
            removePendingValues(preferences)
        }
        removeUnreferencedCertificatesForFingerprints(setOfNotNull(activeFingerprint))
    }

    override suspend fun abortStagedProfile(platformProfileReplaced: Boolean) {
        val activeFingerprints = mutableSetOf<String>()
        applicationContext.profileDataStore.edit { preferences ->
            val pending = pendingValues(preferences)
                ?: throw IOException("No staged VPN profile is available to discard.")
            val hasCommittedProfile = committedValues(preferences) != null
            if (hasCommittedProfile) {
                preferences[CERTIFICATE_FINGERPRINT]?.let(activeFingerprints::add)
                if (platformProfileReplaced) {
                    preferences[STATUS] = ProvisioningStatus.NEEDS_REPROVISION.name
                }
            } else {
                preferences[PROFILE_ID] = pending.config.id
                preferences[PROFILE_NAME] = pending.config.profileName
                preferences[SERVER_ADDRESS] = pending.config.serverAddress
                preferences[USERNAME] = pending.config.username
                setCertificateFingerprint(preferences, CERTIFICATE_FINGERPRINT, pending.certificateFingerprint)
                preferences[STATUS] = ProvisioningStatus.DRAFT.name
                pending.certificateFingerprint?.let(activeFingerprints::add)
            }
            removePendingValues(preferences)
        }
        removeUnreferencedCertificatesForFingerprints(activeFingerprints)
    }

    override suspend fun setProvisioningStatus(status: ProvisioningStatus) {
        applicationContext.profileDataStore.edit { preferences ->
            if (preferences[PROFILE_NAME] == null) {
                throw IOException("No stored VPN profile is available to update.")
            }
            preferences[STATUS] = status.name
        }
    }

    override suspend fun recordError(userMessage: String, technicalMessage: String) {
        applicationContext.profileDataStore.edit { preferences ->
            preferences[LAST_ERROR] = DiagnosticSanitizer.sanitize(userMessage)
            preferences[LAST_TECHNICAL_ERROR] = DiagnosticSanitizer.sanitize(technicalMessage)
            preferences[LAST_ERROR_TIMESTAMP] = System.currentTimeMillis()
        }
    }

    override suspend fun clearError() {
        applicationContext.profileDataStore.edit { preferences ->
            preferences.remove(LAST_ERROR)
            preferences.remove(LAST_TECHNICAL_ERROR)
            preferences.remove(LAST_ERROR_TIMESTAMP)
        }
    }

    override suspend fun recordConnectionEvents(events: List<VpnLogEntry>) {
        if (events.isEmpty()) return
        applicationContext.profileDataStore.edit { preferences ->
            val stored = decodeConnectionEvents(preferences[CONNECTION_EVENTS].orEmpty()).toMutableList()
            events.forEach { event ->
                val sanitized = event.copy(
                    message = DiagnosticSanitizer.sanitize(event.message),
                    detail = event.detail?.let(DiagnosticSanitizer::sanitize),
                )
                val existingIndex = stored.indexOfFirst {
                    it.timestamp == sanitized.timestamp && it.message == sanitized.message
                }
                if (existingIndex >= 0) {
                    val existing = stored[existingIndex]
                    if (!sanitized.detail.isNullOrBlank()) {
                        stored[existingIndex] = existing.copy(detail = sanitized.detail)
                    }
                } else {
                    stored += sanitized
                }
            }
            preferences[CONNECTION_EVENTS] = encodeConnectionEvents(stored.takeLast(MAX_STORED_CONNECTION_EVENTS))
        }
    }

    override suspend fun clearConnectionEvents() {
        applicationContext.profileDataStore.edit { preferences -> preferences.remove(CONNECTION_EVENTS) }
    }

    private fun archivedProfiles(preferences: Preferences): List<Preferences> {
        val entries = JSONArray(preferences[ARCHIVED_PROFILES] ?: "[]")
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            mutablePreferencesOf().apply {
                listOf(PROFILE_ID, PROFILE_NAME, SERVER_ADDRESS, USERNAME, CERTIFICATE_FINGERPRINT).forEach { key ->
                    if (entry.has(key.name)) this[key] = entry.getString(key.name)
                }
                this[STATUS] = ProvisioningStatus.NEEDS_REPROVISION.name
            }
        }
    }

    private fun archiveCurrent(preferences: androidx.datastore.preferences.core.MutablePreferences, targetId: String) {
        val archived = archivedProfiles(preferences).filter { it[PROFILE_ID] != targetId }.toMutableList()
        val currentId = preferences[PROFILE_ID] ?: "legacy"
        if (committedValues(preferences) != null && currentId != targetId) {
            archived.removeAll { it[PROFILE_ID] == currentId }
            archived += preferences.toMutablePreferences().apply { this[PROFILE_ID] = currentId }
        }
        val entries = JSONArray()
        archived.forEach { profile ->
            entries.put(JSONObject().apply {
                listOf(PROFILE_ID, PROFILE_NAME, SERVER_ADDRESS, USERNAME, CERTIFICATE_FINGERPRINT).forEach { key ->
                    profile[key]?.let { put(key.name, it) }
                }
            })
        }
        preferences[ARCHIVED_PROFILES] = entries.toString()
    }

    private fun toSnapshot(preferences: Preferences): RepositorySnapshot {
        var diagnostics = StoredDiagnostics(
            lastError = preferences[LAST_ERROR],
            lastTechnicalError = preferences[LAST_TECHNICAL_ERROR],
            lastErrorTimestamp = preferences[LAST_ERROR_TIMESTAMP],
            connectionEvents = decodeConnectionEvents(preferences[CONNECTION_EVENTS].orEmpty()),
        )
        val committed = loadProfile(preferences, pending = false)
        val pending = loadProfile(preferences, pending = true)
        val repositoryErrors = listOfNotNull(committed.error, pending.error)
        if (repositoryErrors.isNotEmpty()) {
            diagnostics = diagnostics.copy(repositoryError = repositoryErrors.joinToString(" "))
        }
        return RepositorySnapshot(
            profile = committed.profile,
            pendingProfile = pending.profile,
            diagnostics = diagnostics,
            profiles = listOfNotNull(committed.profile) + archivedProfiles(preferences).mapNotNull { loadProfile(it, false).profile },
        )
    }

    private fun loadProfile(preferences: Preferences, pending: Boolean): ProfileLoadResult {
        val values = if (pending) pendingValues(preferences) else committedValues(preferences)
        if (values == null) {
            val anyValuePresent = if (pending) {
                listOf(
                    preferences[PENDING_PROFILE_NAME],
                    preferences[PENDING_SERVER_ADDRESS],
                    preferences[PENDING_USERNAME],
                    preferences[PENDING_CERTIFICATE_FINGERPRINT],
                ).any { it != null }
            } else {
                listOf(
                    preferences[PROFILE_NAME],
                    preferences[SERVER_ADDRESS],
                    preferences[USERNAME],
                    preferences[CERTIFICATE_FINGERPRINT],
                ).any { it != null }
            }
            return if (anyValuePresent) {
                ProfileLoadResult(error = "The ${if (pending) "staged" else "stored"} VPN profile is incomplete.")
            } else {
                ProfileLoadResult()
            }
        }

        val certificate = values.certificateFingerprint?.let { fingerprint ->
            val certificateFile = certificateFileForFingerprint(fingerprint)
                ?: return ProfileLoadResult(error = "The ${if (pending) "staged" else "stored"} CA certificate fingerprint is invalid.")
            val loaded = try {
                certificateLoader.load(certificateFile.readFully())
            } catch (_: Exception) {
                return ProfileLoadResult(error = "The ${if (pending) "staged" else "stored"} CA certificate could not be validated.")
            }
            if (loaded.info.sha256Fingerprint != fingerprint) {
                return ProfileLoadResult(error = "The ${if (pending) "staged" else "stored"} CA certificate fingerprint does not match the profile.")
            }
            loaded
        }
        val status = if (pending) {
            ProvisioningStatus.PENDING_CONSENT
        } else {
            runCatching {
                ProvisioningStatus.valueOf(preferences[STATUS] ?: ProvisioningStatus.DRAFT.name)
            }.getOrDefault(ProvisioningStatus.DRAFT)
        }
        return ProfileLoadResult(
            profile = StoredProfile(values.config.copy(id = preferences[if (pending) PENDING_PROFILE_ID else PROFILE_ID] ?: "legacy"), certificate, status),
        )
    }

    private fun committedValues(preferences: Preferences): ProfileValues? = profileValues(
        preferences[PROFILE_NAME],
        preferences[SERVER_ADDRESS],
        preferences[USERNAME],
        preferences[CERTIFICATE_FINGERPRINT],
        preferences[PROFILE_ID] ?: "legacy",
    )

    private fun pendingValues(preferences: Preferences): ProfileValues? = profileValues(
        preferences[PENDING_PROFILE_NAME],
        preferences[PENDING_SERVER_ADDRESS],
        preferences[PENDING_USERNAME],
        preferences[PENDING_CERTIFICATE_FINGERPRINT],
        preferences[PENDING_PROFILE_ID] ?: "legacy",
    )

    private fun profileValues(
        profileName: String?,
        serverAddress: String?,
        username: String?,
        certificateFingerprint: String?,
        id: String,
    ): ProfileValues? {
        if (profileName == null || serverAddress == null || username == null) {
            return null
        }
        return ProfileValues(
            config = VpnProfileConfig(profileName, serverAddress, username, id),
            certificateFingerprint = certificateFingerprint,
        )
    }

    private fun removePendingValues(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences.remove(PENDING_PROFILE_ID)
        preferences.remove(PENDING_PROFILE_NAME)
        preferences.remove(PENDING_SERVER_ADDRESS)
        preferences.remove(PENDING_USERNAME)
        preferences.remove(PENDING_CERTIFICATE_FINGERPRINT)
    }

    private fun certificateFileForFingerprint(fingerprint: String): AtomicFile? {
        val hexFingerprint = fingerprint.replace(":", "")
        if (!HEX_SHA_256.matches(hexFingerprint)) return null
        return AtomicFile(File(applicationContext.filesDir, "$CERTIFICATE_FILE_PREFIX$hexFingerprint.der"))
    }

    private fun writeCertificate(certificateFile: AtomicFile, bytes: ByteArray) {
        val output = certificateFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            certificateFile.finishWrite(output)
        } catch (exception: Exception) {
            certificateFile.failWrite(output)
            throw exception
        }
    }

    private suspend fun saveCertificate(certificate: LoadedCertificate) {
        val file = certificateFileForFingerprint(certificate.info.sha256Fingerprint)
            ?: throw IOException("The CA certificate fingerprint is invalid.")
        withContext(Dispatchers.IO) { writeCertificate(file, certificate.derBytes) }
    }

    private fun setCertificateFingerprint(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        certificate: LoadedCertificate?,
    ) {
        if (certificate == null) preferences.remove(key)
        else preferences[key] = certificate.info.sha256Fingerprint
    }

    private fun setCertificateFingerprint(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        fingerprint: String?,
    ) {
        if (fingerprint == null) preferences.remove(key) else preferences[key] = fingerprint
    }

    private suspend fun removeUnreferencedCertificatesForFingerprints(fingerprints: Set<String>) {
        val archivedFingerprints = archivedProfiles(applicationContext.profileDataStore.data.first()).mapNotNull { it[CERTIFICATE_FINGERPRINT] }
        val activeFiles = (fingerprints + archivedFingerprints).mapNotNull(::certificateFileForFingerprint)
            .map { it.baseFile }
            .toSet()
        withContext(Dispatchers.IO) {
            applicationContext.filesDir.listFiles().orEmpty()
                .filter { file ->
                    file !in activeFiles &&
                        file.name.startsWith(CERTIFICATE_FILE_PREFIX) &&
                        file.name.endsWith(".der")
                }
                .forEach { file -> runCatching { file.delete() } }
        }
    }

    private data class ProfileValues(
        val config: VpnProfileConfig,
        val certificateFingerprint: String?,
    )

    private fun encodeConnectionEvents(events: List<VpnLogEntry>): String = events.joinToString("\n") { event ->
        val message = Base64.encodeToString(event.message.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        val detail = event.detail?.let {
            Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        }.orEmpty()
        "${event.timestamp}|$message|$detail"
    }

    private fun decodeConnectionEvents(value: String): List<VpnLogEntry> = value.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            val timestamp = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val message = parts.getOrNull(1)?.decodeBase64Utf8() ?: return@mapNotNull null
            val detail = parts.getOrNull(2)?.takeIf(String::isNotEmpty)?.decodeBase64Utf8()
            VpnLogEntry(timestamp, message, detail)
        }
        .toList()
        .takeLast(MAX_STORED_CONNECTION_EVENTS)

    private fun String.decodeBase64Utf8(): String? = runCatching {
        String(Base64.decode(this, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
    }.getOrNull()

    private data class ProfileLoadResult(
        val profile: StoredProfile? = null,
        val error: String? = null,
    )

    private companion object {
        const val CERTIFICATE_FILE_PREFIX = "vpn_ca_"
        const val MAX_STORED_CONNECTION_EVENTS = 200
        val HEX_SHA_256 = Regex("[0-9A-F]{64}")
        val ARCHIVED_PROFILES = stringPreferencesKey("archived_profiles")
        val PROFILE_ID = stringPreferencesKey("profile_id")
        val PENDING_PROFILE_ID = stringPreferencesKey("pending_profile_id")
        val PROFILE_NAME = stringPreferencesKey("profile_name")
        val SERVER_ADDRESS = stringPreferencesKey("server_address")
        val USERNAME = stringPreferencesKey("username")
        val CERTIFICATE_FINGERPRINT = stringPreferencesKey("certificate_fingerprint")
        val STATUS = stringPreferencesKey("provisioning_status")
        val PENDING_PROFILE_NAME = stringPreferencesKey("pending_profile_name")
        val PENDING_SERVER_ADDRESS = stringPreferencesKey("pending_server_address")
        val PENDING_USERNAME = stringPreferencesKey("pending_username")
        val PENDING_CERTIFICATE_FINGERPRINT = stringPreferencesKey("pending_certificate_fingerprint")
        val LAST_ERROR = stringPreferencesKey("last_error")
        val LAST_TECHNICAL_ERROR = stringPreferencesKey("last_technical_error")
        val LAST_ERROR_TIMESTAMP = longPreferencesKey("last_error_timestamp")
        val CONNECTION_EVENTS = stringPreferencesKey("connection_events")
    }
}
