package com.example.mcc_phase3

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.mcc_phase3.data.ConfigManager
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.data.repository.CrowdComputeRepository
import com.example.mcc_phase3.data.models.Job
import com.example.mcc_phase3.data.models.Worker
import com.example.mcc_phase3.ui.fragments.DashboardFragment
import com.example.mcc_phase3.ui.fragments.JobsFragment
import com.example.mcc_phase3.ui.fragments.WorkersFragment
import com.example.mcc_phase3.ui.fragments.ActivityFragment
import com.example.mcc_phase3.utils.ThemeManager

/**
 * Dashboard Activity to show jobs, online devices, and mobile worker controls
 * Now includes tabs for Dashboard, Jobs, Workers, and Activity fragments
 */
class DashboardActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "DashboardActivity"
    }
    
    private lateinit var mobileWorkerButton: Button
    private lateinit var refreshButton: Button
    private lateinit var settingsButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var workerIdTextView: TextView
    private lateinit var workerIdInfoButton: Button
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var resetCircuitButton: Button
    private lateinit var toolbar: Toolbar
    
    private lateinit var repository: CrowdComputeRepository
    private lateinit var dashboardPagerAdapter: DashboardPagerAdapter
    private lateinit var workerIdManager: WorkerIdManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize theme manager and apply current theme
        ThemeManager.initialize(this)
        ThemeManager.applyCurrentTheme()
        
        setContentView(R.layout.activity_dashboard)
        
        repository = CrowdComputeRepository(this)
        workerIdManager = WorkerIdManager.getInstance(this)
        initializeViews()
        setupToolbar()
        setupViewPager()
        setupClickListeners()
        updateWorkerIdDisplay()
        startPeriodicUpdates()
    }
    
    override fun onResume() {
        super.onResume()
        // Force complete reset of ApiClient to clear any cached connections
        com.example.mcc_phase3.data.api.ApiClient.reset(this)
        // Reinitialize repository when returning from settings
        // This ensures the new IP address is used
        repository = CrowdComputeRepository(this)
        // Reset circuit breaker when configuration changes
        repository.manuallyResetCircuitBreaker()
        refreshDashboard()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = "CrowdIO"
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.dashboard_menu, menu)
        
        // Update theme icon based on current theme mode
        val themeItem = menu.findItem(R.id.action_theme)
        val currentMode = ThemeManager.getCurrentThemeMode()
        themeItem?.setIcon(
            when (currentMode) {
                ThemeManager.ThemeMode.LIGHT -> R.drawable.ic_dark_mode
                ThemeManager.ThemeMode.DARK -> R.drawable.ic_light_mode
                ThemeManager.ThemeMode.SYSTEM -> R.drawable.ic_dark_mode
            }
        )
        
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                refreshDashboard()
                true
            }
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_about -> {
                // Show about dialog
                true
            }
            R.id.action_theme -> {
                toggleTheme()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun toggleTheme() {
        val currentMode = ThemeManager.getCurrentThemeMode()
        val newMode = when (currentMode) {
            ThemeManager.ThemeMode.LIGHT -> ThemeManager.ThemeMode.DARK
            ThemeManager.ThemeMode.DARK -> ThemeManager.ThemeMode.SYSTEM
            ThemeManager.ThemeMode.SYSTEM -> ThemeManager.ThemeMode.LIGHT
        }
        ThemeManager.setThemeMode(newMode)
        
        // Recreate activity to apply theme changes
        recreate()
    }
    
    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        mobileWorkerButton = findViewById(R.id.mobileWorkerButton)
        refreshButton = findViewById(R.id.refreshButton)
        settingsButton = findViewById(R.id.settingsButton)
        statusTextView = findViewById(R.id.statusTextView)
        workerIdTextView = findViewById(R.id.workerIdTextView)
        workerIdInfoButton = findViewById(R.id.workerIdInfoButton)
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        resetCircuitButton = findViewById(R.id.resetCircuitButton)
    }
    
    private fun setupViewPager() {
        dashboardPagerAdapter = DashboardPagerAdapter(this)
        viewPager.adapter = dashboardPagerAdapter
        
        // Setup tab layout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Dashboard"
                1 -> "Jobs"
                2 -> "Workers"
                3 -> "Activity"
                else -> "Unknown"
            }
        }.attach()
    }
    
    private fun setupClickListeners() {
        mobileWorkerButton.setOnClickListener {
            val intent = Intent(this, MobileWorkerActivity::class.java)
            startActivity(intent)
        }
        
        refreshButton.setOnClickListener {
            refreshDashboard()
        }
        
        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        
        resetCircuitButton.setOnClickListener {
            repository.resetCircuitBreakerManually()
            refreshDashboard()
        }
        
        workerIdInfoButton.setOnClickListener {
            showWorkerIdInfo()
        }
    }
    
    private fun updateWorkerIdDisplay() {
        try {
            val workerId = workerIdManager.getOrGenerateWorkerId()
            val shortWorkerId = if (workerId.length > 20) {
                "${workerId.take(17)}..."
            } else {
                workerId
            }
            workerIdTextView.text = "Worker ID: $shortWorkerId"
            Log.d(TAG, "Worker ID displayed: $workerId")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating worker ID display", e)
            workerIdTextView.text = "Worker ID: Error"
        }
    }
    
    private fun showWorkerIdInfo() {
        try {
            val info = workerIdManager.getWorkerIdInfo()
            val currentWorkerId = workerIdManager.getCurrentWorkerId()
            
            val infoText = buildString {
                appendLine("🆔 Worker ID Information")
                appendLine("Current ID: $currentWorkerId")
                appendLine("Device ID: ${info.deviceId}")
                appendLine("Generated: ${if (info.generatedAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(info.generatedAt)) else "Not generated"}")
                appendLine("Has ID: ${info.hasWorkerId}")
            }
            
            // Show in a simple dialog or update status
            statusTextView.text = infoText
            Log.d(TAG, "Worker ID info displayed")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing worker ID info", e)
            statusTextView.text = "Error getting worker ID info: ${e.message}"
        }
    }
    
    private fun startPeriodicUpdates() {
        lifecycleScope.launch {
            while (true) {
                refreshDashboard()
                updateWorkerIdDisplay() // Also update worker ID periodically
                delay(10000L) // Update every 10 seconds
            }
        }
    }
    
    private fun refreshDashboard() {
        lifecycleScope.launch {
            try {
                statusTextView.text = "Refreshing..."
                
                // Show circuit breaker status
                val circuitStatus = repository.getCircuitBreakerStatus()
                Log.d(TAG, "Circuit Breaker Status: $circuitStatus")
                
                // Fetch jobs and devices in parallel
                val jobs = fetchJobs()
                val devices = fetchOnlineDevices()
                
                statusTextView.text = "Last updated: ${java.time.LocalTime.now().toString().substring(0, 8)}"
                
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing dashboard", e)
                statusTextView.text = "Error: ${e.message}"
            }
        }
    }
    
    private suspend fun fetchJobs(): List<JobInfo> {
        return try {
            val result = repository.getJobs()
            if (result.isSuccess) {
                val jobs = result.getOrNull() ?: emptyList()
                jobs.map { job ->
                    JobInfo(
                        id = job.id,
                        status = job.status,
                        tasksCompleted = job.completedTasks,
                        totalTasks = job.totalTasks,
                        createdAt = job.createdAt
                    )
                }
            } else {
                Log.e(TAG, "Error fetching jobs: ${result.exceptionOrNull()?.message}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching jobs", e)
            emptyList()
        }
    }
    
    private suspend fun fetchOnlineDevices(): List<DeviceInfo> {
        return try {
            val result = repository.getWorkers()
            if (result.isSuccess) {
                val workers = result.getOrNull() ?: emptyList()
                workers.map { worker ->
                    DeviceInfo(
                        id = worker.id,
                        status = worker.status,
                        platform = "Android", // Default platform for mobile workers
                        batteryLevel = -1, // Not available in current API
                        isCharging = false, // Not available in current API
                        tasksCompleted = worker.totalTasksCompleted,
                        lastSeen = worker.lastSeen
                    )
                }
            } else {
                Log.e(TAG, "Error fetching workers: ${result.exceptionOrNull()?.message}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching workers", e)
            emptyList()
        }
    }
    
    data class JobInfo(
        val id: String,
        val status: String,
        val tasksCompleted: Int,
        val totalTasks: Int,
        val createdAt: String
    )
    
    data class DeviceInfo(
        val id: String,
        val status: String,
        val platform: String,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val tasksCompleted: Int,
        val lastSeen: String
    )
    
    /**
     * ViewPager2 adapter for dashboard tabs
     */
    inner class DashboardPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4
        
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> DashboardFragment()
                1 -> JobsFragment()
                2 -> WorkersFragment()
                3 -> ActivityFragment()
                else -> DashboardFragment()
            }
        }
    }
}

