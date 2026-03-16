package com.github.chenjia404.meshproxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class ProxyService : VpnService() {

    private external fun TProxyStartService(configPath: String, fd: Int)
    private external fun TProxyStopService()
    private external fun TProxyGetStats(): LongArray

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateLock = Any()

    private var process: Process? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var startupJob: Job? = null
    private var stopJob: Job? = null
    private var statsJob: Job? = null
    private var logJob: Job? = null
    private var processMonitorJob: Job? = null

    private val _logs = MutableSharedFlow<String>(replay = 100)
    val logs = _logs.asSharedFlow()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStopProxy()
            else -> startProxy()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        requestStopProxy()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        process?.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startProxy() {
        synchronized(stateLock) {
            if (startupJob?.isActive == true || process != null || tunFd != null) {
                return
            }
        }

        startForegroundServiceNotification()

        startupJob = serviceScope.launch {
            val binaryManager = BinaryManager(this@ProxyService)
            val startedProcess = runCatching { binaryManager.executeBinary() }
                .onFailure {
                    emitLog(
                        getString(
                            R.string.log_failed_start_binary,
                            it.message ?: getString(R.string.unknown_value)
                        )
                    )
                }
                .getOrNull()
            if (startedProcess == null) {
                requestStopProxy()
                return@launch
            }

            synchronized(stateLock) {
                process = startedProcess
            }

            emitLog(getString(R.string.log_meshproxy_started_waiting_socks, SOCKS_HOST, SOCKS_PORT))
            startProcessObservers(startedProcess)

            val socksReadyFailure = waitForSocksReady(startedProcess)
            if (socksReadyFailure != null) {
                emitLog(
                    getString(
                        R.string.log_stopped_waiting_for_socks,
                        SOCKS_HOST,
                        SOCKS_PORT,
                        socksReadyFailure
                    )
                )
                requestStopProxy()
                return@launch
            }

            if (!startTunnel()) {
                emitLog(getString(R.string.log_failed_start_vpn_tunnel))
                requestStopProxy()
                return@launch
            }

            emitLog(getString(R.string.log_vpn_active_via_socks, SOCKS_HOST, SOCKS_PORT))
        }
    }

    private fun startProcessObservers(startedProcess: Process) {
        logJob?.cancel()
        processMonitorJob?.cancel()

        logJob = serviceScope.launch {
            BufferedReader(InputStreamReader(startedProcess.inputStream)).use { reader ->
                try {
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        emitLog(line)
                    }
                } catch (e: Exception) {
                    emitLog(
                        getString(
                            R.string.log_error_reading_logs,
                            e.message ?: getString(R.string.unknown_value)
                        )
                    )
                }
            }
        }

        processMonitorJob = serviceScope.launch {
            val exitCode = runCatching { startedProcess.waitFor() }.getOrNull()
            if (exitCode != null) {
                emitLog(getString(R.string.log_meshproxy_exited_code, exitCode))
            }
            requestStopProxy()
        }
    }

    private suspend fun waitForSocksReady(startedProcess: Process): String? {
        val coroutineContext = currentCoroutineContext()
        var lastFailure = getString(R.string.log_waiting_for_successful_socks_handshake)
        var nextProgressLogAt = SystemClock.elapsedRealtime()

        while (coroutineContext.isActive) {
            if (!startedProcess.isAlive) {
                return getString(R.string.log_meshproxy_exited_before_socks_ready)
            }

            val probeResult = runCatching {
                Socket().use { socket ->
                    socket.soTimeout = SOCKS_CONNECT_TIMEOUT_MS
                    socket.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT), SOCKS_CONNECT_TIMEOUT_MS)
                    val output = socket.getOutputStream()
                    output.write(byteArrayOf(0x05, 0x01, 0x00))
                    output.flush()

                    val response = ByteArray(2)
                    DataInputStream(socket.getInputStream()).readFully(response)
                    check(response[0].toInt() == 0x05) {
                        getString(R.string.log_unexpected_socks_version, response[0].toString())
                    }
                    check(response[1].toInt() != 0xFF) {
                        getString(R.string.log_socks5_auth_rejected)
                    }
                }
            }

            if (probeResult.isSuccess) {
                return null
            }

            lastFailure = probeResult.exceptionOrNull()?.message
                ?: probeResult.exceptionOrNull()?.javaClass?.simpleName
                ?: getString(R.string.log_unknown_probe_failure)

            val now = SystemClock.elapsedRealtime()
            if (now >= nextProgressLogAt) {
                emitLog(
                    getString(R.string.log_still_waiting_for_socks, SOCKS_HOST, SOCKS_PORT, lastFailure)
                )
                nextProgressLogAt = now + SOCKS_PROGRESS_LOG_INTERVAL_MS
            }

            delay(SOCKS_RETRY_DELAY_MS)
        }
        return getString(R.string.log_service_coroutine_cancelled)
    }

    private suspend fun startTunnel(): Boolean {
        synchronized(stateLock) {
            if (tunFd != null) {
                return true
            }
        }

        return try {
            val tunnelSettings = VpnTunnelSettingsStore.getSettings(this)
            if (!tunnelSettings.enableIpv4 && !tunnelSettings.enableIpv6) {
                emitLog(getString(R.string.log_failed_establish_tunnel_both_disabled))
                return false
            }

            val builder = Builder()
                .setSession(getString(R.string.vpn_session_name))
                .setBlocking(false)
                .setMtu(TUN_MTU)
            if (tunnelSettings.enableIpv4) {
                builder
                    .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(MAPPED_DNS_ADDRESS)
            }
            if (tunnelSettings.enableIpv6) {
                builder
                    .addAddress(TUN_IPV6_ADDRESS, TUN_IPV6_PREFIX)
                    .addRoute("::", 0)
            }
            configureVpnApplications(builder)

            val establishedTun = builder
                .establish()
                ?: return false

            val configFile = writeTunnelConfig(tunnelSettings)
            emitLog(getString(R.string.log_vpn_protocols, tunnelSettings.enableIpv4, tunnelSettings.enableIpv6))
            if (!tunnelSettings.enableIpv4) {
                emitLog(getString(R.string.log_ipv4_disabled_mapdns_off))
            }
            emitLog(getString(R.string.log_socks_udp_mode, SOCKS_UDP_MODE))
            emitLog(getString(R.string.log_socks_config, configFile.readText()))
            TProxyStartService(configFile.absolutePath, establishedTun.fd)
            startStatsObserver()
            showVpnActiveNotification()

            synchronized(stateLock) {
                tunFd = establishedTun
            }
            true
        } catch (e: Exception) {
            emitLog(
                getString(
                    R.string.log_failed_establish_tunnel,
                    e.message ?: getString(R.string.unknown_value)
                )
            )
            false
        }
    }

    private suspend fun configureVpnApplications(builder: Builder) {
        val selectedPackages = VpnAppSelectionStore.getSelectedPackages(this)
            .filter { it != packageName }
            .toSortedSet()

        if (selectedPackages.isEmpty()) {
            runCatching { builder.addDisallowedApplication(packageName) }
            emitLog(getString(R.string.log_vpn_routing_all_apps_except, packageName))
            return
        }

        var allowedCount = 0
        for (selectedPackage in selectedPackages) {
            val added = runCatching {
                builder.addAllowedApplication(selectedPackage)
            }.isSuccess
            if (added) {
                allowedCount++
            } else {
                emitLog(getString(R.string.log_skip_vpn_app, selectedPackage))
            }
        }

        if (allowedCount == 0) {
            runCatching { builder.addDisallowedApplication(packageName) }
            emitLog(getString(R.string.log_vpn_routing_fallback, packageName))
        } else {
            emitLog(getString(
                R.string.log_vpn_routing_selected_apps_only,
                allowedCount,
                selectedPackages.joinToString()
            ))
        }
    }

    private fun startStatsObserver() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var lastStats: LongArray? = null
            while (isActive) {
                delay(TUNNEL_STATS_INTERVAL_MS)
                val stats = runCatching { TProxyGetStats() }.getOrNull() ?: continue
                if (stats.size < 4) {
                    continue
                }
                if (lastStats?.contentEquals(stats) == true) {
                    continue
                }
                lastStats = stats.copyOf()
                emitLog(getString(
                    R.string.log_tunnel_stats,
                    stats[0],
                    stats[1],
                    stats[2],
                    stats[3]
                ))
            }
        }
    }

    private suspend fun emitLog(message: String) {
        Log.i(TAG, message)
        _logs.emit(message)
    }

    private fun writeTunnelConfig(tunnelSettings: VpnTunnelSettings): File {
        val configFile = File(filesDir, TUNNEL_CONFIG_NAME)
        val lines = mutableListOf(
            "misc:",
            "  task-stack-size: 81920",
            "  log-file: stderr",
            "  log-level: debug",
            "tunnel:",
            "  mtu: $TUN_MTU",
            "socks5:",
            "  port: $SOCKS_PORT",
            "  address: '$SOCKS_HOST'",
            "  udp: '$SOCKS_UDP_MODE'",
        )

        if (tunnelSettings.enableIpv4) {
            lines.add(6, "  ipv4: $TUN_IPV4_ADDRESS")
        }

        if (tunnelSettings.enableIpv6) {
            val tunnelInsertIndex = lines.indexOfFirst { it == "socks5:" }
            lines.add(tunnelInsertIndex, "  ipv6: '$TUN_IPV6_ADDRESS'")
        }

        if (tunnelSettings.enableIpv4) {
            lines.addAll(
                listOf(
                    "mapdns:",
                    "  address: $MAPPED_DNS_ADDRESS",
                    "  port: 53",
                    "  network: 100.64.0.0",
                    "  netmask: 255.192.0.0",
                    "  cache-size: 10000",
                )
            )
        }

        configFile.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        return configFile
    }

    private fun requestStopProxy() {
        synchronized(stateLock) {
            if (stopJob?.isActive == true) {
                return
            }
            stopJob = serviceScope.launch {
                try {
                    stopProxyInternal()
                } finally {
                    synchronized(stateLock) {
                        stopJob = null
                    }
                }
            }
        }
    }

    private fun stopProxyInternal() {
        val currentStartupJob: Job?
        val currentLogJob: Job?
        val currentProcessMonitorJob: Job?
        val currentStatsJob: Job?
        val currentProcess: Process?

        synchronized(stateLock) {
            currentStartupJob = startupJob
            currentLogJob = logJob
            currentProcessMonitorJob = processMonitorJob
            currentStatsJob = statsJob
            currentProcess = process
            startupJob = null
            logJob = null
            processMonitorJob = null
            statsJob = null
            process = null
        }

        currentStartupJob?.cancel()
        currentLogJob?.cancel()
        currentProcessMonitorJob?.cancel()
        currentStatsJob?.cancel()
        stopTunnel()

        if (currentProcess?.isAlive == true) {
            currentProcess.destroy()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopTunnel() {
        val currentTun = synchronized(stateLock) {
            tunFd.also { tunFd = null }
        } ?: return

        runCatching { TProxyStopService() }
        runCatching { currentTun.close() }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            getString(R.string.notification_channel_service_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_service_desc)
        }
        val vpnChannel = NotificationChannel(
            VPN_ACTIVE_CHANNEL_ID,
            getString(R.string.notification_channel_vpn_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_vpn_desc)
        }
        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(vpnChannel)
    }

    private fun startForegroundServiceNotification() {
        val notification = createNotification(
            channelId = SERVICE_CHANNEL_ID,
            contentText = getString(R.string.notification_starting_vpn_bridge)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showVpnActiveNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            createNotification(
                channelId = VPN_ACTIVE_CHANNEL_ID,
                contentText = getString(R.string.notification_vpn_active)
            )
        )
    }

    private fun createNotification(channelId: String, contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.github.chenjia404.meshproxy.android.START"
        const val ACTION_STOP = "com.github.chenjia404.meshproxy.android.STOP"

        private const val SERVICE_CHANNEL_ID = "proxy_service_channel"
        private const val VPN_ACTIVE_CHANNEL_ID = "proxy_vpn_active_channel"
        private const val NOTIFICATION_ID = 1
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 1080
        private const val SOCKS_RETRY_DELAY_MS = 1_000L
        private const val SOCKS_CONNECT_TIMEOUT_MS = 1_000
        private const val SOCKS_PROGRESS_LOG_INTERVAL_MS = 10_000L
        private const val TUNNEL_STATS_INTERVAL_MS = 2_000L
        private const val SOCKS_UDP_MODE = "udp"
        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "198.18.0.1"
        private const val TUN_IPV4_PREFIX = 32
        private const val TUN_IPV6_ADDRESS = "fc00::1"
        private const val TUN_IPV6_PREFIX = 128
        private const val MAPPED_DNS_ADDRESS = "198.18.0.2"
        private const val TUNNEL_CONFIG_NAME = "hev-socks5-tunnel.yml"
        private const val TAG = "ProxyService"

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}
