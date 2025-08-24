package com.example.mcc_phase3

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.mcc_phase3.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate: Starting MainActivity")
        super.onCreate(savedInstanceState)
        
        try {
            Log.d(TAG, "onCreate: Inflating binding")
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Log.d(TAG, "onCreate: Binding inflated successfully")
            
            setupNavigation()
            Log.d(TAG, "onCreate: MainActivity setup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Error setting up MainActivity", e)
            throw e
        }
    }
    
    private fun setupNavigation() {
        try {
            Log.d(TAG, "setupNavigation: Setting up navigation")
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            Log.d(TAG, "setupNavigation: NavHostFragment found: $navHostFragment")
            
            val navController = navHostFragment.navController
            Log.d(TAG, "setupNavigation: NavController obtained: $navController")
            
            binding.bottomNavigation.setupWithNavController(navController)
            Log.d(TAG, "setupNavigation: BottomNavigation setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "setupNavigation: Error setting up navigation", e)
            throw e
        }
    }
    
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: MainActivity started")
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: MainActivity resumed")
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: MainActivity paused")
    }
    
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: MainActivity stopped")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: MainActivity destroyed")
    }
}