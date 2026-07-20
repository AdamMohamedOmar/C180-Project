package com.kompressorlink.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kompressorlink.app.connection.CdmAssociator
import com.kompressorlink.app.dashboard.DashboardScreen
import com.kompressorlink.app.dashboard.DashboardViewModel
import com.kompressorlink.app.dtc.DtcScreen
import com.kompressorlink.app.dtc.DtcViewModel
import com.kompressorlink.app.health.HealthScreen
import com.kompressorlink.app.health.HealthViewModel
import com.kompressorlink.app.maintenance.MaintenanceCheckWorker
import com.kompressorlink.app.maintenance.MaintenanceScreen
import com.kompressorlink.app.maintenance.MaintenanceViewModel
import com.kompressorlink.app.rides.RidesScreen
import com.kompressorlink.app.rides.RidesViewModel
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.ui.components.ConnectionBanner
import com.kompressorlink.app.ui.theme.KompressorLinkTheme
import kotlinx.coroutines.launch

/** A single "navigate to a notification-driven tab" request. Carries a
 *  monotonically increasing [nonce] alongside the target [tab] so that a
 *  *second* reminder tap for the same tab — currently the only real-world
 *  case, since [MaintenanceCheckWorker] only ever targets
 *  [MaintenanceCheckWorker.TAB_MAINTENANCE] — is still a distinct event.
 *  `LaunchedEffect` restarts only when its key is unequal to the previous
 *  one; two events with an identical `tab` string but different nonces
 *  compare unequal, so the navigation reliably re-fires on every tap. */
private data class StartTabEvent(val tab: String?, val nonce: Int)

class MainActivity : ComponentActivity() {

    private lateinit var associator: CdmAssociator
    private var startTabNonce = 0
    private val startTabState = mutableStateOf(StartTabEvent(tab = null, nonce = 0))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KompressorLinkApp).container
        associator = CdmAssociator(this) { mac ->
            container.persistAssociation(mac)
        }
        // A maintenance-reminder tap lands on the Maintenance tab (spec §6.4).
        startTabState.value = StartTabEvent(
            intent.getStringExtra(MaintenanceCheckWorker.EXTRA_START_TAB), startTabNonce++,
        )
        setContent {
            KompressorLinkTheme {
                AppUi(
                    container = container,
                    startTab = startTabState.value,
                    onPairRequest = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            associator.associate()
                        } else {
                            Toast.makeText(this, "Pairing requires Android 13+",
                                           Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
    }

    // MainActivity keeps the manifest-default "standard" launchMode, but
    // MaintenanceCheckWorker's PendingIntent sets FLAG_ACTIVITY_SINGLE_TOP, so
    // a second reminder tap while this Activity's task already exists (app
    // backgrounded, not swiped away) redelivers the intent here rather than
    // recreating the Activity. Without this override the redelivered intent's
    // extra would be silently dropped: onCreate()'s `intent` read only ever
    // happens once. Routing the new extra into startTabState — read from
    // inside the setContent{} composable scope, which recomposes on a State
    // change — makes the composition react to it like a fresh launch would.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startTabState.value = StartTabEvent(
            intent.getStringExtra(MaintenanceCheckWorker.EXTRA_START_TAB), startTabNonce++,
        )
    }
}

private data class Tab(val route: String, val label: String)

private val TABS = listOf(
    Tab("dashboard", "Dashboard"),
    Tab("health", "Health"),
    Tab("dtc", "DTCs"),
    Tab("maintenance", "Maintenance"),
    Tab("rides", "Rides"),
)

@Composable
private fun tabIcon(route: String) = when (route) {
    "dashboard" -> Icons.Filled.Home
    "health" -> Icons.Filled.Favorite
    "dtc" -> Icons.Filled.Warning
    "maintenance" -> Icons.Filled.Build
    // "rides": Icons.Filled.CloudDownload/DirectionsCar aren't in
    // material-icons-core (this project has no material-icons-extended
    // dependency — confirmed by inspecting the resolved 1.7.0 core aar),
    // so Refresh stands in — this tab's whole job is "sync now".
    else -> Icons.Filled.Refresh
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUi(container: AppContainer, startTab: StartTabEvent, onPairRequest: () -> Unit) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val choice by container.choiceStore.choice
        .collectAsState(initial = SourceChoice.SIMULATED_HEALTHY)
    val connection by container.telemetrySource.connectionState.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    // Runtime permissions, requested once at startup (unchanged from Phase 4).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* denial surfaces visibly: BLE connect / notifications just fail */ }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    LaunchedEffect(startTab) {
        val tab = startTab.tab
        if (tab != null && TABS.any { it.route == tab }) {
            navController.navigate(tab) {
                // Same reuse-friendly pattern as the bottom-nav clicks below:
                // avoids stacking a duplicate "maintenance" entry per tap.
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KompressorLink") },
                actions = {
                    TextButton(onClick = { menuOpen = true }) {
                        Text(if (choice == SourceChoice.REAL_BLE) "BLE" else "SIM")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Pair with device…") },
                            onClick = { menuOpen = false; onPairRequest() },
                        )
                        DropdownMenuItem(
                            text = { Text("Run reminder check now") },
                            onClick = {
                                menuOpen = false
                                // Debug/acceptance hook (spec §6.4): proves the
                                // notification path without waiting 24 h.
                                scope.launch {
                                    MaintenanceCheckWorker.runCheck(
                                        container, navController.context.applicationContext,
                                    )
                                }
                            },
                        )
                        HorizontalDivider()
                        SourceChoice.entries.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(if (c == choice) "✓ ${c.displayName}" else c.displayName) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { container.choiceStore.set(c) }
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            val backStack by navController.currentBackStackEntryAsState()
            val route = backStack?.destination?.route
            NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                // Standard bottom-nav pattern: pop back to the
                                // graph's start destination (saving state) so
                                // cycling tabs doesn't grow the back stack or
                                // leak a fresh ViewModel per revisit, and
                                // restore each tab's saved state on the way
                                // back in instead of recreating it. This also
                                // makes system Back behave like normal
                                // bottom-nav Back (return to start tab / exit)
                                // rather than replaying the whole tab-switch
                                // history.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tabIcon(tab.route), contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ConnectionBanner(connection)
            NavHost(
                navController = navController,
                startDestination = "dashboard",
            ) {
                composable("dashboard") {
                    val vm: DashboardViewModel = viewModel(initializer = {
                        DashboardViewModel(container.telemetrySource, container.referenceRepository,
                                           container.liveWarningMonitor.levels)
                    })
                    DashboardScreen(vm)
                }
                composable("health") {
                    val vm: HealthViewModel = viewModel(initializer = {
                        HealthViewModel(
                            container.sessionRepository, container.warningRepository,
                            container.liveWarningMonitor.levels, container.telemetrySource,
                            container.referenceRepository,
                        )
                    })
                    HealthScreen(vm)
                }
                composable("dtc") {
                    val vm: DtcViewModel = viewModel(initializer = {
                        DtcViewModel(container.telemetrySource, container.dtcRepository)
                    })
                    DtcScreen(vm)
                }
                composable("maintenance") {
                    val vm: MaintenanceViewModel = viewModel(initializer = {
                        MaintenanceViewModel(
                            container.maintenanceRepository, container.odometerRepository,
                            container.sessionRepository,
                        )
                    })
                    MaintenanceScreen(vm)
                }
                composable("rides") {
                    // NEARBY_WIFI_DEVICES (API 33+) is what WifiNetworkSpecifier
                    // needs to join the logger's SoftAP (AndroidManifest.xml).
                    // Requested here, scoped to the Rides tab, rather than in the
                    // app-wide startup LaunchedEffect above — mirrors that same
                    // BLE-permission-request pattern, just localized to "before
                    // the first sync" instead of "at app launch".
                    val wifiPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { /* denial surfaces visibly: the WiFi join just fails/times out */ }
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            wifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                        }
                    }
                    val vm: RidesViewModel = viewModel(initializer = {
                        RidesViewModel(
                            dao = container.rideFileDao,
                            requestWifiSync = container.telemetrySource::requestWifiSync,
                            connector = container.wifiSyncConnector,
                            makeClient = container.syncClientFactory,
                            ingestor = container.rideIngestor,
                            ridesDir = container.ridesDir,
                            debugServerOverride = if (BuildConfig.DEBUG)
                                BuildConfig.KL_SYNC_DEV_SERVER.ifEmpty { null } else null,
                        )
                    })
                    RidesScreen(vm)
                }
            }
        }
    }
}
