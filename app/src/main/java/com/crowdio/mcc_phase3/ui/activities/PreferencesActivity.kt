package com.crowdio.mcc_phase3.ui.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.crowdio.mcc_phase3.R
import com.google.android.material.appbar.MaterialToolbar

class PreferencesActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preferences)
        
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Preferences"
        
        toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }
}
