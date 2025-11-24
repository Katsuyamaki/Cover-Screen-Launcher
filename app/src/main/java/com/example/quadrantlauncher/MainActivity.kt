package com.example.quadrantlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    companion object {
        const val SELECTED_APP_PACKAGE = "SELECTED_APP_PACKAGE"
    }

    data class AppInfo(val label: String, val packageName: String, var isFavorite: Boolean = false)

    private val allAppsList = mutableListOf<AppInfo>()
    private val displayedAppsList = mutableListOf<AppInfo>()
    
    private lateinit var tabLayout: TabLayout
    private lateinit var searchBar: EditText
    private lateinit var appRecyclerView: RecyclerView
    private lateinit var azRecyclerView: RecyclerView
    private lateinit var emptyView: TextView
    
    private lateinit var appAdapter: AppAdapter
    private lateinit var azAdapter: AZAdapter

    private var currentTabIndex = 0 // 0=Fav, 1=Search, 2=All

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabLayout = findViewById(R.id.tab_layout)
        searchBar = findViewById(R.id.search_bar)
        appRecyclerView = findViewById(R.id.app_list_recycler_view)
        azRecyclerView = findViewById(R.id.az_recycler_view)
        emptyView = findViewById(R.id.empty_view)

        appAdapter = AppAdapter(displayedAppsList, 
            onClick = { app -> returnResult(app.packageName) },
            onLongClick = { app -> toggleFavorite(app) }
        )
        appRecyclerView.layoutManager = LinearLayoutManager(this)
        appRecyclerView.adapter = appAdapter

        // A-Z Adapter
        val alphabet = ('A'..'Z').map { it.toString() }
        azAdapter = AZAdapter(alphabet) { letter ->
            searchBar.setText(letter)
            searchBar.setSelection(1) 
        }
        azRecyclerView.layoutManager = GridLayoutManager(this, 6)
        azRecyclerView.adapter = azAdapter

        loadInstalledApps()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTabIndex = tab?.position ?: 0
                updateUI()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateUI() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        updateUI()
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val allInfo: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)

        allAppsList.clear()
        for (ri in allInfo) {
            val pkg = ri.activityInfo.packageName
            val label = ri.loadLabel(pm).toString()
            val isFav = AppPreferences.isFavorite(this, pkg)
            allAppsList.add(AppInfo(label, pkg, isFav))
        }
        allAppsList.sortBy { it.label.lowercase() }
    }

    private fun toggleFavorite(app: AppInfo) {
        val isNowFav = AppPreferences.toggleFavorite(this, app.packageName)
        app.isFavorite = isNowFav
        
        val index = displayedAppsList.indexOf(app)
        if (index != -1) {
            if (currentTabIndex == 0 && !isNowFav) {
                displayedAppsList.removeAt(index)
                appAdapter.notifyItemRemoved(index)
            } else {
                appAdapter.notifyItemChanged(index)
            }
        } else {
            updateUI() 
        }
        allAppsList.find { it.packageName == app.packageName }?.isFavorite = isNowFav
        
        val msg = if (isNowFav) "Added to Favorites" else "Removed from Favorites"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (currentTabIndex == 1) {
            searchBar.visibility = View.VISIBLE
        } else {
            searchBar.visibility = View.GONE
        }

        displayedAppsList.clear()
        var showAZGrid = false

        when (currentTabIndex) {
            0 -> displayedAppsList.addAll(allAppsList.filter { it.isFavorite })
            1 -> {
                val query = searchBar.text.toString().trim()
                if (query.isEmpty()) showAZGrid = true
                else displayedAppsList.addAll(allAppsList.filter { it.label.contains(query, ignoreCase = true) })
            }
            2 -> displayedAppsList.addAll(allAppsList)
        }

        if (showAZGrid) {
            azRecyclerView.visibility = View.VISIBLE
            appRecyclerView.visibility = View.GONE
            emptyView.visibility = View.GONE
        } else {
            azRecyclerView.visibility = View.GONE
            appRecyclerView.visibility = View.VISIBLE
            emptyView.visibility = if (displayedAppsList.isEmpty()) View.VISIBLE else View.GONE
            appAdapter.notifyDataSetChanged()
        }
    }

    private fun returnResult(packageName: String) {
        val resultIntent = Intent()
        resultIntent.putExtra(SELECTED_APP_PACKAGE, packageName)
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    inner class AppAdapter(
        private val apps: List<AppInfo>,
        private val onClick: (AppInfo) -> Unit,
        private val onLongClick: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_app, parent, false)
            return AppViewHolder(view)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(apps[position])
        }

        override fun getItemCount() = apps.size

        inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameText: TextView = itemView.findViewById(R.id.app_name_text)
            private val starIcon: ImageView = itemView.findViewById(R.id.icon_star)

            fun bind(app: AppInfo) {
                nameText.text = app.label
                if (app.isFavorite) starIcon.setImageResource(R.drawable.ic_star_filled)
                else starIcon.setImageResource(R.drawable.ic_star_border)

                itemView.setOnClickListener { onClick(app) }
                itemView.setOnLongClickListener { onLongClick(app); true }
            }
        }
    }

    inner class AZAdapter(
        private val letters: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<AZAdapter.AZViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AZViewHolder {
            val view = TextView(parent.context)
            view.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150)
            view.gravity = android.view.Gravity.CENTER
            view.textSize = 24f
            
            // FORCE WHITE TEXT FOR VISIBILITY ON DARK BG
            view.setTextColor(android.graphics.Color.WHITE)
            view.setBackgroundResource(android.R.drawable.list_selector_background)
            
            return AZViewHolder(view)
        }

        override fun onBindViewHolder(holder: AZViewHolder, position: Int) {
            (holder.itemView as TextView).text = letters[position]
            holder.itemView.setOnClickListener { onClick(letters[position]) }
        }

        override fun getItemCount() = letters.size

        inner class AZViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
    }
}
