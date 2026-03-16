package com.github.chenjia404.meshproxy.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.chenjia404.meshproxy.android.ui.theme.MeshproxyandroidTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private var proxyService: ProxyService? = null
    private val _isBound = mutableStateOf(false)
    val isBound: State<Boolean> = _isBound

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ProxyService.LocalBinder
            proxyService = binder.getService()
            _isBound.value = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            _isBound.value = false
            proxyService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeshproxyandroidTheme {
                ProxyDashboard()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ProxyDashboard(viewModel: ProxyViewModel = viewModel()) {
        val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
        val logs by viewModel.logs.collectAsStateWithLifecycle()
        val vpnApps by viewModel.vpnApps.collectAsStateWithLifecycle()
        val selectedVpnApps by viewModel.selectedVpnApps.collectAsStateWithLifecycle()
        val tunnelSettings by viewModel.tunnelSettings.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val bound by isBound
        val pageScrollState = rememberScrollState()
        val logViewerHeight = configuration.screenHeightDp.dp
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, "通知权限未授予，前台保活通知可能不可见", Toast.LENGTH_SHORT).show()
            }
        }
        val vpnPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.startProxy(context)
            } else {
                viewModel.updateServiceStatus(context)
            }
        }

        // Sync service status on start
        LaunchedEffect(Unit) {
            viewModel.updateServiceStatus(context)
            viewModel.loadVpnApps(context)
            viewModel.loadTunnelSettings(context)
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Collect logs from service if bound
        LaunchedEffect(bound, proxyService) {
            if (bound) {
                proxyService?.logs?.collectLatest { log ->
                    viewModel.addLog(log)
                }
            }
        }

        // Bind/Unbind based on service status
        LaunchedEffect(isRunning) {
            if (isRunning) {
                Intent(context, ProxyService::class.java).also { intent ->
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }
            } else {
                if (bound) {
                    context.unbindService(connection)
                    _isBound.value = false
                    proxyService = null
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("meshproxy-android") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(pageScrollState)
                    .padding(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = if (isRunning) "Proxy is RUNNING" else "Proxy is STOPPED",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "Control the background binary process",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        
                        FilledIconButton(
                            onClick = {
                                if (isRunning) {
                                    viewModel.stopProxy(context)
                                } else {
                                    if (!tunnelSettings.enableIpv4 && !tunnelSettings.enableIpv6) {
                                        Toast.makeText(context, "至少开启 IPv4 或 IPv6 其中一个", Toast.LENGTH_SHORT).show()
                                        return@FilledIconButton
                                    }
                                    val prepareIntent = VpnService.prepare(context)
                                    if (prepareIntent != null) {
                                        vpnPermissionLauncher.launch(prepareIntent)
                                    } else {
                                        viewModel.startProxy(context)
                                    }
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = if (isRunning) "Stop Proxy" else "Start Proxy"
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TunnelProtocolSelector(
                    settings = tunnelSettings,
                    isRunning = isRunning,
                    onIpv4Change = { enabled ->
                        viewModel.setIpv4Enabled(context, enabled)
                    },
                    onIpv6Change = { enabled ->
                        viewModel.setIpv6Enabled(context, enabled)
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                VpnAppSelector(
                    apps = vpnApps,
                    selectedPackages = selectedVpnApps,
                    isRunning = isRunning,
                    onToggle = { packageName, selected ->
                        viewModel.setVpnAppSelected(context, packageName, selected)
                    },
                    onClear = {
                        viewModel.clearVpnAppSelection(context)
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Real-time Logs",
                        style = MaterialTheme.typography.labelLarge
                    )
                    TextButton(
                        onClick = { viewModel.clearLogs() },
                        enabled = logs.isNotEmpty()
                    ) {
                        Text("Clear")
                    }
                }

                LogViewer(
                    logs = logs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(logViewerHeight)
                )
            }
        }
    }

    @Composable
    fun TunnelProtocolSelector(
        settings: VpnTunnelSettings,
        isRunning: Boolean,
        onIpv4Change: (Boolean) -> Unit,
        onIpv6Change: (Boolean) -> Unit,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "IP Protocols",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "关闭 IPv6 后，VPN 不再为应用提供 IPv6 路由",
                    style = MaterialTheme.typography.bodySmall
                )
                if (isRunning) {
                    Text(
                        text = "Changes take effect next time the VPN starts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = settings.enableIpv4,
                        onCheckedChange = onIpv4Change
                    )
                    Text(
                        text = "IPv4",
                        modifier = Modifier
                            .clickable { onIpv4Change(!settings.enableIpv4) }
                            .padding(end = 16.dp)
                    )
                    Checkbox(
                        checked = settings.enableIpv6,
                        onCheckedChange = onIpv6Change
                    )
                    Text(
                        text = "IPv6",
                        modifier = Modifier.clickable { onIpv6Change(!settings.enableIpv6) }
                    )
                }

                if (!settings.enableIpv4 && !settings.enableIpv6) {
                    Text(
                        text = "At least one protocol must stay enabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (!settings.enableIpv4) {
                    Text(
                        text = "IPv4 disabled: mapped DNS is unavailable, so domain resolution may fail",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    @Composable
    fun VpnAppSelector(
        apps: List<VpnAppInfo>,
        selectedPackages: Set<String>,
        isRunning: Boolean,
        onToggle: (String, Boolean) -> Unit,
        onClear: () -> Unit,
    ) {
        var searchQuery by rememberSaveable { mutableStateOf("") }
        val filteredApps = remember(apps, selectedPackages, searchQuery) {
            val keyword = searchQuery.trim()
            val visibleApps = if (keyword.isEmpty()) {
                apps
            } else {
                val normalized = keyword.lowercase()
                apps.filter { app ->
                    app.label.lowercase().contains(normalized) ||
                        app.packageName.lowercase().contains(normalized)
                }
            }

            visibleApps.sortedWith(
                compareByDescending<VpnAppInfo> { it.packageName in selectedPackages }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName }
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "VPN App Selection",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (selectedPackages.isEmpty()) {
                                "No app selected: VPN applies to all apps except this app"
                            } else {
                                "Only selected apps use VPN"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (isRunning) {
                            Text(
                                text = "Changes take effect next time the VPN starts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    TextButton(
                        onClick = onClear,
                        enabled = selectedPackages.isNotEmpty()
                    ) {
                        Text("Clear")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search apps") },
                    placeholder = { Text("App name or package") }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (filteredApps.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (apps.isEmpty()) "No user-installed apps found" else "No apps match the search",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = filteredApps,
                                key = { it.packageName }
                            ) { app ->
                                val checked = app.packageName in selectedPackages
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggle(app.packageName, !checked) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { onToggle(app.packageName, it) }
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = app.label,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = app.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun LogViewer(logs: List<String>, modifier: Modifier = Modifier) {
        val listState = rememberLazyListState()
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        
        // Auto-scroll to bottom when new logs arrive
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) {
                listState.animateScrollToItem(logs.size - 1)
            }
        }

        Surface(
            modifier = modifier.fillMaxWidth(),
            color = Color(0xFF1E1E1E), // Dark terminal background
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        color = Color(0xFF00FF00), // Terminal green
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(log))
                            Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}
