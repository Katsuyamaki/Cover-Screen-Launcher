package com.example.quadrantlauncher

import android.os.IBinder
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

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
            val scClass = if (Build.VERSION.SDK_INT >= 34) {
                try { Class.forName("com.android.server.display.DisplayControl") } catch (e: Exception) { Class.forName("android.view.SurfaceControl") }
            } else {
                Class.forName("android.view.SurfaceControl")
            }

            val getIdsMethod = scClass.getMethod("getPhysicalDisplayIds")
            val physicalIds = getIdsMethod.invoke(null) as LongArray

            if (physicalIds.isEmpty()) return

            val targetId = if (displayIndex >= 0 && displayIndex < physicalIds.size) {
                physicalIds[displayIndex]
            } else {
                physicalIds[0]
            }

            val getTokenMethod = scClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            val token = getTokenMethod.invoke(null, targetId) as? IBinder ?: return

            val mode = if (turnOff) 0 else 2 
            val ctrlClass = Class.forName("android.view.SurfaceControl")
            val setPowerMethod = ctrlClass.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
            setPowerMethod.invoke(null, token, mode)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set screen power", e)
        }
    }

    override fun repositionTask(packageName: String, left: Int, top: Int, right: Int, bottom: Int) {
        try {
            // 1. Run raw dumpsys without grep (more reliable across android versions/shells)
            // "dumpsys activity top" lists the top tasks for ALL displays
            val process = Runtime.getRuntime().exec("dumpsys activity top")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            var line: String?
            var targetTaskId = -1
            
            // 2. Parse the output in Kotlin
            // Looking for lines like: "TASK com.package id=123 ..."
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                
                // Check if this line describes a TASK and belongs to our package
                if (l.startsWith("TASK") && l.contains(packageName)) {
                    // Extract ID using Regex
                    val match = Regex("id=(\\d+)").find(l)
                    if (match != null) {
                        targetTaskId = match.groupValues[1].toInt()
                        Log.i(TAG, "Found Task ID $targetTaskId for $packageName")
                        break // Stop after finding the first match
                    }
                }
            }
            reader.close()
            process.waitFor()

            if (targetTaskId != -1) {
                // 3. Force Window Mode to Freeform (5)
                Runtime.getRuntime().exec("am task set-windowing-mode $targetTaskId 5").waitFor()
                
                // 4. Force Resize
                val cmd = "am task resize $targetTaskId $left $top $right $bottom"
                Log.i(TAG, "Executing: $cmd")
                Runtime.getRuntime().exec(cmd).waitFor()
                
            } else {
                Log.w(TAG, "Task ID not found for package: $packageName")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to reposition task", e)
        }
    }
}
