package com.kompressorlink.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kompressorlink.app.connection.CdmAssociator
import com.kompressorlink.app.dashboard.DashboardScreen
import com.kompressorlink.app.dashboard.DashboardViewModel
import com.kompressorlink.app.dtc.DtcScreen
import com.kompressorlink.app.dtc.DtcViewModel
import com.kompressorlink.app.telemetry.SourceChoice
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var associator: CdmAssociator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KompressorLinkApp).container
        associator = CdmAssociator(this) { mac ->
            container.persistAssociation(mac)
        }
        setContent {
            MaterialTheme {
                AppUi(
                    container = container,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUi(container: AppContainer, onPairRequest: () -> Unit) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val choice by container.choiceStore.choice
        .collectAsState(initial = SourceChoice.SIMULATED_HEALTHY)
    var menuOpen by remember { mutableStateOf(false) }

    // Runtime permissions, requested once at startup. BLUETOOTH_CONNECT is
    // an API 31+ runtime permission; POST_NOTIFICATIONS is API 33+.
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
                NavigationBarItem(
                    selected = route == "dashboard",
                    onClick = { navController.navigate("dashboard") { launchSingleTop = true } },
                    icon = {}, label = { Text("Dashboard") },
                )
                NavigationBarItem(
                    selected = route == "dtc",
                    onClick = { navController.navigate("dtc") { launchSingleTop = true } },
                    icon = {}, label = { Text("DTCs") },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding),
        ) {
            composable("dashboard") {
                val vm: DashboardViewModel = viewModel(initializer = {
                    DashboardViewModel(container.telemetrySource, container.referenceRepository)
                })
                DashboardScreen(vm)
            }
            composable("dtc") {
                val vm: DtcViewModel = viewModel(initializer = {
                    DtcViewModel(container.telemetrySource, container.referenceRepository)
                })
                DtcScreen(vm)
            }
        }
    }
}
