package com.github.chenjia404.meshproxy.android

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    fun updateServiceStatus(context: Context) {
        _isRunning.value = isServiceRunning(context, ProxyService::class.java)
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
    }

    fun stopProxy(context: Context) {
        val intent = Intent(context, ProxyService::class.java)
        intent.action = ProxyService.ACTION_STOP
        context.startService(intent)
        _isRunning.value = false
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
}
