package com.crowdio.mcc_phase3

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.crowdio.mcc_phase3.ui.activities.*
import com.crowdio.mcc_phase3.ui.fragments.NotificationsFragment
import com.crowdio.mcc_phase3.ui.fragments.SettingsFragment
import com.crowdio.mcc_phase3.ui.fragments.TasksFragment
import com.crowdio.mcc_phase3.utils.ThemeManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var toolbar: MaterialToolbar
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() called")
        
        // Initialize theme manager and apply current theme
        ThemeManager.initialize(this)
        ThemeManager.applyCurrentTheme()
        
        setContentView(R.layout.activity_main)
        
        setupViews()
        setupToolbar()
        setupDrawer()
        setupBottomNavigation()
        setupBackPressedHandler()
        
        // Load default fragment
        if (savedInstanceState == null) {
            loadFragment(TasksFragment(), "Tasks")
            bottomNavigation.selectedItemId = R.id.navigation_tasks
        }
    }
    
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
    
    private fun setupViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.navigation_view)
        bottomNavigation = findViewById(R.id.bottom_navigation)
        toolbar = findViewById(R.id.toolbar)
    }
    
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }
    
    private fun setupDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.app_name,
            R.string.app_name
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        
        navigationView.setNavigationItemSelectedListener(this)
    }
    
    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_tasks -> {
                    loadFragment(TasksFragment(), "Tasks")
                    true
                }
                R.id.navigation_settings -> {
                    loadFragment(SettingsFragment(), "Settings")
                    true
                }
                R.id.navigation_notifications -> {
                    loadFragment(NotificationsFragment(), "Notifications")
                    true
                }
                else -> false
            }
        }
    }
    
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_tasks -> {
                loadFragment(TasksFragment(), "Tasks")
                bottomNavigation.selectedItemId = R.id.navigation_tasks
            }
            R.id.nav_notifications -> {
                loadFragment(NotificationsFragment(), "Notifications")
                bottomNavigation.selectedItemId = R.id.navigation_notifications
            }
            R.id.nav_preferences -> {
                startActivity(Intent(this, PreferencesActivity::class.java))
            }
            R.id.nav_help -> {
                startActivity(Intent(this, HelpActivity::class.java))
            }
            R.id.nav_report_issue -> {
                startActivity(Intent(this, ReportIssueActivity::class.java))
            }
            R.id.nav_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
            }
            R.id.nav_event_log -> {
                startActivity(Intent(this, EventLogActivity::class.java))
            }
        }
        
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
    
    private fun loadFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .commit()
    }
    
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart() called")
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() called")
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause() called")
    }
    
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop() called")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() called")
    }
    
    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "onRestart() called")
    }
}