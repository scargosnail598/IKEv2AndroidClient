package com.saeed.ikev2vpn.vpn

/** Inner IP bytes crossing this app's TUN, excluding encryption/control overhead. */
data class VpnTraffic(val downloadBytes: Long, val uploadBytes: Long) {
    init { require(downloadBytes >= 0 && uploadBytes >= 0) }

    companion object {
        fun fromNative(values: LongArray?): VpnTraffic? = values?.takeIf {
            it.size == 2 && it[0] >= 0 && it[1] >= 0
        }?.let { VpnTraffic(it[0], it[1]) }
    }
}

internal fun VpnState.currentTraffic(): VpnTraffic? = traffic.takeIf {
    connectionState == ConnectionState.CONNECTED && confirmed
}
