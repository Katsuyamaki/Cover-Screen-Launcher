package com.example.quadrantlauncher

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    // Reflection to get the newProcess method that works for "User" (ADB)
    private val newProcessMethod: Method by lazy {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val method = clazz.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,   // command array
            Array<String>::class.java,   // environment
            String::class.java           // directory
        )
        method.isAccessible = true
        method
    }

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

    fun requestPermission() {
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    }

    fun killApp(packageName: String) {
        if (!hasPermission()) {
            Log.e(TAG, "killApp: Permission denied")
            return
        }

        try {
            // THE FIX:
            // 1. We use /system/bin/sh to ensure we have a shell.
            // 2. We use /system/bin/am to ensure we find the 'am' command.
            // 3. We do NOT use 'adb shell'.
            
            val fullCommand = "/system/bin/am force-stop "
            val argv = arrayOf("/system/bin/sh", "-c", fullCommand)

            Log.i(TAG, "Requesting kill: ")

            val process = newProcessMethod.invoke(
                null,
                argv,        // The command: sh -c "am force-stop pkg"
                null,        // Env
                null         // Dir
            ) as Process

            val exitCode = process.waitFor()

            // READ THE OUTPUT (Critical for debugging)
            val stdOut = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stdErr = BufferedReader(InputStreamReader(process.errorStream)).readText()

            if (exitCode == 0) {
                Log.i(TAG, "Kill Success (). Output: ")
            } else {
                Log.e(TAG, "Kill FAILED (). Error: ")
            }

        } catch (e: Exception) {
            Log.e(TAG, "killApp CRASHED", e)
        }
    }
}
