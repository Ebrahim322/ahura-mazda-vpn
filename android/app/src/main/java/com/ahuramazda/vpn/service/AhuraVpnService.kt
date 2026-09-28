package com.ahuramazda.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.ahuramazda.vpn.MainActivity
import com.ahuramazda.vpn.R
import com.ahuramazda.vpn.core.IpPrefixCompat
import com.ahuramazda.vpn.core.Log
import com.ahuramazda.vpn.core.Prefs
import com.ahuramazda.vpn.core.RuleSet
import com.ahuramazda.vpn.core.Stats
import com.ahuramazda.vpn.core.Tunnel
import com.ahuramazda.vpn.core.TunnelConfig
import java.net.InetAddress

/**
 * The always-on piece of the app: owns the TUN device, the engine, the
 * foreground notification and the connect/disconnect entry points used by the
 * UI, the quick-settings tile and the boot receiver.
 *
 * Route selection is where the "IP based" promise becomes concrete: the routes
 * installed here are computed from the user's CIDR rules (see [RuleSet]).  A
 * bypassed network is either excluded from the VPN (Android 13+, exact) or
 * simply never routed into it (the complement is routed instead), so bypassed
 * traffic keeps using the normal connection — it is not leaked and not
 * tunneled.
 */
class AhuraVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.ahuramazda.vpn.action.START"
        const val ACTION_STOP = "com.ahuramazda.vpn.action.STOP"
        const val ACTION_TOGGLE = "com.ahuramazda.vpn.action.TOGGLE"

        private const val CHANNEL_ID = "ahura-status"
        private const val NOTIFICATION_ID = 4711
        private const val TUN_ADDRESS_V4 = "10.111.0.2"
        private const val TUN_ADDRESS_V6 = "fd00:1111::2"

        @Volatile
        var instance: AhuraVpnService? = null
            private set

        @Volatile
        var connected: Boolean = false
            private set

        val stats = Stats()

        fun start(context: Context) {
            val intent = Intent(context, AhuraVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AhuraVpnService::class.java).setAction(ACTION_STOP))
        }

        fun toggle(context: Context) {
            if (connected) stop(context) else start(context)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var device: ParcelFileDescriptor? = null
    private var tunnel: Tunnel? = null
    private var lastUp = 0L
    private var lastDown = 0L
    private var lastTick = 0L

    private val ticker = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel("requested from UI")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w("VPN permission revoked by the system or another VPN app")
        stopTunnel("revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel("service destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // starting / stopping
    // ------------------------------------------------------------------

    private fun startTunnel() {
        if (connected) {
            Log.i("already connected")
            return
        }
        Log.debugEnabled = Prefs(this).debugEnabled
        val prefs = Prefs(this)
        val profile = prefs.activeProfile()
        if (profile.host.isEmpty()) {
            Log.e("no relay configured — add a server first")
            return
        }
        val config = prefs.tunnelConfig()

        startForegroundNotification()

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(config.mtu)
            .addAddress(TUN_ADDRESS_V4, 32)
            .setBlocking(true)
        if (config.dnsPrimary.isNotEmpty()) builder.addDnsServer(config.dnsPrimary)
        if (config.dnsSecondary.isNotEmpty()) builder.addDnsServer(config.dnsSecondary)
        if (config.enableV6) {
            try {
                builder.addAddress(TUN_ADDRESS_V6, 128)
            } catch (e: IllegalArgumentException) {
                Log.w("IPv6 address rejected: ${e.message}")
            }
        }

        val useExcludeRoute = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val routes = config.rules.tunnelRoutes(useExcludeRoute, config.enableV6)
        if (routes.isEmpty()) {
            Log.e("rule set produced no routes — refusing to start an empty tunnel")
            stopForegroundNow()
            return
        }
        for (prefix in routes) {
            val literal = InetAddress.getByAddress(prefix.bytes).hostAddress ?: continue
            try {
                builder.addRoute(literal, prefix.bits)
            } catch (e: IllegalArgumentException) {
                Log.w("route ${prefix} rejected: ${e.message}")
            }
        }
        if (useExcludeRoute) {
            for (prefix in config.rules.directExcludes(config.enableV6)) {
                if (IpPrefixCompat.excludeRoute(builder, prefix)) {
                    Log.d("excluded $prefix from the tunnel")
                }
            }
        }
        applyPerAppRules(builder, prefs)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (ignored: PackageManager.NameNotFoundException) {
        }

        val configure = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setConfigureIntent(configure)

        val iface = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e("establish() failed", e)
            null
        }
        if (iface == null) {
            Log.e("could not establish the VPN interface (permission denied?)")
            stopForegroundNow()
            return
        }

        device = iface
        stats.reset()
        val engine = Tunnel(iface, config, stats)
        tunnel = engine
        connected = true
        instance = this
        engine.start()
        lastUp = 0
        lastDown = 0
        lastTick = System.currentTimeMillis()
        handler.post(ticker)
        Log.i("connected: ${profile.label()} mode=${prefs.mode} rules=${config.rules.rules.size} routes=${routes.size}")
    }

    private fun applyPerAppRules(builder: Builder, prefs: Prefs) {
        val packages = prefs.perAppPackages
        when (prefs.perAppMode) {
            Prefs.APP_ONLY -> {
                if (packages.isEmpty()) {
                    Log.w("per-app mode 'only' with an empty list — all apps will be tunneled")
                    return
                }
                for (pkg in packages) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w("app $pkg is gone, skipping")
                    }
                }
            }
            Prefs.APP_EXCLUDE -> for (pkg in packages) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w("app $pkg is gone, skipping")
                }
            }
            else -> Unit
        }
    }

    private fun stopTunnel(reason: String) {
        if (!connected && device == null && tunnel == null) {
            stopForegroundNow()
            return
        }
        connected = false
        tunnel?.stop(reason)
        tunnel = null
        try {
            device?.close()
        } catch (ignored: Exception) {
        }
        device = null
        instance = null
        handler.removeCallbacks(ticker)
        stopForegroundNow()
        Log.i("disconnected ($reason)")
    }

    // ------------------------------------------------------------------
    // notification
    // ------------------------------------------------------------------

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = getString(R.string.channel_description)
                channel.setShowBadge(false)
                manager.createNotificationChannel(channel)
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification(0, 0))
    }

    private fun stopForegroundNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun updateNotification() {
        if (!connected) return
        val now = System.currentTimeMillis()
        val elapsed = (now - lastTick).coerceAtLeast(1)
        val up = stats.uploadedBytes
        val down = stats.downloadedBytes
        val upRate = ((up - lastUp) * 1000L / elapsed).coerceAtLeast(0)
        val downRate = ((down - lastDown) * 1000L / elapsed).coerceAtLeast(0)
        lastUp = up
        lastDown = down
        lastTick = now
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(upRate, downRate))
    }

    private fun buildNotification(upRate: Long, downRate: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, AhuraVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (connected) {
            getString(
                R.string.notification_connected,
                Stats.human(stats.uploadedBytes), Stats.human(stats.downloadedBytes)
            )
        } else {
            getString(R.string.notification_starting)
        }
        val rateLine = if (connected) {
            "▲ " + Stats.human(upRate) + "/s   ▼ " + Stats.human(downRate) + "/s   " +
                stats.tcpFlows + " TCP / " + stats.udpFlows + " UDP"
        } else {
            null
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (rateLine != null) {
            @Suppress("DEPRECATION")
            builder.setSubText(rateLine)
        }
        if (connected) {
            builder.addAction(
                Notification.Action.Builder(null, getString(R.string.disconnect), stopIntent).build()
            )
        }
        return builder.build()
    }
}
