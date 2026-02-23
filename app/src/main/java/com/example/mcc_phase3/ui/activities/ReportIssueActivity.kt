package com.example.mcc_phase3.ui.activities

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.mcc_phase3.R
import com.google.android.material.appbar.MaterialToolbar

class ReportIssueActivity : AppCompatActivity() {
    
    private lateinit var titleInput: EditText
    private lateinit var descriptionInput: EditText
    private lateinit var submitButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_issue)
        
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Report Issue"
        
        toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
        
        titleInput = findViewById(R.id.issue_title_input)
        descriptionInput = findViewById(R.id.issue_description_input)
        submitButton = findViewById(R.id.submit_button)
        
        submitButton.setOnClickListener {
            submitIssue()
        }
    }
    
    private fun submitIssue() {
        val title = titleInput.text.toString().trim()
        val description = descriptionInput.text.toString().trim()
        
        if (title.isEmpty() || description.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }
        
        // TODO: Send issue to backend
        Toast.makeText(this, "Issue reported successfully", Toast.LENGTH_SHORT).show()
        finish()
    }
}
