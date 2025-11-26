package com.example.quadrantlauncher

import android.os.IBinder
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.regex.Pattern

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
            val process = Runtime.getRuntime().exec("dumpsys activity top")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            var line: String?
            var targetTaskId = -1
            
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                if (l.startsWith("TASK") && l.contains(packageName)) {
                    // Robust ID extraction
                    val match = Regex("id=(\\d+)").find(l)
                    if (match != null) {
                        targetTaskId = match.groupValues[1].toInt()
                        break
                    }
                }
            }
            reader.close()
            process.waitFor()

            if (targetTaskId != -1) {
                Runtime.getRuntime().exec("am task set-windowing-mode $targetTaskId 5").waitFor()
                val cmd = "am task resize $targetTaskId $left $top $right $bottom"
                Runtime.getRuntime().exec(cmd).waitFor()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to reposition task", e)
        }
    }

    override fun getVisiblePackages(displayId: Int): List<String> {
        val packages = ArrayList<String>()
        try {
            val process = Runtime.getRuntime().exec("dumpsys activity activities")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            var line: String?
            var currentScanningDisplayId = -1 // Start invalid
            
            // Regex to capture: u0 com.package.name/
            // Matches: "u0 com.foo.bar/" or "u10 com.foo.bar/"
            val recordPattern = Pattern.compile("u\\d+\\s+([a-zA-Z0-9_.]+)/")

            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()

                // 1. Detect Display Headers (e.g., "Display #0 (activities):")
                if (l.startsWith("Display #")) {
                    val displayMatch = Regex("Display #(\\d+)").find(l)
                    if (displayMatch != null) {
                        currentScanningDisplayId = displayMatch.groupValues[1].toInt()
                    }
                    continue
                }

                // 2. Only parse if we are inside the requested display block
                if (currentScanningDisplayId == displayId) {
                    // 3. Look for ActivityRecords
                    // Standard format: "* Hist #0: ActivityRecord{... u0 com.package/...}"
                    if (l.contains("ActivityRecord{")) {
                        val matcher = recordPattern.matcher(l)
                        if (matcher.find()) {
                            val pkg = matcher.group(1)
                            if (pkg != null && !packages.contains(pkg) && isUserApp(pkg)) {
                                packages.add(pkg)
                            }
                        }
                    }
                }
            }
            reader.close()
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get visible packages", e)
        }
        return packages
    }

    private fun isUserApp(pkg: String): Boolean {
        if (pkg == "com.android.systemui") return false
        if (pkg == "com.android.launcher3") return false // Generic
        if (pkg == "com.sec.android.app.launcher") return false // Samsung OneUI Home
        if (pkg == "com.example.quadrantlauncher") return false // Self
        return true
    }
}
