package com.example.quadrantlauncher

import android.os.IBinder
import android.os.Build
import android.util.Log
import java.lang.reflect.Method

class ShellUserService : IShellService.Stub() {

    private val TAG = "ShellUserService"

    override fun forceStop(packageName: String) {
        try {
            val process = Runtime.getRuntime().exec("am force-stop $packageName")
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill", e)
        }
    }

    override fun runCommand(command: String) {
        try {
            Log.i(TAG, "Running: $command")
            val process = Runtime.getRuntime().exec(command)
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
        }
    }

    override fun setScreenOff(displayIndex: Int, turnOff: Boolean) {
        try {
            // 1. Resolve the correct class for Display APIs (Android 14 vs Older)
            val scClass = if (Build.VERSION.SDK_INT >= 34) {
                try { Class.forName("com.android.server.display.DisplayControl") } catch (e: Exception) { Class.forName("android.view.SurfaceControl") }
            } else {
                Class.forName("android.view.SurfaceControl")
            }

            // 2. Get All Physical Display IDs
            val getIdsMethod = scClass.getMethod("getPhysicalDisplayIds")
            val physicalIds = getIdsMethod.invoke(null) as LongArray

            if (physicalIds.isEmpty()) {
                Log.e(TAG, "No physical displays found")
                return
            }

            // 3. Select the ID based on the user's requested index (0 or 1)
            // If the index is out of bounds, default to 0
            val targetId = if (displayIndex >= 0 && displayIndex < physicalIds.size) {
                physicalIds[displayIndex]
            } else {
                physicalIds[0]
            }

            // 4. Get the Token for that ID
            val getTokenMethod = scClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            val token = getTokenMethod.invoke(null, targetId) as? IBinder

            if (token == null) {
                Log.e(TAG, "Could not get display token for ID: $targetId")
                return
            }

            // 5. Set Power Mode (0 = OFF, 2 = ON)
            val mode = if (turnOff) 0 else 2 
            
            // The setDisplayPowerMode method is always on SurfaceControl
            val ctrlClass = Class.forName("android.view.SurfaceControl")
            val setPowerMethod = ctrlClass.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
            setPowerMethod.invoke(null, token, mode)
            
            Log.i(TAG, "Set Display Index $displayIndex (PhysID $targetId) to Power Mode: $mode")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set screen power", e)
        }
    }
}
