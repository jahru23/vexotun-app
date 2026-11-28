package com.vexotun.app

import android.Manifest
import android.content.ComponentCallbacks2
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vexotun.app.model.VxtPinRequiredException
import com.vexotun.app.ui.main.MainScreen
import com.vexotun.app.ui.main.MainScreenViewModel
import com.vexotun.app.ui.theme.VexotunTheme
import com.vexotun.app.vpn.TunnelState

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
        if (savedInstanceState == null) {
            handleIncomingUri(intent?.data)
        }
        setContent {
            val config by viewModel.config.collectAsStateWithLifecycle()
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

    override fun onStart() {
        super.onStart()
        TunnelState.bind(this)
    }

    override fun onStop() {
        TunnelState.unbind(this)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            System.gc()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingUri(intent.data)
    }

    private fun handleIncomingUri(uri: Uri?) {
        if (uri == null) return
        intent?.data = null
        viewModel.importVxtUri(uri) { result ->
            result.onSuccess { name ->
                Toast.makeText(this, "Config .vxt diimpor: $name", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                if (err is VxtPinRequiredException) {
                    viewModel.requestPinForUri(uri)
                } else {
                    Toast.makeText(this, err.message ?: "Gagal membaca file .vxt", Toast.LENGTH_LONG).show()
                }
            }
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
