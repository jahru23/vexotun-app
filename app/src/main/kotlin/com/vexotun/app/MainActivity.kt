package com.vexotun.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.vexotun.app.ui.main.MainScreen
import com.vexotun.app.ui.main.MainScreenViewModel
import com.vexotun.app.ui.theme.VexotunTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainScreenViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startVpn()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestVpnPermissionAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingUri(intent?.data)
        setContent {
            val config by viewModel.config.collectAsState()
            VexotunTheme(darkTheme = config.darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onRequestVpnStart = ::handleStartVpnRequest,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingUri(intent.data)
    }

    private fun handleIncomingUri(uri: Uri?) {
        if (uri == null) return
        viewModel.importVxtUri(uri) { result ->
            val msg = result.fold(
                onSuccess = { name -> "Config .vxt diimpor: $name" },
                onFailure = { err -> err.message ?: "Gagal membaca file .vxt" }
            )
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleStartVpnRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestVpnPermissionAndStart()
    }

    private fun requestVpnPermissionAndStart() {
        val prepareIntent: Intent? = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpn()
        }
    }
}
