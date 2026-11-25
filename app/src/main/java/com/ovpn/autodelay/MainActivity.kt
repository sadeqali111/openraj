package com.ovpn.autodelay

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ovpn.autodelay.databinding.ActivityMainBinding
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var loadedProfiles: List<OvpnProfileItem> = emptyList()

    private var ovpnService: IOpenVPNAPIService? = null
    @Volatile
    private var currentLevel: String = "LEVEL_NOTCONNECTED"

    private val statusCallback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            currentLevel = level ?: "LEVEL_NOTCONNECTED"
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            ovpnService = IOpenVPNAPIService.Stub.asInterface(service)
            try {
                ovpnService?.registerStatusCallback(statusCallback)
                appendLog("Connected to OpenVPN for Android engine.")
            } catch (e: Exception) {
                appendLog("Callback error: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ovpnService = null
        }
    }

    private val zipPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    loadedProfiles = OvpnConfigParser.parseFromZip(stream)
                }
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Loaded ${loadedProfiles.size} .ovpn configs."
                    appendLog("Imported ${loadedProfiles.size} configs from ZIP.")
                }
            }
        }
    }

    private val apiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            prepareVpnAndRun()
        } else {
            appendLog("Permission denied for OpenVPN for Android.")
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startAutoDelayTest()
        } else {
            appendLog("VPN service permission denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindOpenVpnService()

        binding.btnImportZip.setOnClickListener {
            zipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        binding.btnStartTest.setOnClickListener {
            if (loadedProfiles.isEmpty()) {
                appendLog("Please import a ZIP file first.")
                return@setOnClickListener
            }
            if (ovpnService == null) {
                appendLog("ERROR: 'OpenVPN for Android' is not installed or not bound.")
                bindOpenVpnService()
                return@setOnClickListener
            }
            try {
                val permIntent = ovpnService?.prepare(packageName)
                if (permIntent != null) {
                    apiPermissionLauncher.launch(permIntent)
                } else {
                    prepareVpnAndRun()
                }
            } catch (e: Exception) {
                appendLog("Prepare error: ${e.message}")
            }
        }

        binding.btnDisconnect.setOnClickListener {
            try {
                ovpnService?.disconnect()
                appendLog("Disconnected.")
            } catch (e: Exception) {
                appendLog("Disconnect error: ${e.message}")
            }
        }
    }

    private fun bindOpenVpnService() {
        val intent = Intent(IOpenVPNAPIService::class.java.name)
        intent.setPackage("de.blinkt.openvpn")
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun prepareVpnAndRun() {
        try {
            val vpnIntent = ovpnService?.prepareVPNService()
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                startAutoDelayTest()
            }
        } catch (e: Exception) {
            appendLog("VPN prepare error: ${e.message}")
        }
    }

    private fun startAutoDelayTest() {
        val bridge = object : VpnConnectorBridge {
            override suspend fun connectAndWait(
                configContent: String,
                name: String,
                timeoutMs: Long
            ): Boolean {
                val service = ovpnService ?: return false
                currentLevel = "LEVEL_START"
                withContext(Dispatchers.IO) {
                    try {
                        service.startVPN(configContent)
                    } catch (_: Exception) {
                        return@withContext
                    }
                }
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    if (currentLevel == "LEVEL_CONNECTED") return true
                    if (currentLevel == "LEVEL_NOTCONNECTED" || currentLevel == "LEVEL_AUTH_FAILED") {
                        return false
                    }
                    delay(250)
                }
                return false
            }

            override suspend fun disconnect() {
                withContext(Dispatchers.IO) {
                    try {
                        ovpnService?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val engine = RealDelayEngine(bridge)
        lifecycleScope.launch {
            binding.tvStatus.text = "Testing all ${loadedProfiles.size} configs..."
            val fastest = engine.findAndConnectFastest(loadedProfiles) { msg ->
                lifecycleScope.launch(Dispatchers.Main) { appendLog(msg) }
            }
            binding.tvStatus.text = if (fastest != null) {
                "Connected: ${fastest.name} (${fastest.realDelayMs}ms)"
            } else {
                "Failed to find a working server."
            }
        }
    }

    private fun appendLog(line: String) {
        binding.tvLogs.append("$line\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            ovpnService?.unregisterStatusCallback(statusCallback)
            unbindService(serviceConnection)
        } catch (_: Exception) {
        }
    }
}
