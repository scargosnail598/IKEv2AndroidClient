package com.saeed.ikev2vpn

import com.saeed.ikev2vpn.certificate.AndroidCertificateImporter
import com.saeed.ikev2vpn.certificate.CertificateImporter
import com.saeed.ikev2vpn.certificate.CertificateLoader
import com.saeed.ikev2vpn.data.DataStoreProfileRepository
import com.saeed.ikev2vpn.data.ProfileRepository
import com.saeed.ikev2vpn.data.ThemePreferences
import com.saeed.ikev2vpn.profile.AndroidIkevProfileImporter
import com.saeed.ikev2vpn.profile.IkevProfileImporter
import com.saeed.ikev2vpn.vpn.StrongSwanVpnController
import com.saeed.ikev2vpn.vpn.VpnPlatformController
import org.strongswan.android.logic.StrongSwanApplication

class Ikev2VpnApplication : StrongSwanApplication() {
    lateinit var appUpdates: com.saeed.ikev2vpn.update.AppUpdateRepository
        private set
    lateinit var profileRepository: ProfileRepository
        private set
    lateinit var certificateImporter: CertificateImporter
        private set
    lateinit var ikevProfileImporter: IkevProfileImporter
        private set
    lateinit var vpnController: VpnPlatformController
        private set
    lateinit var themePreferences: ThemePreferences
        private set

    override fun onCreate() {
        super.onCreate()
        appUpdates = com.saeed.ikev2vpn.update.GitHubAppUpdateRepository(this)
        val certificateLoader = CertificateLoader()
        profileRepository = DataStoreProfileRepository(this, certificateLoader)
        certificateImporter = AndroidCertificateImporter(contentResolver, certificateLoader)
        ikevProfileImporter = AndroidIkevProfileImporter(contentResolver, certificateLoader)
        vpnController = StrongSwanVpnController(this)
        themePreferences = ThemePreferences(this)
    }
}
