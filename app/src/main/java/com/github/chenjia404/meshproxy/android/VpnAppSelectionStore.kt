package com.github.chenjia404.meshproxy.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class VpnAppInfo(
    val packageName: String,
    val label: String,
)

object VpnAppSelectionStore {

    private const val PREFS_NAME = "vpn_app_selection"
    private const val KEY_SELECTED_PACKAGES = "selected_packages"

    fun loadSelectableApps(context: Context): List<VpnAppInfo> {
        val packageManager = context.packageManager
        val installedApplications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        return installedApplications
            .asSequence()
            .mapNotNull { applicationInfo ->
                val packageName = applicationInfo.packageName
                val isSystemApp =
                    (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystemApp =
                    (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (packageName == context.packageName) {
                    return@mapNotNull null
                }
                if (isSystemApp && !isUpdatedSystemApp) {
                    return@mapNotNull null
                }
                VpnAppInfo(
                    packageName = packageName,
                    label = packageManager.getApplicationLabel(applicationInfo)?.toString().orEmpty()
                        .ifBlank { packageName }
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(VpnAppInfo::label, VpnAppInfo::packageName))
            .toList()
    }

    fun getSelectedPackages(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_SELECTED_PACKAGES, emptySet())
            ?.toSet()
            .orEmpty()
    }

    fun setSelectedPackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SELECTED_PACKAGES, packages)
            .apply()
    }
}
