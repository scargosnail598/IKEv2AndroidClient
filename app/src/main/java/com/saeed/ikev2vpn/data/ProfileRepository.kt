package com.saeed.ikev2vpn.data

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saeed.ikev2vpn.certificate.CertificateLoader
import com.saeed.ikev2vpn.certificate.LoadedCertificate
import com.saeed.ikev2vpn.diagnostics.DiagnosticSanitizer
import kotlinx.coroutines.Dispatchers
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
    val certificate: LoadedCertificate,
    val status: ProvisioningStatus,
)

data class StoredDiagnostics(
    val lastError: String? = null,
    val lastTechnicalError: String? = null,
    val lastErrorTimestamp: Long? = null,
    val repositoryError: String? = null,
)

data class RepositorySnapshot(
    val profile: StoredProfile? = null,
    val pendingProfile: StoredProfile? = null,
    val diagnostics: StoredDiagnostics = StoredDiagnostics(),
)

interface ProfileRepository {
    val snapshots: Flow<RepositorySnapshot>

    suspend fun saveProfile(
        config: VpnProfileConfig,
        certificate: LoadedCertificate,
        status: ProvisioningStatus,
    )

    suspend fun stageProfile(config: VpnProfileConfig, certificate: LoadedCertificate)
    suspend fun commitStagedProfile()
    suspend fun abortStagedProfile(platformProfileReplaced: Boolean)
    suspend fun setProvisioningStatus(status: ProvisioningStatus)
    suspend fun recordError(userMessage: String, technicalMessage: String)
    suspend fun clearError()
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
        certificate: LoadedCertificate,
        status: ProvisioningStatus,
    ) {
        val certificateFile = certificateFileForFingerprint(certificate.info.sha256Fingerprint)
            ?: throw IOException("The CA certificate fingerprint is invalid.")
        withContext(Dispatchers.IO) {
            writeCertificate(certificateFile, certificate.derBytes)
        }
        applicationContext.profileDataStore.edit { preferences ->
            preferences[PROFILE_NAME] = config.profileName
            preferences[SERVER_ADDRESS] = config.serverAddress
            preferences[USERNAME] = config.username
            preferences[CERTIFICATE_FINGERPRINT] = certificate.info.sha256Fingerprint
            preferences[STATUS] = status.name
            removePendingValues(preferences)
        }
        removeUnreferencedCertificatesForFingerprints(setOf(certificate.info.sha256Fingerprint))
    }

    override suspend fun stageProfile(
        config: VpnProfileConfig,
        certificate: LoadedCertificate,
    ) {
        val certificateFile = certificateFileForFingerprint(certificate.info.sha256Fingerprint)
            ?: throw IOException("The CA certificate fingerprint is invalid.")
        withContext(Dispatchers.IO) {
            writeCertificate(certificateFile, certificate.derBytes)
        }
        val activeFingerprints = mutableSetOf(certificate.info.sha256Fingerprint)
        applicationContext.profileDataStore.edit { preferences ->
            preferences[CERTIFICATE_FINGERPRINT]?.let(activeFingerprints::add)
            preferences[PENDING_PROFILE_NAME] = config.profileName
            preferences[PENDING_SERVER_ADDRESS] = config.serverAddress
            preferences[PENDING_USERNAME] = config.username
            preferences[PENDING_CERTIFICATE_FINGERPRINT] = certificate.info.sha256Fingerprint
        }
        removeUnreferencedCertificatesForFingerprints(activeFingerprints)
    }

    override suspend fun commitStagedProfile() {
        var activeFingerprint: String? = null
        applicationContext.profileDataStore.edit { preferences ->
            val pending = pendingValues(preferences)
                ?: throw IOException("No staged VPN profile is available to commit.")
            preferences[PROFILE_NAME] = pending.config.profileName
            preferences[SERVER_ADDRESS] = pending.config.serverAddress
            preferences[USERNAME] = pending.config.username
            preferences[CERTIFICATE_FINGERPRINT] = pending.certificateFingerprint
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
                preferences[PROFILE_NAME] = pending.config.profileName
                preferences[SERVER_ADDRESS] = pending.config.serverAddress
                preferences[USERNAME] = pending.config.username
                preferences[CERTIFICATE_FINGERPRINT] = pending.certificateFingerprint
                preferences[STATUS] = ProvisioningStatus.DRAFT.name
                activeFingerprints += pending.certificateFingerprint
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

    private fun toSnapshot(preferences: Preferences): RepositorySnapshot {
        var diagnostics = StoredDiagnostics(
            lastError = preferences[LAST_ERROR],
            lastTechnicalError = preferences[LAST_TECHNICAL_ERROR],
            lastErrorTimestamp = preferences[LAST_ERROR_TIMESTAMP],
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

        val certificateFile = certificateFileForFingerprint(values.certificateFingerprint)
            ?: return ProfileLoadResult(error = "The ${if (pending) "staged" else "stored"} CA certificate fingerprint is invalid.")
        val certificate = try {
            certificateLoader.load(certificateFile.readFully())
        } catch (_: Exception) {
            return ProfileLoadResult(error = "The ${if (pending) "staged" else "stored"} CA certificate could not be validated.")
        }
        if (certificate.info.sha256Fingerprint != values.certificateFingerprint) {
            return ProfileLoadResult(
                error = "The ${if (pending) "staged" else "stored"} CA certificate fingerprint does not match the profile.",
            )
        }
        val status = if (pending) {
            ProvisioningStatus.PENDING_CONSENT
        } else {
            runCatching {
                ProvisioningStatus.valueOf(preferences[STATUS] ?: ProvisioningStatus.DRAFT.name)
            }.getOrDefault(ProvisioningStatus.DRAFT)
        }
        return ProfileLoadResult(
            profile = StoredProfile(values.config, certificate, status),
        )
    }

    private fun committedValues(preferences: Preferences): ProfileValues? = profileValues(
        preferences[PROFILE_NAME],
        preferences[SERVER_ADDRESS],
        preferences[USERNAME],
        preferences[CERTIFICATE_FINGERPRINT],
    )

    private fun pendingValues(preferences: Preferences): ProfileValues? = profileValues(
        preferences[PENDING_PROFILE_NAME],
        preferences[PENDING_SERVER_ADDRESS],
        preferences[PENDING_USERNAME],
        preferences[PENDING_CERTIFICATE_FINGERPRINT],
    )

    private fun profileValues(
        profileName: String?,
        serverAddress: String?,
        username: String?,
        certificateFingerprint: String?,
    ): ProfileValues? {
        if (profileName == null || serverAddress == null || username == null || certificateFingerprint == null) {
            return null
        }
        return ProfileValues(
            config = VpnProfileConfig(profileName, serverAddress, username),
            certificateFingerprint = certificateFingerprint,
        )
    }

    private fun removePendingValues(preferences: androidx.datastore.preferences.core.MutablePreferences) {
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

    private suspend fun removeUnreferencedCertificatesForFingerprints(fingerprints: Set<String>) {
        val activeFiles = fingerprints.mapNotNull(::certificateFileForFingerprint)
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
        val certificateFingerprint: String,
    )

    private data class ProfileLoadResult(
        val profile: StoredProfile? = null,
        val error: String? = null,
    )

    private companion object {
        const val CERTIFICATE_FILE_PREFIX = "vpn_ca_"
        val HEX_SHA_256 = Regex("[0-9A-F]{64}")
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
    }
}
