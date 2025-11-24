package com.example.quadrantlauncher

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rikka.shizuku.Shizuku
import java.util.*

class FloatingLauncherService : Service() {

    private lateinit var windowManager: WindowManager
    private var displayContext: Context? = null // Context bound to the specific display
    
    private var bubbleView: View? = null
    private var drawerView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var drawerParams: WindowManager.LayoutParams

    private var isExpanded = false
    private val selectedAppsQueue = mutableListOf<MainActivity.AppInfo>()
    private val allAppsList = mutableListOf<MainActivity.AppInfo>()
    private val displayList = mutableListOf<Any>()
    
    private var currentMode = MODE_SEARCH
    private var shellService: IShellService? = null
    private var isBound = false

    companion object {
        const val MODE_SEARCH = 0
        const val MODE_FAVORITES = 1
        const val MODE_LAYOUTS = 2
        const val MODE_SETTINGS = 3
        const val CHANNEL_ID = "OverlayServiceChannel"
        const val TAG = "FloatingService"
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shellService = IShellService.Stub.asInterface(binder)
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            isBound = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        
        // Shizuku bind can stay in onCreate
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bindShizuku()
        }
        
        loadInstalledApps()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // PREVENT DOUBLE INIT if service is already running
        if (bubbleView != null) return START_NOT_STICKY

        // 1. Get Display ID from Intent
        val displayId = intent?.getIntExtra("DISPLAY_ID", Display.DEFAULT_DISPLAY) ?: Display.DEFAULT_DISPLAY
        Log.d(TAG, "Starting Service on Display ID: $displayId")

        // 2. Initialize UI on that specific Display
        try {
            setupDisplayContext(displayId)
            setupBubble()
            setupDrawer()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up UI on Display $displayId", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun setupDisplayContext(displayId: Int) {
        // CRITICAL: Create a context associated with the target display
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(displayId)
        
        if (display == null) {
            throw IllegalStateException("Display $displayId not found")
        }

        displayContext = createDisplayContext(display)
        
        // Get the WindowManager for THIS display context
        windowManager = displayContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun startForegroundService() {
        val channelId = if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "Floating Launcher", android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
            CHANNEL_ID
        } else ""

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("CoverScreen Launcher Active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun bindShizuku() {
        try {
            val component = ComponentName(packageName, ShellUserService::class.java.name)
            ShizukuBinder.bind(component, userServiceConnection, true, 1)
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku Bind Failed", e)
        }
    }

    private fun setupBubble() {
        // USE DISPLAY CONTEXT WRAPPED IN THEME
        if (displayContext == null) return
        val themeContext = ContextThemeWrapper(displayContext, R.style.Theme_QuadrantLauncher)
        
        bubbleView = LayoutInflater.from(themeContext).inflate(R.layout.layout_bubble, null)
        
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        
        bubbleParams.gravity = Gravity.TOP or Gravity.START
        bubbleParams.x = 50
        bubbleParams.y = 200

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f; var isDrag = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams.x; initialY = bubbleParams.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        isDrag = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(event.rawX - initialTouchX) > 10 || Math.abs(event.rawY - initialTouchY) > 10) isDrag = true
                        if (isDrag) {
                            bubbleParams.x = initialX + (event.rawX - initialTouchX).toInt()
                            bubbleParams.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(bubbleView, bubbleParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDrag) toggleDrawer()
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun setupDrawer() {
        if (displayContext == null) return
        val themeContext = ContextThemeWrapper(displayContext, R.style.Theme_QuadrantLauncher)
        
        drawerView = LayoutInflater.from(themeContext).inflate(R.layout.layout_rofi_drawer, null)
        
        drawerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        )
        drawerParams.dimAmount = 0.5f
        drawerParams.gravity = Gravity.CENTER
        
        val searchBar = drawerView!!.findViewById<EditText>(R.id.rofi_search_bar)
        val recycler = drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view)
        
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_window).setOnClickListener { switchMode(MODE_LAYOUTS) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_star).setOnClickListener { switchMode(MODE_FAVORITES) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_settings).setOnClickListener { switchMode(MODE_SETTINGS) }

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        recycler.layoutManager = LinearLayoutManager(themeContext) // Use theme context here too
        recycler.adapter = RofiAdapter()
        
        drawerView!!.isFocusableInTouchMode = true
        drawerView!!.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                toggleDrawer(); true
            } else false
        }
    }

    private fun toggleDrawer() {
        if (isExpanded) {
            windowManager.removeView(drawerView)
            bubbleView?.visibility = View.VISIBLE
            isExpanded = false
        } else {
            windowManager.addView(drawerView, drawerParams)
            bubbleView?.visibility = View.GONE
            isExpanded = true
            switchMode(MODE_SEARCH)
            val et = drawerView?.findViewById<EditText>(R.id.rofi_search_bar)
            et?.setText("")
            et?.requestFocus()
        }
    }

    // --- LOGIC ---

    private fun loadInstalledApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val riList = pm.queryIntentActivities(intent, 0)
        allAppsList.clear()
        for (ri in riList) {
            val app = MainActivity.AppInfo(
                ri.loadLabel(pm).toString(),
                ri.activityInfo.packageName,
                AppPreferences.isFavorite(this, ri.activityInfo.packageName)
            )
            allAppsList.add(app)
        }
        allAppsList.sortBy { it.label.lowercase() }
    }

    private fun switchMode(mode: Int) {
        currentMode = mode
        val searchBar = drawerView!!.findViewById<EditText>(R.id.rofi_search_bar)
        val iconWin = drawerView!!.findViewById<ImageView>(R.id.icon_mode_window)
        val iconStar = drawerView!!.findViewById<ImageView>(R.id.icon_mode_star)
        
        iconWin.setColorFilter(if(mode==MODE_LAYOUTS) Color.WHITE else Color.GRAY)
        iconStar.setColorFilter(if(mode==MODE_FAVORITES) Color.WHITE else Color.GRAY)

        displayList.clear()
        
        when (mode) {
            MODE_SEARCH -> {
                searchBar.hint = "Type to search..."
                displayList.addAll(allAppsList)
            }
            MODE_FAVORITES -> {
                searchBar.hint = "Search Favorites..."
                displayList.addAll(allAppsList.filter { it.isFavorite })
            }
            MODE_LAYOUTS -> {
                searchBar.hint = "Select Layout"
                displayList.add(LayoutOption("Split Screen (2 Apps)", 2))
                displayList.add(LayoutOption("Tri-Split (3 Apps)", 3))
                displayList.add(LayoutOption("Quadrant (4 Apps)", 4))
                displayList.add(LayoutOption("Clear Selection", -1))
            }
            MODE_SETTINGS -> { searchBar.hint = "Settings" }
        }
        drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view).adapter?.notifyDataSetChanged()
    }

    private fun filterList(query: String) {
        if (currentMode == MODE_LAYOUTS) return
        val masterList = if (currentMode == MODE_FAVORITES) allAppsList.filter { it.isFavorite } else allAppsList
        val actualQuery = query.substringAfterLast(",").trim()
        displayList.clear()
        if (actualQuery.isEmpty()) displayList.addAll(masterList)
        else displayList.addAll(masterList.filter { it.label.contains(actualQuery, ignoreCase = true) })
        drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view).adapter?.notifyDataSetChanged()
    }

    private fun addToSelection(app: MainActivity.AppInfo) {
        selectedAppsQueue.add(app)
        val sb = StringBuilder()
        for (a in selectedAppsQueue) sb.append(a.label).append(", ")
        val et = drawerView!!.findViewById<EditText>(R.id.rofi_search_bar)
        et.setText(sb.toString())
        et.setSelection(sb.length)
    }

    private fun toggleFavorite(app: MainActivity.AppInfo) {
        val newState = AppPreferences.toggleFavorite(this, app.packageName)
        app.isFavorite = newState
        allAppsList.find { it.packageName == app.packageName }?.isFavorite = newState
        Toast.makeText(this, if(newState) "Favorited" else "Unfavorited", Toast.LENGTH_SHORT).show()
        if (currentMode == MODE_FAVORITES) switchMode(MODE_FAVORITES)
    }

    private fun executeLaunch(layoutType: Int) {
        if (layoutType == -1) {
            selectedAppsQueue.clear()
            drawerView?.findViewById<EditText>(R.id.rofi_search_bar)?.setText("")
            return
        }
        if (selectedAppsQueue.isEmpty()) {
            Toast.makeText(this, "No apps selected!", Toast.LENGTH_SHORT).show()
            return
        }

        toggleDrawer() 
        
        val metrics = windowManager.maximumWindowMetrics
        val w = metrics.bounds.width()
        val h = metrics.bounds.height()
        val rects = mutableListOf<Rect>()
        
        when (layoutType) {
            2 -> { rects.add(Rect(0, 0, w, h/2)); rects.add(Rect(0, h/2, w, h)) }
            3 -> { rects.add(Rect(0, 0, w, h/2)); rects.add(Rect(0, h/2, w/2, h)); rects.add(Rect(w/2, h/2, w, h)) }
            4 -> { rects.add(Rect(0, 0, w/2, h/2)); rects.add(Rect(w/2, 0, w, h/2)); rects.add(Rect(0, h/2, w/2, h)); rects.add(Rect(w/2, h/2, w, h)) }
        }

        Thread {
            try {
                for (app in selectedAppsQueue) shellService?.forceStop(app.packageName)
                Thread.sleep(400)
                val count = Math.min(selectedAppsQueue.size, rects.size)
                for (i in 0 until count) {
                    launchAppAt(selectedAppsQueue[i].packageName, rects[i])
                    Thread.sleep(150)
                }
                // Handle overflow center apps
                if (selectedAppsQueue.size > rects.size) {
                    val centerRect = Rect(w/4, h/4, (w*0.75).toInt(), (h*0.75).toInt())
                    for (i in rects.size until selectedAppsQueue.size) {
                        launchAppAt(selectedAppsQueue[i].packageName, centerRect)
                        Thread.sleep(150)
                    }
                }
                selectedAppsQueue.clear()
            } catch (e: Exception) {}
        }.start()
    }

    private fun launchAppAt(pkg: String, bounds: Rect) {
        try {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val opts = android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds)
            startActivity(i, opts.toBundle())
        } catch (e: Exception) {}
    }

    data class LayoutOption(val name: String, val type: Int)

    inner class RofiAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class AppHolder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.rofi_app_icon)
            val text: android.widget.TextView = v.findViewById(R.id.rofi_app_text)
            val star: ImageView = v.findViewById(R.id.rofi_app_star)
        }
        inner class LayoutHolder(v: View) : RecyclerView.ViewHolder(v) {
            val text: android.widget.TextView = v.findViewById(android.R.id.text1)
        }

        override fun getItemViewType(position: Int): Int = if (displayList[position] is MainActivity.AppInfo) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_rofi, parent, false)
                AppHolder(v)
            } else {
                val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
                v.findViewById<android.widget.TextView>(android.R.id.text1).setTextColor(Color.WHITE)
                LayoutHolder(v)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AppHolder) {
                val app = displayList[position] as MainActivity.AppInfo
                holder.text.text = app.label
                holder.star.visibility = if (app.isFavorite) View.VISIBLE else View.GONE
                holder.itemView.setOnClickListener { addToSelection(app) }
                holder.itemView.setOnLongClickListener { toggleFavorite(app); true }
            } else if (holder is LayoutHolder) {
                val layout = displayList[position] as LayoutOption
                holder.text.text = layout.name
                holder.itemView.setOnClickListener { executeLaunch(layout.type) }
            }
        }
        override fun getItemCount() = displayList.size
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try { if (bubbleView != null) windowManager.removeView(bubbleView) } catch(e: Exception) {}
        try { if (isExpanded) windowManager.removeView(drawerView) } catch(e: Exception) {}
        if (isBound) ShizukuBinder.unbind(ComponentName(packageName, ShellUserService::class.java.name), userServiceConnection)
    }
}
