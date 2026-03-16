package com.github.chenjia404.meshproxy.android

import android.content.Context

data class VpnTunnelSettings(
    val enableIpv4: Boolean = true,
    val enableIpv6: Boolean = true,
)

object VpnTunnelSettingsStore {

    private const val PREFS_NAME = "vpn_tunnel_settings"
    private const val KEY_ENABLE_IPV4 = "enable_ipv4"
    private const val KEY_ENABLE_IPV6 = "enable_ipv6"

    fun getSettings(context: Context): VpnTunnelSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return VpnTunnelSettings(
            enableIpv4 = prefs.getBoolean(KEY_ENABLE_IPV4, true),
            enableIpv6 = prefs.getBoolean(KEY_ENABLE_IPV6, true),
        )
    }

    fun setSettings(context: Context, settings: VpnTunnelSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLE_IPV4, settings.enableIpv4)
            .putBoolean(KEY_ENABLE_IPV6, settings.enableIpv6)
            .apply()
    }
}
