package com.ahuramazda.vpn.core

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import java.net.InetAddress

/**
 * `VpnService.Builder.excludeRoute()` and `android.net.IpPrefix` only exist
 * from API 33 (Android 13).  Keeping that reference isolated here means the
 * rest of the app never has to think about it and older devices keep working:
 * they get the "complement" route list instead (see [RuleSet.tunnelRoutes]).
 */
object IpPrefixCompat {

    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Returns true when the network was excluded from the tunnel. */
    fun excludeRoute(builder: VpnService.Builder, prefix: Prefix): Boolean {
        if (!supported) return false
        return try {
            builder.excludeRoute(IpPrefix(InetAddress.getByAddress(prefix.bytes), prefix.bits))
            true
        } catch (e: Exception) {
            Log.w("excludeRoute($prefix) failed: ${e.message}")
            false
        }
    }
}
