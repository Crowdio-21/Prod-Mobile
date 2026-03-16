package com.example.mcc_phase3.ui.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mcc_phase3.R
import com.example.mcc_phase3.ui.adapters.EventLogAdapter
import com.example.mcc_phase3.utils.EventLogger
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class EventLogActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: EventLogAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_log)
        
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Event Log"
        
        toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
        
        // Setup RecyclerView
        adapter = EventLogAdapter()
        recyclerView = findViewById(R.id.event_log_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        // Load events and observe changes
        loadEvents()
        observeEvents()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_event_log, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear -> {
                EventLogger.clear()
                true
            }
            R.id.action_refresh -> {
                loadEvents()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun loadEvents() {
        val events = EventLogger.getEvents().reversed() // Show newest first
        adapter.submitList(events)
        
        // Scroll to top when loading
        if (events.isNotEmpty()) {
            recyclerView.scrollToPosition(0)
        }
    }
    
    private fun observeEvents() {
        lifecycleScope.launch {
            EventLogger.eventsFlow.collect { events ->
                adapter.submitList(events.reversed()) // Show newest first
                
                // Auto-scroll to top for new events
                if (events.isNotEmpty()) {
                    recyclerView.scrollToPosition(0)
                }
            }
        }
    }
}
