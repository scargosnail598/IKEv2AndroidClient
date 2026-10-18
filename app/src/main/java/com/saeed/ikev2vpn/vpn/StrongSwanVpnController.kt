package com.saeed.ikev2vpn.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.saeed.ikev2vpn.data.VpnProfileConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnProfileDataSource
import org.strongswan.android.data.VpnProfileSource
import org.strongswan.android.data.VpnType
import org.strongswan.android.logic.CharonVpnService
import org.strongswan.android.logic.TrustedCertificateManager
import org.strongswan.android.logic.VpnStateService
import org.strongswan.android.security.LocalCertificateStore
import java.io.File
import java.io.RandomAccessFile
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** Controls the userland IKE/IPsec engine shipped by strongSwan. */
class StrongSwanVpnController(context: Context) : VpnPlatformController {
    private val appContext = context.applicationContext
    private val credentialStore = EapCredentialStore(appContext)
    private val mutableState = MutableStateFlow(VpnState())
    private val serviceExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "strongswan-state").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stateService: VpnStateService? = null
    private var logOffset = 0L
    private var initialLogRead = true

    override val state: StateFlow<VpnState> = mutableState.asStateFlow()

    private val stateListener = object : VpnStateService.VpnStateListener {
        override fun stateChanged() = refreshStrongSwanState()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as VpnStateService.LocalBinder).service
            stateService?.unregisterListener(stateListener)
            stateService = service
            service.registerListener(stateListener)
            refreshStrongSwanState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            stateService = null
            refreshStrongSwanState()
        }
    }

    init {
        appContext.bindService(
            Intent(appContext, VpnStateService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        serviceExecutor.scheduleWithFixedDelay({
            pollCharonLog()
            mainHandler.post {
                refreshStrongSwanState()
                refreshTraffic()
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    override fun isPlatformSupported(): Boolean = true

    override fun retainCurrentCredential(profileId: String) {
        credentialStore.load()?.let { credentialStore.save(it, profileId) }
    }

    override fun hasSavedCredential(profileId: String): Boolean = credentialStore.load(profileId) != null

    override fun restoreProfile(config: VpnProfileConfig, serverRootCa: X509Certificate?): VpnResult<ProvisioningAction> {
        return try {
            val password = credentialStore.load(config.id)
                ?: return VpnResult.Failure("Enter the password for this profile.", "The selected profile has no saved credential.")
            provision(config, password, serverRootCa)
        } catch (exception: Exception) {
            fail("The saved profile could not be restored.", exception)
        }
    }

    override fun provision(
        config: VpnProfileConfig,
        password: String,
        serverRootCa: X509Certificate?,
    ): VpnResult<ProvisioningAction> {
        var previousCredential: String? = null
        var credentialUpdated = false
        var previousProfileCredential: String? = null
        return try {
            previousCredential = credentialStore.load()
            previousProfileCredential = credentialStore.load(config.id)
            credentialStore.save(password)
            credentialUpdated = true
            credentialStore.save(password, config.id)
            val profileId = activeProfileId
            val source = VpnProfileSource(appContext).open()
            try {
                val previous = source.getVpnProfile(profileId)
                val profile = previous ?: VpnProfile().apply { setUUID(profileId) }
                val certificateAlias = serverRootCa?.let(::installLocalTrustAnchor)

                profile.name = config.profileName
                profile.gateway = config.serverAddress
                profile.remoteId = config.serverAddress
                profile.vpnType = VpnType.IKEV2_EAP
                profile.username = config.username
                profile.password = null // The password is supplied only to the service at connect time.
                profile.port = 500
                profile.flags = 0
                profile.certificateAlias = certificateAlias
                profile.isReadOnly = false
                profile.applyAppRouting(config.appRouting)

                val stored = if (previous == null) {
                    source.insertProfile(profile)
                } else {
                    if (source.updateVpnProfile(profile)) profile else null
                }
                if (stored == null) {
                    throw IllegalStateException("The strongSwan profile data source did not insert or update the profile.")
                }
            } finally {
                source.close()
            }

            val consent = VpnService.prepare(appContext)
            VpnResult.Success(consent?.let(ProvisioningAction::ConsentRequired) ?: ProvisioningAction.Complete)
        } catch (exception: Exception) {
            val rollbackFailure = if (credentialUpdated) {
                runCatching {
                    previousCredential?.let { credentialStore.save(it) } ?: credentialStore.clear()
                    previousProfileCredential?.let { credentialStore.save(it, config.id) } ?: credentialStore.clear(config.id)
                }.exceptionOrNull()
            } else {
                null
            }
            VpnResult.Failure(
                "The strongSwan VPN profile could not be prepared.",
                buildString {
                    append(exception.message ?: exception.javaClass.simpleName)
                    rollbackFailure?.let {
                        append(" Credential rollback also failed: ")
                        append(it.message ?: it.javaClass.simpleName)
                    }
                },
            )
        }
    }

    override fun connect(): VpnResult<String?> {
        return try {
            if (VpnService.prepare(appContext) != null) {
                return VpnResult.Failure(
                    "Android VPN permission is required before connecting.",
                    "VpnService.prepare() returned a consent intent after profile setup.",
                )
            }
            val current = readStoredProfile()
                ?: return VpnResult.Failure("Save the VPN profile before connecting.", "No strongSwan profile exists.")
            val eapPassword = credentialStore.load()
                ?: return VpnResult.Failure(
                    "Edit and save the VPN profile once to store its EAP password.",
                    "No encrypted EAP credential exists for the strongSwan profile.",
                )

            mutableState.update {
                it.copy(
                    connectionState = ConnectionState.CONNECTING,
                    evidence = StateEvidence.LOCAL_REQUEST,
                    confirmed = false,
                    detail = "Starting the strongSwan IKEv2 service.",
                    error = null,
                )
            }
            val extras = Bundle().apply {
                putString(VpnProfileDataSource.KEY_UUID, current.uuid.toString())
                putString(VpnProfileDataSource.KEY_PASSWORD, eapPassword)
            }
            val service = stateService
            if (service != null) {
                service.connect(extras, true)
            } else {
                val intent = Intent(appContext, CharonVpnService::class.java).putExtras(extras)
                ContextCompat.startForegroundService(appContext, intent)
            }
            VpnResult.Success(stateService?.connectionID?.toString())
        } catch (exception: Exception) {
            fail("strongSwan could not start the VPN tunnel.", exception)
        }
    }

    override fun disconnect(): VpnResult<Unit> {
        return try {
            mutableState.update {
                it.copy(
                    connectionState = ConnectionState.DISCONNECTING,
                    evidence = StateEvidence.LOCAL_REQUEST,
                    confirmed = false,
                    detail = "Stopping the strongSwan IKEv2 service.",
                    error = null,
                )
            }
            val service = stateService
            if (service != null) {
                service.disconnect()
            } else {
                appContext.startService(
                    Intent(appContext, CharonVpnService::class.java).setAction(CharonVpnService.DISCONNECT_ACTION),
                )
            }
            VpnResult.Success(Unit)
        } catch (exception: Exception) {
            fail("strongSwan could not stop the VPN tunnel.", exception)
        }
    }

    override fun deleteProvisionedProfile(): VpnResult<Unit> {
        return try {
            val source = VpnProfileSource(appContext).open()
            try {
                readStoredProfile(source)?.let(source::deleteVpnProfile)
            } finally {
                source.close()
            }
            credentialStore.clear()
            VpnResult.Success(Unit)
        } catch (exception: Exception) {
            fail("The strongSwan profile could not be removed.", exception)
        }
    }

    override fun forgetProfileCredential(profileId: String) = credentialStore.clear(profileId)

    override fun renameProvisionedProfile(name: String): VpnResult<Unit> {
        return try {
            val source = VpnProfileSource(appContext).open()
            try {
                val profile = readStoredProfile(source) ?: error("The VPN profile is unavailable.")
                profile.name = name
                check(source.updateVpnProfile(profile)) { "The VPN profile name could not be updated." }
            } finally {
                source.close()
            }
            VpnResult.Success(Unit)
        } catch (exception: Exception) {
            fail("The profile could not be renamed.", exception)
        }
    }

    override fun refreshState() = refreshStrongSwanState()

    override fun clearEventLog() {
        val file = charonLogFile()
        logOffset = file.length()
        initialLogRead = false
        mutableState.update { it.copy(events = emptyList()) }
    }

    private fun installLocalTrustAnchor(certificate: X509Certificate): String {
        val store = LocalCertificateStore()
        check(store.addCertificate(certificate)) { "The CA certificate could not be added to strongSwan's local trust store." }
        val alias = store.getCertificateAlias(certificate)
            ?: error("strongSwan could not create a CA certificate alias.")
        TrustedCertificateManager.getInstance().reset().load()
        return alias
    }

    private fun readStoredProfile(): VpnProfile? {
        val source = VpnProfileSource(appContext).open()
        return try {
            readStoredProfile(source)
        } finally {
            source.close()
        }
    }

    private fun readStoredProfile(source: VpnProfileDataSource): VpnProfile? {
        val profiles = source.getAllVpnProfiles()
        return profiles.firstOrNull { it.uuid == activeProfileId }
    }

    private val activeProfileId: UUID = UUID.nameUUIDFromBytes(
        "com.saeed.ikev2vpn.strongswan-profile".toByteArray(Charsets.UTF_8),
    )
    private fun refreshStrongSwanState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::refreshStrongSwanState)
            return
        }
        val service = stateService
        if (service == null) {
            mutableState.update { current ->
                if (current.connectionState == ConnectionState.CONNECTING ||
                    current.connectionState == ConnectionState.CONNECTED
                ) current else current.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    evidence = StateEvidence.STRONGSWAN_SERVICE,
                    confirmed = true,
                    detail = "The strongSwan service is not running.",
                    sessionId = null,
                    connectedSinceMillis = null,
                    traffic = null,
                )
            }
            return
        }

        val connectionState = when (service.state) {
            VpnStateService.State.CONNECTING -> ConnectionState.CONNECTING
            VpnStateService.State.CONNECTED -> ConnectionState.CONNECTED
            VpnStateService.State.DISCONNECTING -> ConnectionState.DISCONNECTING
            VpnStateService.State.DISABLED -> if (service.errorState == VpnStateService.ErrorState.NO_ERROR) {
                ConnectionState.DISCONNECTED
            } else {
                ConnectionState.ERROR
            }
        }
        val error = if (connectionState == ConnectionState.ERROR) {
            runCatching { appContext.getString(service.errorText) }.getOrDefault("strongSwan connection failed.")
        } else {
            null
        }
        mutableState.update {
            it.copy(
                connectionState = connectionState,
                evidence = StateEvidence.STRONGSWAN_SERVICE,
                confirmed = true,
                detail = error ?: "strongSwan reports ${connectionState.name.lowercase()}.",
                sessionId = service.connectionID.toString().takeIf { connectionState in setOf(
                    ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.DISCONNECTING,
                ) },
                traffic = it.traffic.takeIf { _ -> connectionState == ConnectionState.CONNECTED &&
                    it.sessionId == service.connectionID.toString() },
                connectedSinceMillis = if (connectionState == ConnectionState.CONNECTED) {
                    it.connectedSinceMillis.takeIf { _ -> it.sessionId == service.connectionID.toString() &&
                        it.connectionState == ConnectionState.CONNECTED } ?: System.currentTimeMillis()
                } else null,
                error = error,
            )
        }
    }

    /** Service state and connection ID are main-thread-only; native totals are mutex protected. */
    private fun refreshTraffic() {
        val service = stateService
        val session = service?.connectionID?.toString()
        val traffic = if (service?.state == VpnStateService.State.CONNECTED) {
            try {
                VpnTraffic.fromNative(CharonVpnService.getTrafficSnapshot())
            } catch (_: LinkageError) {
                null
            } catch (_: RuntimeException) {
                null
            }
        } else null
        mutableState.update { current ->
            current.copy(traffic = traffic.takeIf {
                current.connectionState == ConnectionState.CONNECTED && current.confirmed && current.sessionId == session
            })
        }
    }

    private fun pollCharonLog() {
        val file = charonLogFile()
        if (!file.isFile) return
        try {
            val newLines = ArrayList<String>()
            RandomAccessFile(file, "r").use { log ->
                val length = log.length()
                if (length < logOffset || initialLogRead) {
                    logOffset = if (initialLogRead) (length - INITIAL_LOG_WINDOW).coerceAtLeast(0) else 0
                    log.seek(logOffset)
                    if (logOffset > 0) log.readLine()
                    initialLogRead = false
                } else {
                    log.seek(logOffset)
                }
                while (true) {
                    val line = log.readLine() ?: break
                    val sanitized = redactSecrets(line.trim())
                    if (sanitized.isNotEmpty()) newLines += sanitized
                }
                logOffset = log.filePointer
            }
            if (newLines.isNotEmpty()) {
                val entries = newLines.takeLast(MAX_LOG_ENTRIES).map { line ->
                    VpnLogEntry(System.currentTimeMillis(), line, "strongSwan charon")
                }
                mutableState.update { it.copy(events = (it.events + entries).takeLast(MAX_LOG_ENTRIES)) }
            }
        } catch (exception: Exception) {
            mutableState.update {
                it.copy(detail = "Could not read the strongSwan diagnostic log: ${exception.javaClass.simpleName}")
            }
        }
    }

    private fun charonLogFile(): File = File(appContext.filesDir, CharonVpnService.LOG_FILE)

    private fun redactSecrets(line: String): String = SECRET_PATTERN.matcher(line)
        .replaceAll("$1=<redacted>")

    private fun fail(message: String, exception: Exception): VpnResult.Failure {
        val failure = VpnResult.Failure(message, exception.message ?: exception.javaClass.simpleName)
        mutableState.update {
            it.copy(
                connectionState = ConnectionState.ERROR,
                evidence = StateEvidence.STRONGSWAN_SERVICE,
                confirmed = true,
                detail = message,
                error = message,
            )
        }
        return failure
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 200
        private const val INITIAL_LOG_WINDOW = 256 * 1024L
        private val SECRET_PATTERN = Pattern.compile("(?i)(password|secret|shared[_ -]?secret)(\\s*[=:]\\s*)\\S+")
    }
}
