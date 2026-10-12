package sh.kizer.golive.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import sh.kizer.golive.ScanService
import sh.kizer.golive.repository

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var hasPermission by mutableStateOf(false)

    private val permissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GoLiveTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }
                when (screen) {
                    Screen.MAIN -> MainScreen(
                        vm = vm,
                        hasPermission = hasPermission,
                        onRequestPermission = { requestPermissions.launch(permissions) },
                        onCollectingChange = ::setCollecting,
                        onOpenBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                        onOpenTrend = { vm.trendMetric.value = it; screen = Screen.TREND },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.TREND -> TrendScreen(vm) { screen = Screen.MAIN }
                    Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.MAIN }
                }
            }
        }
        if (!ScanService.canScan(this)) requestPermissions.launch(permissions)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Starts collecting when it's on and allowed. */
    private fun refresh() {
        hasPermission = ScanService.canScan(this)
        if (hasPermission && repository.collecting.value) ScanService.start(this)
    }

    private fun setCollecting(on: Boolean) {
        repository.setCollecting(on)
        if (on) refresh() else ScanService.stop(this)
    }

    private enum class Screen { MAIN, TREND, SETTINGS }
}
