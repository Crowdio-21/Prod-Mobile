package com.example.mcc_phase3.ui.fragments

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.mcc_phase3.R
import com.example.mcc_phase3.data.ConfigManager
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.services.MobileWorkerService
import com.example.mcc_phase3.utils.ThemeManager
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textview.MaterialTextView

class SettingsFragment : Fragment() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var configManager: ConfigManager
    private lateinit var foremanConfigCard: MaterialCardView
    private lateinit var foremanIpText: MaterialTextView
    private lateinit var workerNameCard: MaterialCardView
    private lateinit var workerNameText: MaterialTextView
    private lateinit var notificationsSwitch: SwitchMaterial
    private lateinit var themeToggleGroup: MaterialButtonToggleGroup
    private lateinit var workerIdManager: WorkerIdManager

    // Service binding so we can reconnect the worker when the address changes
    private var workerService: MobileWorkerService? = null
    private var isBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            workerService = (binder as MobileWorkerService.LocalBinder).getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            workerService = null
            isBound = false
        }
    }
    
    override fun onStart() {
        super.onStart()
        val intent = Intent(requireContext(), MobileWorkerService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        sharedPreferences = requireContext().getSharedPreferences("CROWDioPrefs", Context.MODE_PRIVATE)
        configManager = ConfigManager.getInstance(requireContext())
        workerIdManager = WorkerIdManager.getInstance(requireContext())
        
        foremanConfigCard = view.findViewById(R.id.foreman_config_card)
        foremanIpText = view.findViewById(R.id.foreman_ip_text)
        workerNameCard = view.findViewById(R.id.worker_name_card)
        workerNameText = view.findViewById(R.id.worker_name_text)
        notificationsSwitch = view.findViewById(R.id.notifications_switch)
        themeToggleGroup = view.findViewById(R.id.theme_toggle_group)
        
        loadSettings()
        setupListeners()
    }
    
    private fun loadSettings() {
        // Load foreman configuration from ConfigManager
        val foremanIp = configManager.getForemanIP()
        val foremanPort = configManager.getWebSocketPort()
        
        if (foremanIp.isNotEmpty()) {
            foremanIpText.text = "$foremanIp:$foremanPort"
        } else {
            foremanIpText.text = "Not configured"
        }
        
        // Load worker name
        val workerId = workerIdManager.getOrGenerateWorkerId()
        val customName = workerIdManager.getCustomWorkerName()
        if (customName != null) {
            workerNameText.text = "$customName (custom)"
        } else {
            workerNameText.text = workerId
        }
        
        notificationsSwitch.isChecked = sharedPreferences.getBoolean("notifications_enabled", true)

        // Reflect current ThemeManager mode in the toggle group
        val checkedId = when (ThemeManager.getCurrentThemeMode()) {
            ThemeManager.ThemeMode.LIGHT  -> R.id.theme_light
            ThemeManager.ThemeMode.DARK   -> R.id.theme_dark
            ThemeManager.ThemeMode.SYSTEM -> R.id.theme_system
        }
        themeToggleGroup.check(checkedId)
    }
    
    private fun setupListeners() {
        foremanConfigCard.setOnClickListener {
            showForemanConfigDialog()
        }
        
        workerNameCard.setOnClickListener {
            showWorkerNameDialog()
        }
        
        notificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("notifications_enabled", isChecked).apply()
        }

        themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.theme_light  -> ThemeManager.ThemeMode.LIGHT
                R.id.theme_dark   -> ThemeManager.ThemeMode.DARK
                else              -> ThemeManager.ThemeMode.SYSTEM
            }
            ThemeManager.setThemeMode(mode)
        }
    }
    
    private fun showForemanConfigDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_foreman_config, null)
        
        val ipInput = dialogView.findViewById<EditText>(R.id.ip_input)
        val portInput = dialogView.findViewById<EditText>(R.id.port_input)
        
        // Load current values from ConfigManager
        ipInput.setText(configManager.getForemanIP())
        val port = configManager.getWebSocketPort()
        portInput.setText(port.toString())
        
        AlertDialog.Builder(requireContext())
            .setTitle("Foreman WebSocket Configuration")
            .setView(dialogView)
            .setPositiveButton("Save") { dialog, _ ->
                val ip = ipInput.text.toString().trim()
                val portStr = portInput.text.toString().trim()
                
                if (validateInput(ip, portStr)) {
                    val wasRunning = workerService?.isWorkerRunning() == true

                    // Save to ConfigManager (used by worker service)
                    configManager.setForemanIP(ip)
                    configManager.setWebSocketPort(portStr.toInt())

                    loadSettings()

                    if (wasRunning) {
                        // Stop current connection and reconnect to new address
                        workerService?.stopWorker()
                        val newUrl = configManager.getForemanURL()
                        if (newUrl != null) {
                            val serviceIntent = Intent(requireContext(), MobileWorkerService::class.java).apply {
                                action = "START_WORKER"
                                putExtra("foreman_url", newUrl)
                            }
                            requireContext().startService(serviceIntent)
                            Toast.makeText(requireContext(), "Reconnecting to $ip:${portStr}...", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(requireContext(), "Configuration saved.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "Invalid configuration", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    private fun showWorkerNameDialog() {
        val nameInput = EditText(requireContext()).apply {
            hint = "Enter worker name"
            setText(workerIdManager.getCustomWorkerName() ?: "")
            setPadding(50, 40, 50, 40)
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("Worker Name")
            .setMessage("Set a custom name for this worker device")
            .setView(nameInput)
            .setPositiveButton("Set Custom Name") { dialog, _ ->
                val customName = nameInput.text.toString().trim()
                if (customName.isNotBlank()) {
                    workerIdManager.setCustomWorkerName(customName)
                    loadSettings()
                    Toast.makeText(requireContext(), "Worker name updated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNeutralButton("Use Auto-Generated") { dialog, _ ->
                workerIdManager.clearCustomWorkerName()
                loadSettings()
                Toast.makeText(requireContext(), "Using auto-generated name", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    private fun validateInput(ip: String, port: String): Boolean {
        if (ip.isEmpty() || port.isEmpty()) return false
        
        // Basic IP validation
        val ipParts = ip.split(".")
        if (ipParts.size != 4) return false
        
        for (part in ipParts) {
            val num = part.toIntOrNull() ?: return false
            if (num < 0 || num > 255) return false
        }
        
        // Port validation
        val portNum = port.toIntOrNull() ?: return false
        return portNum in 1..65535
    }
}
