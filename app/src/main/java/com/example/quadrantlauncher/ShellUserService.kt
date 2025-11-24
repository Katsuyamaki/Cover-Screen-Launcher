package com.example.quadrantlauncher

import android.util.Log
import com.example.quadrantlauncher.IShellService

class ShellUserService : IShellService.Stub() {

    private val TAG = "ShellUserService"

    override fun forceStop(packageName: String) {
        try {
            Log.i(TAG, "Killing: $packageName")
            // Executes in Shizuku's shell (u:r:shell:s0)
            val process = Runtime.getRuntime().exec("am force-stop $packageName")
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill", e)
        }
    }
}
