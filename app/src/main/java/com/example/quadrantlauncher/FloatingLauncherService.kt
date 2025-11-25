package com.example.quadrantlauncher

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Color
import android.net.Uri
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rikka.shizuku.Shizuku
import java.util.*

class FloatingLauncherService : Service() {

    private lateinit var windowManager: WindowManager
    private var displayContext: Context? = null
    private var currentDisplayId = 0
    
    private var bubbleView: View? = null
    private var drawerView: View? = null
    
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var drawerParams: WindowManager.LayoutParams

    private var isExpanded = false
    private val selectedAppsQueue = mutableListOf<MainActivity.AppInfo>()
    private val allAppsList = mutableListOf<MainActivity.AppInfo>()
    private val displayList = mutableListOf<Any>()
    
    private var currentMode = MODE_SEARCH
    private var selectedLayoutType = 2
    private var selectedResolutionIndex = 0
    private var currentDpiSetting = -1
    private var currentFontSize = 16f
    
    // Settings
    private var disableAppKill = false
    private var targetDisplayIndex = 1
    private var resetTrackpad = false
    private var isExtinguished = false
    
    // TODO: UPDATE THIS TO THE REAL PACKAGE NAME OF YOUR TRACKPAD APP
    private val TRACKPAD_PACKAGE = "com.katsuyamaki.trackpad"
    
    private var shellService: IShellService? = null
    private var isBound = false

    companion object {
        const val MODE_SEARCH = 0
        const val MODE_LAYOUTS = 2
        const val MODE_RESOLUTION = 3
        const val MODE_DPI = 4
        const val MODE_PROFILES = 5
        const val MODE_SETTINGS = 6
        
        const val LAYOUT_SIDE_BY_SIDE = 2
        const val LAYOUT_TRI_EVEN = 3
        const val LAYOUT_CORNERS = 4
        const val LAYOUT_TOP_BOTTOM = 5

        const val CHANNEL_ID = "OverlayServiceChannel"
        const val TAG = "FloatingService"
        const val ACTION_OPEN_DRAWER = "com.example.quadrantlauncher.OPEN_DRAWER"
        const val ACTION_UPDATE_ICON = "com.example.quadrantlauncher.UPDATE_ICON"
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_OPEN_DRAWER) {
                if (isExtinguished) {
                    wakeUp()
                } else if (!isExpanded) {
                    toggleDrawer()
                }
            } else if (intent?.action == ACTION_UPDATE_ICON) {
                updateBubbleIcon()
            }
        }
    }

    private val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val pos = viewHolder.adapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            
            if (currentMode == MODE_PROFILES) {
                val item = displayList.getOrNull(pos) as? ProfileOption ?: return
                if (item.isCurrent) {
                     (drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view).adapter as RofiAdapter).notifyItemChanged(pos)
                     return
                }
                AppPreferences.deleteProfile(this@FloatingLauncherService, item.name)
                Toast.makeText(this@FloatingLauncherService, "Deleted ${item.name}", Toast.LENGTH_SHORT).show()
                switchMode(MODE_PROFILES)
                return
            }

            if (currentMode == MODE_SEARCH) {
                val item = displayList.getOrNull(pos) as? MainActivity.AppInfo ?: return
                if (direction == ItemTouchHelper.LEFT && !item.isFavorite) toggleFavorite(item)
                else if (direction == ItemTouchHelper.RIGHT && item.isFavorite) toggleFavorite(item)
                refreshSearchList()
            }
        }
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
        
        val filter = IntentFilter().apply {
            addAction(ACTION_OPEN_DRAWER)
            addAction(ACTION_UPDATE_ICON)
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bindShizuku()
        }
        loadInstalledApps()
        currentFontSize = AppPreferences.getFontSize(this)
        disableAppKill = AppPreferences.getDisableKill(this)
        targetDisplayIndex = AppPreferences.getTargetDisplayIndex(this)
        resetTrackpad = AppPreferences.getResetTrackpad(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubbleView != null) return START_NOT_STICKY

        val displayId = intent?.getIntExtra("DISPLAY_ID", Display.DEFAULT_DISPLAY) ?: Display.DEFAULT_DISPLAY
        try {
            setupDisplayContext(displayId)
            setupBubble()
            setupDrawer()
            selectedLayoutType = AppPreferences.getLastLayout(this)
            selectedResolutionIndex = AppPreferences.getLastResolution(this)
            currentDpiSetting = AppPreferences.getLastDpi(this)
            updateGlobalFontSize()
            updateBubbleIcon()
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setupDisplayContext(displayId: Int) {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(displayId)
        if (display == null) {
             windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
             return
        }
        currentDisplayId = displayId
        displayContext = createDisplayContext(display)
        windowManager = displayContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun startForegroundService() {
        val channelId = if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = android.app.NotificationChannel(CHANNEL_ID, "Floating Launcher", android.app.NotificationManager.IMPORTANCE_LOW)
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
        } catch (e: Exception) {}
    }

    private fun setupBubble() {
        val context = displayContext ?: this
        val themeContext = ContextThemeWrapper(context, R.style.Theme_QuadrantLauncher)
        bubbleView = LayoutInflater.from(themeContext).inflate(R.layout.layout_bubble, null)
        bubbleView?.isClickable = true; bubbleView?.isFocusable = true 

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        bubbleParams.gravity = Gravity.TOP or Gravity.START
        bubbleParams.x = 50; bubbleParams.y = 200

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f; var isDrag = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams.x; initialY = bubbleParams.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY; isDrag = false
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

    private fun updateBubbleIcon() {
        try {
            val uriStr = AppPreferences.getIconUri(this)
            val iconView = bubbleView?.findViewById<ImageView>(R.id.bubble_icon)
            
            if (uriStr != null) {
                val uri = Uri.parse(uriStr)
                var input = contentResolver.openInputStream(uri)
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeStream(input, null, options)
                input?.close()
                options.inSampleSize = calculateInSampleSize(options, 150, 150)
                options.inJustDecodeBounds = false
                input = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(input, null, options)
                input?.close()
                if (bitmap != null) {
                    iconView?.setImageBitmap(bitmap)
                    iconView?.imageTintList = null 
                    iconView?.clearColorFilter()   
                } else {
                    iconView?.setImageResource(R.mipmap.ic_launcher_round)
                    iconView?.setColorFilter(Color.WHITE)
                }
            } else {
                iconView?.setImageResource(R.mipmap.ic_launcher_round)
                iconView?.setColorFilter(Color.WHITE)
            }
        } catch (e: Exception) {
            val iconView = bubbleView?.findViewById<ImageView>(R.id.bubble_icon)
            iconView?.setImageResource(R.mipmap.ic_launcher_round)
            iconView?.setColorFilter(Color.WHITE)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun setupDrawer() {
        val context = displayContext ?: this
        val themeContext = ContextThemeWrapper(context, R.style.Theme_QuadrantLauncher)
        drawerView = LayoutInflater.from(themeContext).inflate(R.layout.layout_rofi_drawer, null)
        
        drawerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0, 
            PixelFormat.TRANSLUCENT
        )
        
        val searchBar = drawerView!!.findViewById<EditText>(R.id.rofi_search_bar)
        val recycler = drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view)
        
        drawerView!!.findViewById<ImageView>(R.id.icon_search_mode).setOnClickListener { switchMode(MODE_SEARCH) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_window).setOnClickListener { switchMode(MODE_LAYOUTS) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_resolution).setOnClickListener { switchMode(MODE_RESOLUTION) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_dpi).setOnClickListener { switchMode(MODE_DPI) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_profiles).setOnClickListener { switchMode(MODE_PROFILES) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_settings).setOnClickListener { switchMode(MODE_SETTINGS) }
        drawerView!!.findViewById<ImageView>(R.id.icon_execute).setOnClickListener { executeLaunch(selectedLayoutType) }
        
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        recycler.layoutManager = LinearLayoutManager(themeContext)
        recycler.adapter = RofiAdapter()
        
        val itemTouchHelper = ItemTouchHelper(swipeCallback)
        itemTouchHelper.attachToRecyclerView(recycler)
        
        drawerView!!.setOnClickListener { toggleDrawer() }
        drawerView!!.isFocusableInTouchMode = true
        drawerView!!.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { toggleDrawer(); true } 
            else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && isExtinguished) {
                wakeUp()
                true
            }
            else false
        }
    }

    private fun updateGlobalFontSize() {
        val searchBar = drawerView?.findViewById<EditText>(R.id.rofi_search_bar)
        searchBar?.textSize = currentFontSize
        drawerView?.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
    }

    private fun toggleDrawer() {
        if (isExpanded) {
            try { windowManager.removeView(drawerView) } catch(e: Exception) {}
            bubbleView?.visibility = View.VISIBLE
            isExpanded = false
        } else {
            try { windowManager.addView(drawerView, drawerParams) } catch(e: Exception) {}
            bubbleView?.visibility = View.GONE
            isExpanded = true
            switchMode(MODE_SEARCH) 
            val et = drawerView?.findViewById<EditText>(R.id.rofi_search_bar)
            et?.setText("")
            et?.requestFocus()
        }
    }

    // --- EXTINGUISH LOGIC ---
    private fun performExtinguish() {
        toggleDrawer()
        isExtinguished = true
        
        Thread {
            try {
                shellService?.setScreenOff(targetDisplayIndex, true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
        Toast.makeText(this, "Screen OFF (Index $targetDisplayIndex). Vol+ to Wake.", Toast.LENGTH_SHORT).show()
    }

    private fun wakeUp() {
        isExtinguished = false
        Thread { 
            shellService?.setScreenOff(0, false) 
            shellService?.setScreenOff(1, false)
        }.start()
        Toast.makeText(this, "Screen Woke Up", Toast.LENGTH_SHORT).show()
    }

    // --- TRACKPAD LOGIC ---
    private fun restartTrackpad() {
        try {
            // 1. Kill
            shellService?.forceStop(TRACKPAD_PACKAGE)
            // 2. Start (Launch Intent)
            val i = packageManager.getLaunchIntentForPackage(TRACKPAD_PACKAGE)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(i)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset trackpad", e)
        }
    }

    // --- OTHER LOGIC ---

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
        val searchIcon = drawerView!!.findViewById<ImageView>(R.id.icon_search_mode)
        val iconWin = drawerView!!.findViewById<ImageView>(R.id.icon_mode_window)
        val iconRes = drawerView!!.findViewById<ImageView>(R.id.icon_mode_resolution)
        val iconDpi = drawerView!!.findViewById<ImageView>(R.id.icon_mode_dpi)
        val iconProf = drawerView!!.findViewById<ImageView>(R.id.icon_mode_profiles)
        val iconSet = drawerView!!.findViewById<ImageView>(R.id.icon_mode_settings)
        val executeBtn = drawerView!!.findViewById<ImageView>(R.id.icon_execute)
        
        searchIcon.setColorFilter(if(mode==MODE_SEARCH) Color.WHITE else Color.GRAY)
        iconWin.setColorFilter(if(mode==MODE_LAYOUTS) Color.WHITE else Color.GRAY)
        iconRes.setColorFilter(if(mode==MODE_RESOLUTION) Color.WHITE else Color.GRAY)
        iconDpi.setColorFilter(if(mode==MODE_DPI) Color.WHITE else Color.GRAY)
        iconProf.setColorFilter(if(mode==MODE_PROFILES) Color.WHITE else Color.GRAY)
        iconSet.setColorFilter(if(mode==MODE_SETTINGS) Color.WHITE else Color.GRAY)

        executeBtn.visibility = View.VISIBLE 
        
        displayList.clear()
        
        when (mode) {
            MODE_SEARCH -> {
                searchBar.hint = "Search apps..."
                refreshSearchList()
            }
            MODE_LAYOUTS -> {
                searchBar.hint = "Select Layout"
                displayList.add(LayoutOption("2 Apps - Side by Side", LAYOUT_SIDE_BY_SIDE))
                displayList.add(LayoutOption("2 Apps - Top & Bottom", LAYOUT_TOP_BOTTOM))
                displayList.add(LayoutOption("3 Apps - Even", LAYOUT_TRI_EVEN))
                displayList.add(LayoutOption("4 Apps - Corners", LAYOUT_CORNERS))
            }
            MODE_RESOLUTION -> {
                searchBar.hint = "Select Resolution"
                displayList.add(ResolutionOption("Default (Reset)", "wm size reset -d $currentDisplayId", 0))
                displayList.add(ResolutionOption("1:1 Square (1422x1500)", "wm size 1422x1500 -d $currentDisplayId", 1))
                displayList.add(ResolutionOption("16:9 Landscape (1920x1080)", "wm size 1920x1080 -d $currentDisplayId", 2))
                displayList.add(ResolutionOption("32:9 Ultrawide (3840x1080)", "wm size 3840x1080 -d $currentDisplayId", 3))
            }
            MODE_DPI -> {
                searchBar.hint = "Adjust Density (DPI)"
                displayList.add(ResolutionOption("Reset Density (Default)", "wm density reset -d $currentDisplayId", -1))
                var savedDpi = currentDpiSetting
                if (savedDpi <= 0) {
                    savedDpi = displayContext?.resources?.configuration?.densityDpi ?: 160
                }
                displayList.add(DpiOption(savedDpi))
            }
            MODE_PROFILES -> {
                searchBar.hint = "Enter Profile Name..."
                displayList.add(ProfileOption("Current Profile", true))
                val profiles = AppPreferences.getProfileNames(this).sorted()
                for (p in profiles) {
                    displayList.add(ProfileOption(p, false))
                }
            }
            MODE_SETTINGS -> {
                searchBar.hint = "Settings"
                displayList.add(FontSizeOption(currentFontSize))
                displayList.add(IconOption("Launcher Icon (Tap to Change)"))
                
                // Settings Toggles
                displayList.add(ToggleOption("Disable App Kill (Faster)", disableAppKill) { 
                    disableAppKill = it
                    AppPreferences.setDisableKill(this, it)
                })
                
                val targetText = if (targetDisplayIndex == 1) "Target: Cover Screen (1)" else "Target: Main Screen (0)"
                // IMPORTANT: The 'ToggleOption' here also serves as the row for the power button
                displayList.add(ToggleOption(targetText, targetDisplayIndex == 1) { 
                    targetDisplayIndex = if (it) 1 else 0
                    AppPreferences.setTargetDisplayIndex(this, targetDisplayIndex)
                    switchMode(MODE_SETTINGS)
                })

                displayList.add(ToggleOption("Reset Trackpad on Execute", resetTrackpad) {
                    resetTrackpad = it
                    AppPreferences.setResetTrackpad(this, it)
                })
            }
        }
        drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view).adapter?.notifyDataSetChanged()
    }

    private fun refreshSearchList() {
        val query = drawerView?.findViewById<EditText>(R.id.rofi_search_bar)?.text?.toString() ?: ""
        filterList(query)
    }

    private fun filterList(query: String) {
        if (currentMode != MODE_SEARCH) return
        val actualQuery = query.substringAfterLast(",").trim()
        displayList.clear()
        val filtered = if (actualQuery.isEmpty()) {
            allAppsList
        } else {
            allAppsList.filter { it.label.contains(actualQuery, ignoreCase = true) }
        }
        val sorted = filtered.sortedWith(compareByDescending<MainActivity.AppInfo> { it.isFavorite }
            .thenBy { it.label.lowercase() })
        displayList.addAll(sorted)
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
    }

    private fun selectLayout(type: Int) {
        selectedLayoutType = type
        AppPreferences.saveLastLayout(this, type)
        (drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view).adapter as RofiAdapter).notifyDataSetChanged()
    }

    private fun applyResolution(opt: ResolutionOption) {
        if (opt.index != -1) { 
            selectedResolutionIndex = opt.index
            AppPreferences.saveLastResolution(this, opt.index)
        }
        (drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view).adapter as RofiAdapter).notifyDataSetChanged()
    }
    
    private fun selectDpi(value: Int) {
        currentDpiSetting = value.coerceIn(100, 400)
        AppPreferences.saveLastDpi(this, currentDpiSetting)
    }
    
    private fun changeFontSize(newSize: Float) {
        currentFontSize = newSize.coerceIn(10f, 30f)
        AppPreferences.saveFontSize(this, currentFontSize)
        updateGlobalFontSize()
        if (currentMode == MODE_SETTINGS) {
            switchMode(MODE_SETTINGS)
        }
    }
    
    private fun pickIcon() {
        toggleDrawer()
        try {
            val intent = Intent(this, IconPickerActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot start picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveProfile() {
        val name = drawerView?.findViewById<EditText>(R.id.rofi_search_bar)?.text?.toString()?.trim()
        if (name.isNullOrEmpty()) {
            Toast.makeText(this, "Enter a profile name in the search bar", Toast.LENGTH_SHORT).show()
            return
        }
        val pkgs = selectedAppsQueue.map { it.packageName }
        AppPreferences.saveProfile(this, name, selectedLayoutType, selectedResolutionIndex, currentDpiSetting, pkgs)
        Toast.makeText(this, "Saved Profile: $name", Toast.LENGTH_SHORT).show()
        drawerView?.findViewById<EditText>(R.id.rofi_search_bar)?.setText("")
        switchMode(MODE_PROFILES)
    }

    private fun loadProfile(name: String) {
        val data = AppPreferences.getProfileData(this, name) ?: return
        try {
            val parts = data.split("|")
            selectedLayoutType = parts[0].toInt()
            selectedResolutionIndex = parts[1].toInt()
            currentDpiSetting = parts[2].toInt()
            val pkgList = parts[3].split(",")
            
            selectedAppsQueue.clear()
            for (pkg in pkgList) {
                if (pkg.isNotEmpty()) {
                    val app = allAppsList.find { it.packageName == pkg }
                    if (app != null) selectedAppsQueue.add(app)
                }
            }
            
            AppPreferences.saveLastLayout(this, selectedLayoutType)
            AppPreferences.saveLastResolution(this, selectedResolutionIndex)
            AppPreferences.saveLastDpi(this, currentDpiSetting)
            
            Toast.makeText(this, "Loaded Profile: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load profile", e)
        }
    }

    private fun getResolutionCommand(index: Int): String {
        return when(index) {
            1 -> "wm size 1422x1500 -d $currentDisplayId"
            2 -> "wm size 1920x1080 -d $currentDisplayId"
            3 -> "wm size 3840x1080 -d $currentDisplayId"
            else -> "wm size reset -d $currentDisplayId"
        }
    }
    
    private fun getTargetDimensions(index: Int): Pair<Int, Int>? {
        return when(index) {
            1 -> 1422 to 1500
            2 -> 1920 to 1080
            3 -> 3840 to 1080
            else -> null 
        }
    }

    private fun executeLaunch(layoutType: Int) {
        toggleDrawer() 
        
        val targetDim = getTargetDimensions(selectedResolutionIndex)
        val w = targetDim?.first ?: windowManager.maximumWindowMetrics.bounds.width()
        val h = targetDim?.second ?: windowManager.maximumWindowMetrics.bounds.height()
        
        val rects = mutableListOf<Rect>()
        
        when (layoutType) {
            LAYOUT_SIDE_BY_SIDE -> { 
                rects.add(Rect(0, 0, w/2, h))
                rects.add(Rect(w/2, 0, w, h)) 
            }
            LAYOUT_TOP_BOTTOM -> { 
                rects.add(Rect(0, 0, w, h/2))
                rects.add(Rect(0, h/2, w, h))
            }
            LAYOUT_TRI_EVEN -> { 
                val third = w / 3
                rects.add(Rect(0, 0, third, h))
                rects.add(Rect(third, 0, third * 2, h))
                rects.add(Rect(third * 2, 0, w, h))
            }
            LAYOUT_CORNERS -> { 
                rects.add(Rect(0, 0, w/2, h/2))
                rects.add(Rect(w/2, 0, w, h/2))
                rects.add(Rect(0, h/2, w/2, h))
                rects.add(Rect(w/2, h/2, w, h))
            }
        }

        Thread {
            try {
                val resCmd = getResolutionCommand(selectedResolutionIndex)
                shellService?.runCommand(resCmd)
                
                if (currentDpiSetting > 0) {
                     val dpiCmd = "wm density $currentDpiSetting -d $currentDisplayId"
                     shellService?.runCommand(dpiCmd)
                } else {
                     if (currentDpiSetting == -1) shellService?.runCommand("wm density reset -d $currentDisplayId")
                }

                Thread.sleep(600)
                
                // --- TRACKPAD RESET ---
                if (resetTrackpad) {
                    restartTrackpad()
                    Thread.sleep(200)
                }

                if (selectedAppsQueue.isNotEmpty()) {
                    if (!disableAppKill) {
                        for (app in selectedAppsQueue) shellService?.forceStop(app.packageName)
                        Thread.sleep(400)
                    } else {
                        Thread.sleep(100)
                    }
                    
                    val count = Math.min(selectedAppsQueue.size, rects.size)
                    for (i in 0 until count) {
                        launchAppAt(selectedAppsQueue[i].packageName, rects[i])
                        Thread.sleep(150)
                    }
                    
                    if (selectedAppsQueue.size > rects.size) {
                        val centerRect = Rect(w/4, h/4, (w*0.75).toInt(), (h*0.75).toInt())
                        for (i in rects.size until selectedAppsQueue.size) {
                            launchAppAt(selectedAppsQueue[i].packageName, centerRect)
                            Thread.sleep(150)
                        }
                    }
                    selectedAppsQueue.clear()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Execute Failed", e)
            }
        }.start()
        
        drawerView?.findViewById<EditText>(R.id.rofi_search_bar)?.setText("")
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
    data class ResolutionOption(val name: String, val command: String, val index: Int)
    data class DpiOption(val currentDpi: Int)
    data class ProfileOption(val name: String, val isCurrent: Boolean)
    data class FontSizeOption(val currentSize: Float)
    data class IconOption(val name: String)
    data class ToggleOption(val name: String, var isEnabled: Boolean, val onToggle: (Boolean) -> Unit)

    inner class RofiAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        
        inner class AppHolder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.rofi_app_icon)
            val text: android.widget.TextView = v.findViewById(R.id.rofi_app_text)
            val star: ImageView = v.findViewById(R.id.rofi_app_star)
        }
        
        inner class LayoutHolder(v: View) : RecyclerView.ViewHolder(v) {
            val text: android.widget.TextView = v.findViewById(R.id.layout_name)
            val check: ImageView = v.findViewById(R.id.layout_check_icon)
            val btnSave: ImageView = v.findViewById(R.id.btn_save_profile)
            val btnExtinguish: ImageView = v.findViewById(R.id.btn_extinguish_item) // New Button
        }
        
        inner class DpiHolder(v: View) : RecyclerView.ViewHolder(v) {
            val btnMinus: ImageView = v.findViewById(R.id.btn_dpi_minus)
            val btnPlus: ImageView = v.findViewById(R.id.btn_dpi_plus)
            val input: EditText = v.findViewById(R.id.input_dpi_value)
        }

        inner class FontSizeHolder(v: View) : RecyclerView.ViewHolder(v) {
            val btnMinus: ImageView = v.findViewById(R.id.btn_font_minus)
            val btnPlus: ImageView = v.findViewById(R.id.btn_font_plus)
            val textVal: TextView = v.findViewById(R.id.text_font_value)
        }

        override fun getItemViewType(position: Int): Int {
            return when (displayList[position]) {
                is MainActivity.AppInfo -> 0
                is LayoutOption -> 1
                is ResolutionOption -> 1 
                is DpiOption -> 2
                is ProfileOption -> 1
                is FontSizeOption -> 3
                is IconOption -> 1
                is ToggleOption -> 1
                else -> 0
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                0 -> AppHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_app_rofi, parent, false))
                1 -> LayoutHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_layout_option, parent, false))
                2 -> DpiHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_dpi_custom, parent, false))
                3 -> FontSizeHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_font_size, parent, false))
                else -> AppHolder(View(parent.context))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = displayList[position]
            
            if (holder is AppHolder) holder.text.textSize = currentFontSize
            if (holder is LayoutHolder) holder.text.textSize = currentFontSize

            if (holder is AppHolder && item is MainActivity.AppInfo) {
                holder.text.text = item.label
                holder.star.visibility = if (item.isFavorite) View.VISIBLE else View.GONE
                holder.itemView.setOnClickListener { addToSelection(item) }
                holder.itemView.setOnLongClickListener { toggleFavorite(item); refreshSearchList(); true }
                
            } else if (holder is LayoutHolder) {
                // Reset State
                holder.btnSave.visibility = View.GONE 
                holder.btnExtinguish.visibility = View.GONE
                holder.check.visibility = View.INVISIBLE

                if (item is LayoutOption) {
                    holder.text.text = item.name
                    val isSelected = (item.type == selectedLayoutType)
                    holder.check.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                    holder.itemView.setBackgroundResource(if (isSelected) R.drawable.bg_item_press else 0)
                    holder.itemView.setOnClickListener { selectLayout(item.type) }
                    
                } else if (item is ResolutionOption) {
                    holder.text.text = item.name
                    val isSelected = (item.index == selectedResolutionIndex)
                    holder.check.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                    holder.itemView.setBackgroundResource(if (isSelected) R.drawable.bg_item_press else 0)
                    holder.itemView.setOnClickListener { applyResolution(item) }
                    
                } else if (item is ProfileOption) {
                    holder.text.text = item.name
                    holder.itemView.setBackgroundResource(0)
                    if (item.isCurrent) {
                        holder.btnSave.visibility = View.VISIBLE
                        holder.btnSave.setOnClickListener { saveProfile() }
                    } else {
                        holder.itemView.setOnClickListener { loadProfile(item.name) }
                    }
                } else if (item is IconOption) {
                    holder.text.text = item.name
                    holder.itemView.setBackgroundResource(R.drawable.bg_item_press)
                    holder.itemView.setOnClickListener { pickIcon() }
                } else if (item is ToggleOption) {
                    holder.text.text = item.name
                    holder.check.visibility = if (item.isEnabled) View.VISIBLE else View.INVISIBLE
                    holder.check.setImageResource(android.R.drawable.checkbox_on_background)
                    holder.check.setColorFilter(if (item.isEnabled) 0xFF00FF00.toInt() else 0xFF888888.toInt())
                    holder.itemView.setOnClickListener {
                        item.isEnabled = !item.isEnabled
                        item.onToggle(item.isEnabled)
                        // Update Text for Target Screen
                        if (item.name.startsWith("Target:")) {
                            holder.text.text = if (item.isEnabled) "Target: Cover Screen (1)" else "Target: Main Screen (0)"
                        }
                        notifyItemChanged(position)
                    }
                    
                    // SHOW EXTINGUISH BUTTON ONLY ON TARGET SCREEN TOGGLE
                    if (item.name.startsWith("Target:")) {
                        holder.btnExtinguish.visibility = View.VISIBLE
                        holder.btnExtinguish.setOnClickListener { performExtinguish() }
                    }
                }
                
            } else if (holder is DpiHolder && item is DpiOption) {
                holder.input.setText(item.currentDpi.toString())
                holder.btnMinus.setOnClickListener {
                    val v = holder.input.text.toString().toIntOrNull() ?: 160
                    val newVal = (v - 5).coerceAtLeast(100)
                    holder.input.setText(newVal.toString())
                    selectDpi(newVal)
                }
                holder.btnPlus.setOnClickListener {
                    val v = holder.input.text.toString().toIntOrNull() ?: 160
                    val newVal = (v + 5).coerceAtMost(400)
                    holder.input.setText(newVal.toString())
                    selectDpi(newVal)
                }
            } else if (holder is FontSizeHolder && item is FontSizeOption) {
                holder.textVal.text = item.currentSize.toInt().toString()
                holder.btnMinus.setOnClickListener { changeFontSize(item.currentSize - 1) }
                holder.btnPlus.setOnClickListener { changeFontSize(item.currentSize + 1) }
            }
        }
        override fun getItemCount() = displayList.size
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch(e: Exception) {}
        try { if (bubbleView != null) windowManager.removeView(bubbleView) } catch(e: Exception) {}
        try { if (isExpanded) windowManager.removeView(drawerView) } catch(e: Exception) {}
        if (isBound) ShizukuBinder.unbind(ComponentName(packageName, ShellUserService::class.java.name), userServiceConnection)
    }
}
