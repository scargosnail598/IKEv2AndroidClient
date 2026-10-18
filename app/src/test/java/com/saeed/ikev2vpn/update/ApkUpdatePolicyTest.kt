package com.saeed.ikev2vpn.update

import org.junit.Assert.*
import org.junit.Test

class ApkUpdatePolicyTest {
    private val installed = InstalledApp("com.saeed.ikev2vpn", "1.4.0", 6, false)
    private val version = ReleaseVersion(1, 5, 0)
    private val apk = ApkIdentity(installed.packageName, "1.5.0", 7, 30, setOf("current-key"))
    @Test fun `accepts a newer APK signed by the installed key`() { validate(apk) }
    @Test fun `accepts forward key rotation but rejects unrelated keys and reverse rotation`() {
        validate(apk.copy(signers = setOf("next-key"), signingHistory = setOf("current-key", "next-key")))
        invalid(apk.copy(signers = setOf("unrelated")))
        invalid(apk.copy(signers = setOf("old-key"), signingHistory = setOf("old-key")))
    }
    @Test fun `rejects wrong package mismatched release versions downgrades and unsupported Android`() {
        for (candidate in listOf(apk.copy(packageName = "other.app"), apk.copy(versionName = "1.5.1"), apk.copy(versionCode = 6), apk.copy(versionCode = 1), apk.copy(minSdk = 37), apk.copy(signers = emptySet()))) invalid(candidate)
    }
    @Test fun `multiple signing keys require an exact match`() {
        ApkUpdatePolicy.validate(installed, setOf("one", "two"), apk.copy(signers = setOf("one", "two")), version, 36)
        try { ApkUpdatePolicy.validate(installed, setOf("one", "two"), apk.copy(signers = setOf("one"), signingHistory = setOf("one", "two")), version, 36); fail() }
        catch (_: UpdateException) { }
    }
    private fun validate(candidate: ApkIdentity) = ApkUpdatePolicy.validate(installed, setOf("current-key"), candidate, version, 36)
    private fun invalid(candidate: ApkIdentity) { try { validate(candidate); fail("Expected rejected APK") } catch (_: UpdateException) { } }
}
