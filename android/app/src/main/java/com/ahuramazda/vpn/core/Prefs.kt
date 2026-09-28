package com.ahuramazda.vpn.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * A relay profile: where the relay lives, how to reach it in stealth mode and
 * which token identifies this device.
 *
 * Encoded as a shareable link so a user can send the whole thing over a
 * messenger:
 *
 *   ahura://my-vps@192.0.2.10:1080?key=<hex>&token=<token>&obfs=ahura/1
 */
class ServerProfile(
    var name: String = "relay",
    var host: String = "",
    var port: Int = 1080,
    var stealthKey: String = "",
    var token: String = "",
    var obfs: String = ObfsStream.MODE_AHURA,
    var username: String = "",
    var password: String = ""
) {

    fun label(): String = "$name  ·  $host:$port"

    fun uri(): String {
        val builder = Uri.Builder()
            .scheme("ahura")
            .encodedAuthority(token + "@" + host + ":" + port)
            .appendQueryParameter("key", stealthKey)
            .appendQueryParameter("obfs", obfs)
        if (username.isNotEmpty()) {
            builder.appendQueryParameter("user", username)
            builder.appendQueryParameter("pass", password)
        }
        if (name.isNotEmpty()) builder.appendQueryParameter("name", name)
        return builder.build().toString()
    }

    fun serialize(): String = listOf(name, host, port.toString(), stealthKey, token, obfs, username, password)
        .joinToString(SEP) { it.replace(SEP, " ") }

    override fun toString(): String = label()

    companion object {
        private const val SEP = "\u0001"

        fun deserialize(line: String): ServerProfile? {
            val parts = line.split(SEP)
            if (parts.size < 6) return null
            return ServerProfile(
                name = parts[0],
                host = parts[1],
                port = parts[2].toIntOrNull() ?: 1080,
                stealthKey = parts[3],
                token = parts[4],
                obfs = parts[5],
                username = if (parts.size > 6) parts[6] else "",
                password = if (parts.size > 7) parts[7] else ""
            )
        }

        /** Accepts `ahura://…` links, `host:port` shorthand and bare IPs. */
        fun parse(text: String): ServerProfile? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("ahura://", ignoreCase = true)) {
                val uri = Uri.parse(trimmed)
                val profile = ServerProfile()
                profile.host = uri.host ?: return null
                profile.port = if (uri.port > 0) uri.port else 1080
                profile.token = uri.userInfo ?: ""
                profile.stealthKey = uri.getQueryParameter("key") ?: ""
                profile.obfs = uri.getQueryParameter("obfs") ?: ObfsStream.MODE_AHURA
                profile.name = uri.getQueryParameter("name") ?: "relay"
                profile.username = uri.getQueryParameter("user") ?: ""
                profile.password = uri.getQueryParameter("pass") ?: ""
                return profile
            }
            val hostPort = trimmed.removePrefix("[")
            val colon = hostPort.lastIndexOf(':')
            val profile = ServerProfile()
            if (colon > 0 && hostPort.indexOf(':') == colon) {
                profile.host = hostPort.substring(0, colon)
                profile.port = hostPort.substring(colon + 1).toIntOrNull() ?: 1080
            } else {
                profile.host = trimmed
            }
            return if (profile.host.isEmpty()) null else profile
        }
    }
}

/** All user settings, backed by `SharedPreferences`. */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("ahura-mazda", Context.MODE_PRIVATE)

    var profiles: List<ServerProfile>
        get() = (prefs.getStringSet(KEY_PROFILES, emptySet()) ?: emptySet())
            .mapNotNull { ServerProfile.deserialize(it) }
            .sortedBy { it.name }
        set(value) {
            prefs.edit().putStringSet(KEY_PROFILES, value.map { it.serialize() }.toSet()).apply()
        }

    var activeIndex: Int
        get() = prefs.getInt(KEY_ACTIVE, 0)
        set(value) = prefs.edit().putInt(KEY_ACTIVE, value).apply()

    var mode: String
        get() = prefs.getString(KEY_MODE, MODE_ALL) ?: MODE_ALL
        set(value) = prefs.edit().putString(KEY_MODE, value).apply()

    var ipList: String
        get() = prefs.getString(KEY_IP_LIST, DEFAULT_IP_LIST) ?: DEFAULT_IP_LIST
        set(value) = prefs.edit().putString(KEY_IP_LIST, value).apply()

    var bypassLocal: Boolean
        get() = prefs.getBoolean(KEY_BYPASS_LOCAL, true)
        set(value) = prefs.edit().putBoolean(KEY_BYPASS_LOCAL, value).apply()

    var enableV6: Boolean
        get() = prefs.getBoolean(KEY_V6, false)
        set(value) = prefs.edit().putBoolean(KEY_V6, value).apply()

    var udpEnabled: Boolean
        get() = prefs.getBoolean(KEY_UDP, true)
        set(value) = prefs.edit().putBoolean(KEY_UDP, value).apply()

    var dnsPrimary: String
        get() = prefs.getString(KEY_DNS1, "1.1.1.1") ?: "1.1.1.1"
        set(value) = prefs.edit().putString(KEY_DNS1, value).apply()

    var dnsSecondary: String
        get() = prefs.getString(KEY_DNS2, "9.9.9.9") ?: "9.9.9.9"
        set(value) = prefs.edit().putString(KEY_DNS2, value).apply()

    var mtu: Int
        get() = prefs.getInt(KEY_MTU, 1500)
        set(value) = prefs.edit().putInt(KEY_MTU, value.coerceIn(576, 1500)).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_BOOT, value).apply()

    var perAppMode: String
        get() = prefs.getString(KEY_APP_MODE, APP_ALL) ?: APP_ALL
        set(value) = prefs.edit().putString(KEY_APP_MODE, value).apply()

    var perAppPackages: Set<String>
        get() = prefs.getStringSet(KEY_APP_LIST, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_APP_LIST, value).apply()

    var debugEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DEBUG, value).apply()
            Log.debugEnabled = value
        }

    fun activeProfile(): ServerProfile {
        val all = profiles
        if (all.isEmpty()) return ServerProfile()
        val index = activeIndex
        return all[if (index in all.indices) index else 0]
    }

    fun saveProfile(profile: ServerProfile, index: Int) {
        val all = ArrayList(profiles)
        if (index in all.indices) all[index] = profile else all.add(profile)
        profiles = all
        if (index !in all.indices) activeIndex = all.size - 1
    }

    fun deleteProfile(index: Int) {
        val all = ArrayList(profiles)
        if (index in all.indices) {
            all.removeAt(index)
            profiles = all
            if (activeIndex >= all.size) activeIndex = (all.size - 1).coerceAtLeast(0)
        }
    }

    fun ruleSet(): RuleSet {
        if (mode == MODE_LIST) {
            val cidrs = RulesText.proxyCidrs(ipList)
            if (cidrs.isNotEmpty()) return RuleSet.listOnly(cidrs)
        }
        return RuleSet.fullTunnel(bypassLocal, enableV6)
    }

    fun tunnelConfig(): TunnelConfig {
        val profile = activeProfile()
        return TunnelConfig(
            serverHost = profile.host,
            serverPort = profile.port,
            stealthKey = profile.stealthKey,
            token = profile.token,
            obfs = profile.obfs,
            username = profile.username,
            password = profile.password,
            rules = ruleSet(),
            udpEnabled = udpEnabled,
            dnsPrimary = dnsPrimary,
            dnsSecondary = dnsSecondary,
            mtu = mtu
        )
    }

    companion object {
        const val MODE_ALL = "all"
        const val MODE_LIST = "list"
        const val APP_ALL = "all"
        const val APP_ONLY = "only"
        const val APP_EXCLUDE = "exclude"

        const val DEFAULT_IP_LIST = "1.1.1.0/24\n8.8.8.0/24\n9.9.9.0/24"

        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active_profile"
        private const val KEY_MODE = "mode"
        private const val KEY_IP_LIST = "ip_list"
        private const val KEY_BYPASS_LOCAL = "bypass_local"
        private const val KEY_V6 = "ipv6"
        private const val KEY_UDP = "udp"
        private const val KEY_DNS1 = "dns1"
        private const val KEY_DNS2 = "dns2"
        private const val KEY_MTU = "mtu"
        private const val KEY_BOOT = "boot"
        private const val KEY_APP_MODE = "app_mode"
        private const val KEY_APP_LIST = "app_list"
        private const val KEY_DEBUG = "debug"
    }
}

/**
 * The IP rule editor, line oriented:
 *
 *   1.2.3.0/24          tunnel this network
 *   !10.0.0.0/8         bypass it (DIRECT)
 *   block 5.5.5.5       drop it
 *   # comment           ignored
 */
object RulesText {

    fun parse(text: String): List<IpRule> {
        val rules = ArrayList<IpRule>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            var action = IpAction.PROXY
            var body = line
            if (body.startsWith("!")) {
                action = IpAction.DIRECT
                body = body.substring(1).trim()
            } else {
                val lower = body.lowercase()
                for ((keyword, act) in listOf("direct" to IpAction.DIRECT, "bypass" to IpAction.DIRECT,
                    "block" to IpAction.BLOCK, "drop" to IpAction.BLOCK, "proxy" to IpAction.PROXY)) {
                    if (lower.startsWith("$keyword ") || lower.startsWith("$keyword:")) {
                        action = act
                        body = body.substring(keyword.length).trim().trimStart(':', ' ')
                        break
                    }
                }
            }
            val hash = body.indexOf('#')
            if (hash > 0) body = body.substring(0, hash).trim()
            if (body.isEmpty()) continue
            for (token in body.split(',', ' ', '\t')) {
                val piece = token.trim()
                if (piece.isEmpty()) continue
                val prefix = Prefix.parse(piece) ?: run {
                    Log.w("rule editor: ignoring '$piece' (not a CIDR)")
                    null
                } ?: continue
                rules.add(IpRule(prefix.toString(), action))
            }
        }
        return rules
    }

    fun proxyCidrs(text: String): List<String> =
        parse(text).filter { it.action == IpAction.PROXY }.map { it.cidr }

    fun describe(rules: List<IpRule>): String =
        rules.joinToString("; ") { "${it.action.name.lowercase()}:${it.cidr}" }
}
