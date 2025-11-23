package com.example.quadrantlauncher // <-- YOUR PACKAGE NAME

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    // ADD THIS COMPANION OBJECT AT THE TOP OF THE CLASS
    // This gives us a constant key to use for passing data
    companion object {
        const val SELECTED_APP_PACKAGE = "SELECTED_APP_PACKAGE"
    }

    data class AppInfo(val label: String, val packageName: String)

    private val appList = mutableListOf<AppInfo>()
    private lateinit var appAdapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recyclerView = findViewById<RecyclerView>(R.id.app_list_recycler_view)

        appAdapter = AppAdapter(appList) { app ->
            
            // --- THIS IS THE PART TO CHANGE ---
            // DELETE the Toast.makeText line
            // REPLACE IT with these 4 lines:

            val resultIntent = Intent()
            resultIntent.putExtra(SELECTED_APP_PACKAGE, app.packageName)
            setResult(Activity.RESULT_OK, resultIntent)
            finish() // This closes the app list and returns to QuadrantActivity
            
            // --- END OF CHANGE ---
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = appAdapter

        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        val pm = packageManager // Get the Android Package Manager

        // Create an intent to find apps that can be "launched"
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        // Get the list of all apps that match our intent
        // We use "ResolveInfo" which contains app metadata
        val allApps: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)

        // Clear the list before adding new ones
        appList.clear()

        // Loop through the results and extract the info we want
        for (ri in allApps) {
            val app = AppInfo(
                label = ri.loadLabel(pm).toString(),
                packageName = ri.activityInfo.packageName
            )
            appList.add(app)
        }

        // Sort the list alphabetically by app name
        appList.sortBy { it.label.lowercase() }

        // Tell the adapter that the data has changed, so it updates the UI
        appAdapter.notifyDataSetChanged()
    }

    // --- This is the Adapter ---
    // It manages creating views and binding data for the RecyclerView
    inner class AppAdapter(
        private val apps: List<AppInfo>,
        private val onClick: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

        // This creates the actual view for a single row
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.list_item_app, parent, false)
            return AppViewHolder(view)
        }

        // This binds the data (app name) to the view for a specific row
        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val app = apps[position]
            holder.bind(app, onClick)
        }

        // Tells the list how many items there are
        override fun getItemCount() = apps.size

        // This class holds the view items for a single row
        inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val appNameTextView: TextView = itemView.findViewById(R.id.app_name_text)

            fun bind(app: AppInfo, onClick: (AppInfo) -> Unit) {
                appNameTextView.text = app.label
                itemView.setOnClickListener { onClick(app) }
            }
        }
    }
}