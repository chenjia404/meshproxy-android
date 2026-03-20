package com.github.chenjia404.meshproxy.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.os.Build
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
        if (Build.TYPE == "debug") {
            WebView.setWebContentsDebuggingEnabled(true)
        }
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
        val proxyStatus by viewModel.proxyStatus.collectAsStateWithLifecycle()
        val appUpdateInfo by viewModel.appUpdateInfo.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val bound by isBound
        val pageScrollState = rememberScrollState()
        val logViewerHeight = configuration.screenHeightDp.dp
        var isConsoleStandaloneOpen by rememberSaveable { mutableStateOf(false) }
        val consoleLoadedSuccessfully = remember { mutableStateOf(false) }
        val webViewFileChooserCallback = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
        val fileChooserLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val callback = webViewFileChooserCallback.value
            if (callback == null) return@rememberLauncherForActivityResult

            val uris = if (result.resultCode == RESULT_OK) {
                val data = result.data
                when {
                    data == null -> null
                    data.clipData != null -> {
                        val clip = data.clipData!!
                        Array(clip.itemCount) { idx -> clip.getItemAt(idx).uri }
                    }
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else {
                null
            }

            callback.onReceiveValue(uris)
            webViewFileChooserCallback.value = null
        }
        val sharedWebChromeClient = remember {
            object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) return false

                    // 若上一次未回收，先回覆 null，避免 callback 遺失
                    webViewFileChooserCallback.value?.onReceiveValue(null)
                    webViewFileChooserCallback.value = filePathCallback

                    val intent = try {
                        fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    } catch (_: Throwable) {
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    }.apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    }

                    return try {
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (_: Throwable) {
                        webViewFileChooserCallback.value?.onReceiveValue(null)
                        webViewFileChooserCallback.value = null
                        false
                    }
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    // 直接把 JS console 輸出到 Logcat，便於定位渲染/腳本中斷原因
                    Log.d(
                        "MainActivity",
                        "WebView JS console level=${consoleMessage.messageLevel()} msg=${consoleMessage.message()} url=${consoleMessage.sourceId()} line=${consoleMessage.lineNumber()}"
                    )
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        }
        val consoleWebView = remember {
            WebView(context).apply {
                webViewClient = object : WebViewClient() {

                }
                webChromeClient = sharedWebChromeClient
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.javaScriptCanOpenWindowsAutomatically = true

                // Ensure cookies are usable inside WebView.
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
        }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(
                    context,
                    context.getString(R.string.notification_permission_denied),
                    Toast.LENGTH_SHORT
                ).show()
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
            Intent(context, ProxyService::class.java).also { intent ->
                intent.action = ProxyService.ACTION_START_BACKGROUND
                ContextCompat.startForegroundService(context, intent)
            }
            viewModel.loadVpnApps(context)
            viewModel.loadTunnelSettings(context)
            viewModel.checkForAppUpdate(context)
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

        // Ensure WebView loads (or reloads) when 控制台獨立頁打開且之前未成功載入
        LaunchedEffect(isConsoleStandaloneOpen) {
            if (isConsoleStandaloneOpen && !consoleLoadedSuccessfully.value) {
                consoleWebView.loadUrl(CONSOLE_URL)
            }
        }

        if (isConsoleStandaloneOpen) {
            ConsoleStandalonePage(
                title = stringResource(R.string.console),
                webView = consoleWebView,
                onBack = { isConsoleStandaloneOpen = false }
            )
            return
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
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            appUpdateInfo?.let { updateInfo ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissAppUpdatePrompt() },
                    title = {
                        Text(stringResource(R.string.update_available_title))
                    },
                    text = {
                        Text(
                            stringResource(
                                R.string.update_available_message,
                                updateInfo.latestVersion,
                                updateInfo.currentVersion
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                openUrl(context, updateInfo.releaseUrl)
                                viewModel.dismissAppUpdatePrompt()
                            }
                        ) {
                            Text(stringResource(R.string.update_now))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { viewModel.dismissAppUpdatePrompt() }
                        ) {
                            Text(stringResource(R.string.later))
                        }
                    }
                )
            }

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
                                        text = if (isRunning) {
                                            stringResource(R.string.proxy_running)
                                        } else {
                                            stringResource(R.string.proxy_stopped)
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = stringResource(R.string.control_background_binary_process),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                FilledIconButton(
                                    onClick = {
                                        if (isRunning) {
                                            viewModel.stopProxy(context)
                                        } else {
                                            if (!tunnelSettings.enableIpv4 && !tunnelSettings.enableIpv6) {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.enable_ipv4_or_ipv6),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@FilledIconButton
                                            }
                                            val prepareIntent = VpnService.prepare(context)
                                            if (prepareIntent != null) {
                                                vpnPermissionLauncher.launch(prepareIntent)
                                            } else {
                                        viewModel.startVpn(context)
                                            }
                                        }
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isRunning) {
                                            stringResource(R.string.stop_proxy)
                                        } else {
                                            stringResource(R.string.start_proxy)
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        ProxyStatusPanel(
                            status = proxyStatus,
                            isRunning = isRunning,
                            onOpenConsole = { isConsoleStandaloneOpen = true },
                            onOpenChat = { openUrl(context, CHAT_URL) }
                        )

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
                                text = stringResource(R.string.real_time_logs),
                                style = MaterialTheme.typography.labelLarge
                            )
                            TextButton(
                                onClick = { viewModel.clearLogs() },
                                enabled = logs.isNotEmpty()
                            ) {
                                Text(stringResource(R.string.clear))
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
    }

    @Composable
    fun WebViewContainer(
        webView: WebView,
        modifier: Modifier = Modifier,
    ) {
        AndroidView(
            modifier = modifier,
            factory = { webView },
            update = { }
        )
    }

    private fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @Composable
    fun ProxyStatusPanel(
        status: ProxyStatusSnapshot,
        isRunning: Boolean,
        onOpenConsole: () -> Unit,
        onOpenChat: () -> Unit,
    ) {
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current

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
                    text = stringResource(R.string.proxy_status),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(
                        R.string.relay_exit_status,
                        status.relayCount?.toString() ?: "-",
                        status.exitCount?.toString() ?: "-"
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.peer_id_value, status.peerId ?: "-"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable(enabled = !status.peerId.isNullOrBlank()) {
                        clipboard.setText(AnnotatedString(status.peerId.orEmpty()))
                        Toast.makeText(
                            context,
                            context.getString(R.string.peer_id_copied),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
                Text(
                    text = stringResource(
                        R.string.uptime_seconds_value,
                        status.uptimeSeconds?.toString() ?: "-"
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(R.string.socks5_listen_value, status.socks5Listen ?: "-"),
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenConsole,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.console))
                    }
                    OutlinedButton(
                        onClick = onOpenChat,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat))
                    }
                }

                if (isRunning && status.isLoading) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.status_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (isRunning &&
                    status.errorMessage != null &&
                    status.relayCount == null &&
                    status.exitCount == null &&
                    status.peerId == null &&
                    status.uptimeSeconds == null &&
                    status.socks5Listen == null
                ) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.status_api_not_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (!isRunning) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.start_proxy_to_load_status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ConsoleStandalonePage(
        title: String,
        webView: WebView,
        onBack: () -> Unit,
    ) {
        BackHandler(onBack = onBack)

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = title
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            WebViewContainer(
                webView = webView,
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ChatStandalonePage(
        title: String,
        webView: WebView,
        onBack: () -> Unit,
    ) {
        BackHandler(onBack = onBack)

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = title
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            WebViewContainer(
                webView = webView,
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            )
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
                    text = stringResource(R.string.ip_protocols),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.disable_ipv6_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                if (isRunning) {
                    Text(
                        text = stringResource(R.string.changes_take_effect_next_time),
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
                        text = stringResource(R.string.ipv4),
                        modifier = Modifier
                            .clickable { onIpv4Change(!settings.enableIpv4) }
                            .padding(end = 16.dp)
                    )
                    Checkbox(
                        checked = settings.enableIpv6,
                        onCheckedChange = onIpv6Change
                    )
                    Text(
                        text = stringResource(R.string.ipv6),
                        modifier = Modifier.clickable { onIpv6Change(!settings.enableIpv6) }
                    )
                }

                if (!settings.enableIpv4 && !settings.enableIpv6) {
                    Text(
                        text = stringResource(R.string.at_least_one_protocol),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (!settings.enableIpv4) {
                    Text(
                        text = stringResource(R.string.ipv4_disabled_dns_warning),
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
                            text = stringResource(R.string.vpn_app_selection),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (selectedPackages.isEmpty()) {
                                stringResource(R.string.vpn_no_app_selected)
                            } else {
                                stringResource(R.string.vpn_selected_apps_only)
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (isRunning) {
                            Text(
                                text = stringResource(R.string.changes_take_effect_next_time),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    TextButton(
                        onClick = onClear,
                        enabled = selectedPackages.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.clear))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_apps)) },
                    placeholder = { Text(stringResource(R.string.app_name_or_package)) }
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
                                text = if (apps.isEmpty()) {
                                    stringResource(R.string.no_user_installed_apps_found)
                                } else {
                                    stringResource(R.string.no_apps_match_search)
                                },
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
                            Toast.makeText(
                                context,
                                context.getString(R.string.log_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }
    }

    companion object {
        private const val CONSOLE_URL = "http://127.0.0.1:19080/console"
        private const val CHAT_URL = "http://127.0.0.1:19080/chat"
    }
}
