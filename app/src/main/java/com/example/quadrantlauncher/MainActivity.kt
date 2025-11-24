package com.example.quadrantlauncher

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        const val SELECTED_APP_PACKAGE = "SELECTED_APP_PACKAGE"
    }

    data class AppInfo(val label: String, val packageName: String, var isFavorite: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay Permission", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            finish()
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(0)
        }

        // --- FIX: Get the current Display ID (Cover Screen) ---
        val currentDisplayId = display?.displayId ?: android.view.Display.DEFAULT_DISPLAY

        // Pass it to the service
        val intent = Intent(this, FloatingLauncherService::class.java).apply {
            putExtra("DISPLAY_ID", currentDisplayId)
        }
        
        // Android 8.0+ requires foreground service start
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        Toast.makeText(this, "Launcher Bubble Started!", Toast.LENGTH_SHORT).show()
        finish() 
    }
}
