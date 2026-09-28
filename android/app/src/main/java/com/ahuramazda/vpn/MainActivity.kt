package com.ahuramazda.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import com.ahuramazda.vpn.core.IpAction
import com.ahuramazda.vpn.core.Log
import com.ahuramazda.vpn.core.ObfsStream
import com.ahuramazda.vpn.core.Prefs
import com.ahuramazda.vpn.core.RuleSet
import com.ahuramazda.vpn.core.RulesText
import com.ahuramazda.vpn.core.ServerProfile
import com.ahuramazda.vpn.core.Socks5Client
import com.ahuramazda.vpn.core.Stats
import com.ahuramazda.vpn.service.AhuraVpnService
import java.net.InetAddress

/**
 * The whole UI in one activity, built on framework widgets only — no AndroidX,
 * no Compose, one dependency (the Kotlin stdlib).  Small enough to audit, and
 * small enough that the APK stays tiny.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusLabel: TextView
    private lateinit var detailLabel: TextView
    private lateinit var serverLabel: TextView
    private lateinit var toggleButton: Button
    private lateinit var flipper: ViewFlipper
    private lateinit var tabs: List<Button>

    // home
    private lateinit var statUpload: TextView
    private lateinit var statDownload: TextView
    private lateinit var statFlows: TextView
    private lateinit var statUptime: TextView
    private lateinit var healthResult: TextView
    private lateinit var recentLog: TextView

    // servers
    private lateinit var serverList: ListView
    private lateinit var serverAdapter: ArrayAdapter<String>
    private var serverItems: List<ServerProfile> = emptyList()

    // rules
    private lateinit var modeAll: RadioButton
    private lateinit var modeList: RadioButton
    private lateinit var rulesEdit: EditText
    private lateinit var bypassLocal: CheckBox
    private lateinit var useV6: CheckBox
    private lateinit var useUdp: CheckBox
    private lateinit var appMode: Spinner
    private lateinit var appSummary: TextView
    private lateinit var ruleTestInput: EditText
    private lateinit var ruleTestResult: TextView
    private lateinit var routeSummary: TextView

    // settings
    private lateinit var dns1: EditText
    private lateinit var dns2: EditText
    private lateinit var mtuEdit: EditText
    private lateinit var autoStart: CheckBox
    private lateinit var debugLog: CheckBox
    private lateinit var versionLabel: TextView

    // log
    private lateinit var logText: TextView

    private var pendingConnect = false
    private var apps: List<Pair<String, String>> = emptyList()

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Log.debugEnabled = prefs.debugEnabled
        setContentView(R.layout.activity_main)

        statusLabel = findViewById(R.id.statusLabel)
        detailLabel = findViewById(R.id.detailLabel)
        serverLabel = findViewById(R.id.serverLabel)
        toggleButton = findViewById(R.id.toggleButton)
        flipper = findViewById(R.id.flipper)
        tabs = listOf(
            findViewById(R.id.tabHome), findViewById(R.id.tabServers),
            findViewById(R.id.tabRules), findViewById(R.id.tabSettings), findViewById(R.id.tabLog)
        )

        statUpload = findViewById(R.id.statUpload)
        statDownload = findViewById(R.id.statDownload)
        statFlows = findViewById(R.id.statFlows)
        statUptime = findViewById(R.id.statUptime)
        healthResult = findViewById(R.id.healthResult)
        recentLog = findViewById(R.id.recentLog)

        serverList = findViewById(R.id.serverList)
        serverAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, ArrayList<String>())
        serverList.adapter = serverAdapter
        serverList.choiceMode = ListView.CHOICE_MODE_SINGLE

        modeAll = findViewById(R.id.modeAll)
        modeList = findViewById(R.id.modeList)
        rulesEdit = findViewById(R.id.rulesEdit)
        bypassLocal = findViewById(R.id.bypassLocal)
        useV6 = findViewById(R.id.useV6)
        useUdp = findViewById(R.id.useUdp)
        appMode = findViewById(R.id.appMode)
        appSummary = findViewById(R.id.appSummary)
        ruleTestInput = findViewById(R.id.ruleTestInput)
        ruleTestResult = findViewById(R.id.ruleTestResult)
        routeSummary = findViewById(R.id.routeSummary)

        dns1 = findViewById(R.id.dns1)
        dns2 = findViewById(R.id.dns2)
        mtuEdit = findViewById(R.id.mtu)
        autoStart = findViewById(R.id.autoStart)
        debugLog = findViewById(R.id.debugLog)
        versionLabel = findViewById(R.id.versionLabel)

        logText = findViewById(R.id.logText)

        setupTabs()
        setupHome()
        setupServers()
        setupRules()
        setupSettings()

        versionLabel.text = getString(R.string.version_line, packageName, BuildConfigCompat.versionName(this))
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        loadSettingsIntoViews()
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                AhuraVpnService.start(this)
            } else {
                toast(getString(R.string.permission_denied))
            }
        }
    }

    // ------------------------------------------------------------------
    // tabs
    // ------------------------------------------------------------------

    private fun setupTabs() {
        tabs.forEachIndexed { index, button ->
            button.setOnClickListener {
                flipper.displayedChild = index
                updateTabs()
            }
        }
        updateTabs()
    }

    private fun updateTabs() {
        tabs.forEachIndexed { index, button ->
            button.alpha = if (index == flipper.displayedChild) 1.0f else 0.6f
        }
    }

    // ------------------------------------------------------------------
    // home
    // ------------------------------------------------------------------

    private fun setupHome() {
        toggleButton.setOnClickListener {
            if (AhuraVpnService.connected) {
                AhuraVpnService.stop(this)
            } else {
                connect()
            }
        }
        findViewById<Button>(R.id.healthButton).setOnClickListener { runHealthCheck() }
    }

    private fun connect() {
        val profile = prefs.activeProfile()
        if (profile.host.isEmpty()) {
            toast(getString(R.string.need_server))
            flipper.displayedChild = 1
            updateTabs()
            return
        }
        val permission = VpnService.prepare(this)
        if (permission != null) {
            pendingConnect = true
            startActivityForResult(permission, REQUEST_VPN)
        } else {
            pendingConnect = false
            AhuraVpnService.start(this)
        }
    }

    private fun runHealthCheck() {
        val cfg = prefs.tunnelConfig()
        healthResult.text = getString(R.string.checking)
        Thread({
            val started = System.currentTimeMillis()
            val text = try {
                val conn = ObfsStream.connect(
                    cfg.serverHost, cfg.serverPort, cfg.stealthKey, cfg.token, cfg.obfs, 8000, 8000
                )
                val socks = Socks5Client(conn)
                socks.negotiate(cfg.username, cfg.password)
                conn.close()
                getString(R.string.check_ok, (System.currentTimeMillis() - started).toInt(), cfg.obfs)
            } catch (e: Exception) {
                getString(R.string.check_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { healthResult.text = text }
        }, "ahura-health").start()
    }

    // ------------------------------------------------------------------
    // servers
    // ------------------------------------------------------------------

    private fun setupServers() {
        serverList.setOnItemClickListener { _, _, position, _ ->
            prefs.activeIndex = position
            refreshServers()
            refresh()
        }
        findViewById<Button>(R.id.addServerButton).setOnClickListener { showServerDialog(-1) }
        findViewById<Button>(R.id.editServerButton).setOnClickListener {
            val index = serverList.checkedItemPosition
            if (index < 0) toast(getString(R.string.select_server)) else showServerDialog(index)
        }
        findViewById<Button>(R.id.deleteServerButton).setOnClickListener {
            val index = serverList.checkedItemPosition
            if (index < 0) {
                toast(getString(R.string.select_server))
            } else {
                confirm(getString(R.string.delete_server)) {
                    prefs.deleteProfile(index)
                    refreshServers()
                }
            }
        }
        findViewById<Button>(R.id.shareServerButton).setOnClickListener {
            val profile = prefs.activeProfile()
            if (profile.host.isEmpty()) toast(getString(R.string.need_server))
            else copyToClipboard(profile.uri(), getString(R.string.copied_link))
        }
        findViewById<Button>(R.id.importServerButton).setOnClickListener {
            val text = readClipboard()
            val profile = if (text == null) null else ServerProfile.parse(text)
            if (profile == null) {
                toast(getString(R.string.import_failed))
            } else {
                val all = ArrayList(prefs.profiles)
                all.add(profile)
                prefs.profiles = all
                prefs.activeIndex = all.size - 1
                refreshServers()
                toast(getString(R.string.import_done, profile.label()))
            }
        }
    }

    private fun showServerDialog(index: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_server, null)
        val name = view.findViewById<EditText>(R.id.dlgName)
        val host = view.findViewById<EditText>(R.id.dlgHost)
        val port = view.findViewById<EditText>(R.id.dlgPort)
        val key = view.findViewById<EditText>(R.id.dlgKey)
        val token = view.findViewById<EditText>(R.id.dlgToken)
        val obfs = view.findViewById<Spinner>(R.id.dlgObfs)
        val user = view.findViewById<EditText>(R.id.dlgUser)
        val pass = view.findViewById<EditText>(R.id.dlgPass)

        obfs.adapter = ArrayAdapter.createFromResource(
            this, R.array.obfs_modes, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val existing = if (index >= 0 && index < prefs.profiles.size) prefs.profiles[index] else null
        if (existing != null) {
            name.setText(existing.name)
            host.setText(existing.host)
            port.setText(existing.port.toString())
            key.setText(existing.stealthKey)
            token.setText(existing.token)
            user.setText(existing.username)
            pass.setText(existing.password)
        } else {
            name.setText(getString(R.string.default_profile_name))
            port.setText("1080")
            key.setText(ObfsStream.randomHex(16))
            token.setText("phone-1")
        }
        obfs.setSelection(if (existing?.obfs == ObfsStream.MODE_NONE) 1 else 0)

        view.findViewById<Button>(R.id.dlgGenerateKey).setOnClickListener {
            key.setText(ObfsStream.randomHex(16))
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_server else R.string.edit_server)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val profile = ServerProfile(
                    name = name.text.toString().ifEmpty { "relay" },
                    host = host.text.toString().trim(),
                    port = port.text.toString().toIntOrNull() ?: 1080,
                    stealthKey = key.text.toString().trim(),
                    token = token.text.toString().trim(),
                    obfs = if (obfs.selectedItemPosition == 1) ObfsStream.MODE_NONE else ObfsStream.MODE_AHURA,
                    username = user.text.toString().trim(),
                    password = pass.text.toString()
                )
                if (profile.host.isEmpty()) {
                    toast(getString(R.string.host_required))
                    return@setPositiveButton
                }
                prefs.saveProfile(profile, index)
                refreshServers()
                toast(getString(R.string.server_saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshServers() {
        serverItems = prefs.profiles
        serverAdapter.clear()
        serverItems.forEach { serverAdapter.add(it.label()) }
        serverAdapter.notifyDataSetChanged()
        val active = prefs.activeIndex
        if (active in serverItems.indices) {
            serverList.setItemChecked(active, true)
        }
        serverLabel.text = getString(R.string.active_server, prefs.activeProfile().label())
    }

    // ------------------------------------------------------------------
    // rules
    // ------------------------------------------------------------------

    private fun setupRules() {
        modeAll.setOnClickListener { saveRules() }
        modeList.setOnClickListener { saveRules() }
        bypassLocal.setOnClickListener { saveRules() }
        useV6.setOnClickListener { saveRules() }
        useUdp.setOnClickListener { saveRules() }
        appMode.adapter = ArrayAdapter.createFromResource(
            this, R.array.app_modes, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        appMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.perAppMode = when (position) {
                    1 -> Prefs.APP_ONLY
                    2 -> Prefs.APP_EXCLUDE
                    else -> Prefs.APP_ALL
                }
                updateAppSummary()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        findViewById<Button>(R.id.pickAppsButton).setOnClickListener { showAppPicker() }
        findViewById<Button>(R.id.saveRulesButton).setOnClickListener {
            saveRules()
            toast(getString(R.string.saved))
        }
        ruleTestInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateRuleTest()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        rulesEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateRoutePreview()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    private fun saveRules() {
        prefs.mode = if (modeList.isChecked) Prefs.MODE_LIST else Prefs.MODE_ALL
        prefs.ipList = rulesEdit.text.toString()
        prefs.bypassLocal = bypassLocal.isChecked
        prefs.enableV6 = useV6.isChecked
        prefs.udpEnabled = useUdp.isChecked
        updateRoutePreview()
    }

    private fun updateRuleTest() {
        val text = ruleTestInput.text.toString().trim()
        if (text.isEmpty()) {
            ruleTestResult.text = ""
            return
        }
        val address = try {
            InetAddress.getByName(text)
        } catch (e: Exception) {
            ruleTestResult.text = getString(R.string.rule_test_bad)
            return
        }
        val rules = currentRules()
        val action = rules.actionFor(address)
        ruleTestResult.text = getString(
            R.string.rule_test_result, action.name,
            rules.describe(address)
        )
    }

    private fun currentRules(): RuleSet {
        val mode = if (modeList.isChecked) Prefs.MODE_LIST else Prefs.MODE_ALL
        if (mode == Prefs.MODE_LIST) {
            val rules = RulesText.parse(rulesEdit.text.toString())
            if (rules.isNotEmpty()) return RuleSet(rules)
        }
        return RuleSet.fullTunnel(bypassLocal.isChecked, useV6.isChecked)
    }

    private fun updateRoutePreview() {
        val rules = currentRules()
        val routes = rules.tunnelRoutes(false, useV6.isChecked)
        val shown = routes.take(8).joinToString(", ") { it.toString() }
        routeSummary.text = getString(
            R.string.route_summary, routes.size,
            if (routes.size > 8) "$shown …" else shown
        )
    }

    private fun updateAppSummary() {
        val count = prefs.perAppPackages.size
        appSummary.text = getString(R.string.app_summary, perAppModeName(), count)
    }

    private fun perAppModeName(): String = when (prefs.perAppMode) {
        Prefs.APP_ONLY -> getString(R.string.app_mode_only)
        Prefs.APP_EXCLUDE -> getString(R.string.app_mode_exclude)
        else -> getString(R.string.app_mode_all)
    }

    private fun showAppPicker() {
        if (apps.isEmpty()) apps = loadLaunchableApps()
        val labels = apps.map { it.second }.toTypedArray()
        val checked = apps.map { prefs.perAppPackages.contains(it.first) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_apps)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val packageName = apps[which].first
                val current = HashSet(prefs.perAppPackages)
                if (isChecked) current.add(packageName) else current.remove(packageName)
                prefs.perAppPackages = current
            }
            .setPositiveButton(R.string.save) { _, _ -> updateAppSummary() }
            .setNeutralButton(R.string.clear) { _, _ ->
                prefs.perAppPackages = emptySet()
                updateAppSummary()
            }
            .show()
    }

    private fun loadLaunchableApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = packageManager.queryIntentActivities(intent, 0)
        return infos.map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }

    // ------------------------------------------------------------------
    // settings + log
    // ------------------------------------------------------------------

    private fun setupSettings() {
        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener {
            prefs.dnsPrimary = dns1.text.toString().trim()
            prefs.dnsSecondary = dns2.text.toString().trim()
            prefs.mtu = mtuEdit.text.toString().toIntOrNull() ?: 1500
            prefs.autoStartOnBoot = autoStart.isChecked
            prefs.debugEnabled = debugLog.isChecked
            toast(getString(R.string.saved))
        }
        findViewById<Button>(R.id.exportButton).setOnClickListener {
            val text = buildString {
                append("dns1=").append(prefs.dnsPrimary).append('\n')
                append("dns2=").append(prefs.dnsSecondary).append('\n')
                append("mtu=").append(prefs.mtu).append('\n')
                append("mode=").append(prefs.mode).append('\n')
                append("bypass_local=").append(prefs.bypassLocal).append('\n')
                append("ipv6=").append(prefs.enableV6).append('\n')
                append("udp=").append(prefs.udpEnabled).append('\n')
                append("rules:\n").append(prefs.ipList).append('\n')
                for (profile in prefs.profiles) {
                    append("server=").append(profile.uri()).append('\n')
                }
            }
            copyToClipboard(text, getString(R.string.copied_settings))
        }
        findViewById<Button>(R.id.importButton).setOnClickListener {
            val text = readClipboard()
            if (text == null) {
                toast(getString(R.string.import_failed))
                return@setOnClickListener
            }
            val added = ArrayList<String>()
            for (line in text.split('\n')) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("server=") -> ServerProfile.parse(trimmed.substring(7))?.let { profile ->
                        val all = ArrayList(prefs.profiles)
                        all.add(profile)
                        prefs.profiles = all
                        added.add(profile.label())
                    }
                    trimmed.startsWith("dns1=") -> prefs.dnsPrimary = trimmed.substring(5)
                    trimmed.startsWith("dns2=") -> prefs.dnsSecondary = trimmed.substring(5)
                    trimmed.startsWith("mtu=") -> prefs.mtu = trimmed.substring(4).toIntOrNull() ?: 1500
                    trimmed.startsWith("mode=") -> prefs.mode = trimmed.substring(5)
                    trimmed.startsWith("ipv6=") -> prefs.enableV6 = trimmed.substring(5).toBoolean()
                    trimmed.startsWith("udp=") -> prefs.udpEnabled = trimmed.substring(4).toBoolean()
                    trimmed.startsWith("bypass_local=") -> prefs.bypassLocal = trimmed.substring(13).toBoolean()
                    else -> Unit
                }
            }
            loadSettingsIntoViews()
            refreshServers()
            updateRoutePreview()
            toast(
                if (added.isEmpty()) getString(R.string.import_done_settings)
                else getString(R.string.import_done_servers, added.joinToString(", "))
            )
        }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            Log.clear()
            logText.text = ""
        }
        findViewById<Button>(R.id.copyLogButton).setOnClickListener {
            copyToClipboard(Log.asText(), getString(R.string.copied_log))
        }
    }

    private fun loadSettingsIntoViews() {
        modeAll.isChecked = prefs.mode == Prefs.MODE_ALL
        modeList.isChecked = prefs.mode == Prefs.MODE_LIST
        rulesEdit.setText(prefs.ipList)
        bypassLocal.isChecked = prefs.bypassLocal
        useV6.isChecked = prefs.enableV6
        useUdp.isChecked = prefs.udpEnabled
        appMode.setSelection(
            when (prefs.perAppMode) {
                Prefs.APP_ONLY -> 1
                Prefs.APP_EXCLUDE -> 2
                else -> 0
            }
        )
        dns1.setText(prefs.dnsPrimary)
        dns2.setText(prefs.dnsSecondary)
        mtuEdit.setText(prefs.mtu.toString())
        autoStart.isChecked = prefs.autoStartOnBoot
        debugLog.isChecked = prefs.debugEnabled
        updateAppSummary()
        updateRoutePreview()
    }

    // ------------------------------------------------------------------
    // refresh loop
    // ------------------------------------------------------------------

    private fun refresh() {
        val connected = AhuraVpnService.connected
        statusLabel.text = getString(if (connected) R.string.status_connected else R.string.status_disconnected)
        statusLabel.setTextColor(getColorCompat(if (connected) R.color.ahura_ok else R.color.ahura_muted))
        toggleButton.text = getString(if (connected) R.string.disconnect else R.string.connect)
        serverLabel.text = getString(R.string.active_server, prefs.activeProfile().label())

        val stats = AhuraVpnService.stats
        statUpload.text = getString(R.string.stat_upload, Stats.human(stats.uploadedBytes))
        statDownload.text = getString(R.string.stat_download, Stats.human(stats.downloadedBytes))
        statFlows.text = getString(R.string.stat_flows, stats.tcpFlows, stats.udpFlows, stats.tcpConnections, stats.udpConnections)
        statUptime.text = getString(R.string.stat_uptime, Stats.duration(stats.uptimeSeconds), stats.errorCount)

        detailLabel.text = if (connected) getString(R.string.detail_connected) else getString(R.string.detail_idle)
        if (flipper.displayedChild == 4) {
            logText.text = Log.tail(220).joinToString("\n") { Log.format(it) }
        }
        recentLog.text = Log.tail(3).joinToString("\n") { Log.format(it) }
    }

    // ------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------

    private fun getColorCompat(id: Int): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            resources.getColor(id, theme)
        } else {
            @Suppress("DEPRECATION")
            resources.getColor(id)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.yes) { _, _ -> onYes() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun copyToClipboard(text: String, toastMessage: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ahura", text))
        toast(toastMessage)
    }

    private fun readClipboard(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    private companion object {
        const val REQUEST_VPN = 1001
    }
}

/** Tiny wrapper so the About line can show a version without Gradle codegen. */
object BuildConfigCompat {
    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }
}
