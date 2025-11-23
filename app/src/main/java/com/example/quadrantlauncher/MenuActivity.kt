package com.example.quadrantlauncher

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        val quadrantButton: Button = findViewById(R.id.button_quadrant)
        val splitButton: Button = findViewById(R.id.button_split)
        val triSplitButton: Button = findViewById(R.id.button_tri_split) // New

        quadrantButton.setOnClickListener {
            startActivity(Intent(this, QuadrantActivity::class.java))
        }

        splitButton.setOnClickListener {
            startActivity(Intent(this, SplitActivity::class.java))
        }

        // New click listener
        triSplitButton.setOnClickListener {
            startActivity(Intent(this, TriSplitActivity::class.java))
        }
    }
}