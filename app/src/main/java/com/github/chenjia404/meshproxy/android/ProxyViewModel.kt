package com.github.chenjia404.meshproxy.android

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ProxyStatusSnapshot(
    val relaysKnown: Int? = null,
    val exitsKnown: Int? = null,
    val relayCount: Int? = null,
    val exitCount: Int? = null,
    val peerId: String? = null,
    val uptimeSeconds: Long? = null,
    val socks5Listen: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class ProxyViewModel : ViewModel() {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _vpnApps = MutableStateFlow<List<VpnAppInfo>>(emptyList())
    val vpnApps: StateFlow<List<VpnAppInfo>> = _vpnApps.asStateFlow()

    private val _selectedVpnApps = MutableStateFlow<Set<String>>(emptySet())
    val selectedVpnApps: StateFlow<Set<String>> = _selectedVpnApps.asStateFlow()

    private val _tunnelSettings = MutableStateFlow(VpnTunnelSettings())
    val tunnelSettings: StateFlow<VpnTunnelSettings> = _tunnelSettings.asStateFlow()

    private val _proxyStatus = MutableStateFlow(ProxyStatusSnapshot())
    val proxyStatus: StateFlow<ProxyStatusSnapshot> = _proxyStatus.asStateFlow()

    private var statusPollingJob: Job? = null

    fun updateServiceStatus(context: Context) {
        val isRunning = isServiceRunning(context, ProxyService::class.java)
        _isRunning.value = isRunning
        if (isRunning) {
            startStatusPolling()
        } else {
            stopStatusPolling()
        }
    }

    fun loadVpnApps(context: Context) {
        viewModelScope.launch {
            val apps = VpnAppSelectionStore.loadSelectableApps(context)
            val selectedPackages = VpnAppSelectionStore.getSelectedPackages(context)
            val validPackages = apps.mapTo(mutableSetOf()) { it.packageName }
            val sanitizedSelection = selectedPackages.filterTo(mutableSetOf()) { it in validPackages }

            if (sanitizedSelection != selectedPackages) {
                VpnAppSelectionStore.setSelectedPackages(context, sanitizedSelection)
            }

            _vpnApps.value = apps
            _selectedVpnApps.value = sanitizedSelection
        }
    }

    fun loadTunnelSettings(context: Context) {
        _tunnelSettings.value = VpnTunnelSettingsStore.getSettings(context)
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    fun startProxy(context: Context) {
        val intent = Intent(context, ProxyService::class.java)
        intent.action = ProxyService.ACTION_START
        context.startForegroundService(intent)
        _isRunning.value = true
        startStatusPolling()
    }

    fun stopProxy(context: Context) {
        val intent = Intent(context, ProxyService::class.java)
        intent.action = ProxyService.ACTION_STOP
        context.startService(intent)
        _isRunning.value = false
        stopStatusPolling()
    }

    fun setVpnAppSelected(context: Context, packageName: String, selected: Boolean) {
        val updated = _selectedVpnApps.value.toMutableSet().apply {
            if (selected) add(packageName) else remove(packageName)
        }
        VpnAppSelectionStore.setSelectedPackages(context, updated)
        _selectedVpnApps.value = updated
    }

    fun clearVpnAppSelection(context: Context) {
        VpnAppSelectionStore.setSelectedPackages(context, emptySet())
        _selectedVpnApps.value = emptySet()
    }

    fun setIpv4Enabled(context: Context, enabled: Boolean) {
        val updated = _tunnelSettings.value.copy(enableIpv4 = enabled)
        VpnTunnelSettingsStore.setSettings(context, updated)
        _tunnelSettings.value = updated
    }

    fun setIpv6Enabled(context: Context, enabled: Boolean) {
        val updated = _tunnelSettings.value.copy(enableIpv6 = enabled)
        VpnTunnelSettingsStore.setSettings(context, updated)
        _tunnelSettings.value = updated
    }

    fun addLog(log: String) {
        viewModelScope.launch {
            val currentLogs = _logs.value.toMutableList()
            currentLogs.add(log)
            if (currentLogs.size > 500) {
                currentLogs.removeAt(0)
            }
            _logs.value = currentLogs
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    override fun onCleared() {
        stopStatusPolling()
        super.onCleared()
    }

    private fun startStatusPolling() {
        if (statusPollingJob?.isActive == true) {
            return
        }

        statusPollingJob = viewModelScope.launch {
            _proxyStatus.value = ProxyStatusSnapshot(isLoading = true)
            while (isActive) {
                val previousStatus = _proxyStatus.value
                _proxyStatus.value = fetchProxyStatus(previousStatus)
                delay(5_000)
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
        _proxyStatus.value = ProxyStatusSnapshot()
    }

    private suspend fun fetchProxyStatus(previousStatus: ProxyStatusSnapshot): ProxyStatusSnapshot {
        return withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(STATUS_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = STATUS_TIMEOUT_MS
                    readTimeout = STATUS_TIMEOUT_MS
                }

                connection.use { httpConnection ->
                    check(httpConnection.responseCode in 200..299) {
                        "HTTP ${httpConnection.responseCode}"
                    }
                    val body = httpConnection.inputStream.bufferedReader().use { it.readText() }
                    parseProxyStatus(body)
                }
            }.getOrElse { error ->
                previousStatus.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Unable to read status API"
                )
            }
        }
    }

    private fun parseProxyStatus(responseBody: String): ProxyStatusSnapshot {
        val json = JSONObject(responseBody)
        val payload = json.optJSONObject("data")
        val sources = listOfNotNull(json, payload)
        val relaysKnown = sources.firstNotNullOfOrNull { it.optIntValue("relays_known") }
        val exitsKnown = sources.firstNotNullOfOrNull { it.optIntValue("exits_known") }

        return ProxyStatusSnapshot(
            relaysKnown = relaysKnown,
            exitsKnown = exitsKnown,
            relayCount = relaysKnown,
            exitCount = exitsKnown,
            peerId = sources.firstNotNullOfOrNull { it.optStringValue("peer_id") },
            uptimeSeconds = sources.firstNotNullOfOrNull { it.optLongValue("uptime_seconds") },
            socks5Listen = sources.firstNotNullOfOrNull { it.optStringValue("socks5_listen") },
            isLoading = false,
            errorMessage = null
        )
    }

    private fun JSONObject.optIntValue(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) {
                continue
            }

            when (val value = opt(key)) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.optLongValue(vararg keys: String): Long? {
        for (key in keys) {
            if (!has(key) || isNull(key)) {
                continue
            }

            when (val value = opt(key)) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.optStringValue(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key, "").trim()
            if (value.isNotEmpty()) {
                return value
            }
        }
        return null
    }

    private fun <T : HttpURLConnection> T.use(block: (T) -> ProxyStatusSnapshot): ProxyStatusSnapshot {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    companion object {
        private const val STATUS_URL = "http://127.0.0.1:19080/api/v1/status"
        private const val STATUS_TIMEOUT_MS = 1_500
    }
}
