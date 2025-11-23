package com.example.quadrantlauncher

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    // -----------------------------------------------------------------------
    // Correct Shizuku.newProcess() reflection method for API 13.1.5
    //
    // newProcess(String[] argv, String[] envp, String cwd, int uid, int gid)
    // -----------------------------------------------------------------------
    private val newProcessMethod: Method by lazy {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val method = clazz.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,   // argv
            Array<String>::class.java,   // envp
            String::class.java,          // cwd
            Int::class.java,             // uid
            Int::class.java              // gid
        )
        method.isAccessible = true
        method
    }

    // -----------------------------------------------------------------------
    // Shizuku availability & permission
    // -----------------------------------------------------------------------
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    // Shizuku 13+ permission request (no Activity parameter)
    fun requestPermission() {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Log.w(TAG, "Shizuku: shouldShowRequestPermissionRationale() = true")
        }
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    }

    // -----------------------------------------------------------------------
    // Force-stop an app using Shizuku
    // -----------------------------------------------------------------------
    fun killApp(packageName: String) {
        if (!hasPermission()) {
            Log.w(TAG, "killApp: Shizuku permission not granted")
            return
        }

        try {
            val command = arrayOf("sh", "-c", "am force-stop $packageName")
            Log.i(TAG, "Executing kill: ${command.joinToString(" ")}")

            // Run as root in Shizuku
            val process = newProcessMethod.invoke(
                null,
                command,     // argv
                null,        // envp
                null,        // cwd
                0,           // uid = root
                0            // gid = root
            ) as Process

            // Capture stdout and stderr for debugging
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()

            val exitCode = process.waitFor()

            Log.i(TAG, "killApp stdout: $stdout")
            Log.e(TAG, "killApp stderr: $stderr")
            Log.i(TAG, "killApp exitCode = $exitCode")

            if (exitCode != 0) {
                Log.e(TAG, "Error: am force-stop failed (exit=$exitCode)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "killApp exception for $packageName", e)
        }
    }
}
