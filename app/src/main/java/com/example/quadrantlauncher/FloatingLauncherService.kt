package com.example.quadrantlauncher

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Color
import android.net.Uri
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.util.Log
import android.view.*
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.hypot

class FloatingLauncherService : Service() {

    // =========================================
    // 1. CLASS VARIABLES & CONSTANTS
    // =========================================
    
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
    
    private var activeProfileName: String? = null
    private var currentMode = MODE_SEARCH
    private var selectedLayoutType = 2
    private var selectedResolutionIndex = 0
    private var currentDpiSetting = -1
    private var currentFontSize = 16f
    
    private var killAppOnExecute = true
    private var targetDisplayIndex = 1 
    private var resetTrackpad = false
    private var isExtinguished = false
    private var isInstantMode = false 
    
    private val TRACKPAD_PACKAGE = "com.katsuyamaki.trackpad"
    private val PACKAGE_BLANK = "internal.blank.spacer"
    
    private var shellService: IShellService? = null
    private var isBound = false
    private val uiHandler = Handler(Looper.getMainLooper())

    // =========================================
    // 2. CALLBACK DEFINITIONS
    // =========================================

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
                if (currentMode == MODE_SETTINGS) {
                    drawerView?.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
                }
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
                showToast("Deleted ${item.name}")
                switchMode(MODE_PROFILES)
                return
            }

            if (currentMode == MODE_SEARCH) {
                val item = displayList.getOrNull(pos) as? MainActivity.AppInfo ?: return
                if (item.packageName == PACKAGE_BLANK) {
                    (drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view).adapter as RofiAdapter).notifyItemChanged(pos)
                    return
                }
                if (direction == ItemTouchHelper.LEFT && !item.isFavorite) toggleFavorite(item)
                else if (direction == ItemTouchHelper.RIGHT && item.isFavorite) toggleFavorite(item)
                refreshSearchList()
            }
        }
    }

    private val selectedAppsDragCallback = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 
        ItemTouchHelper.UP or ItemTouchHelper.DOWN 
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val fromPos = viewHolder.adapterPosition
            val toPos = target.adapterPosition
            Collections.swap(selectedAppsQueue, fromPos, toPos)
            recyclerView.adapter?.notifyItemMoved(fromPos, toPos)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val pos = viewHolder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val app = selectedAppsQueue[pos]
                if (isInstantMode && app.packageName != PACKAGE_BLANK) {
                    Thread { 
                        try { shellService?.forceStop(app.packageName) } catch(e: Exception) {}
                    }.start()
                    showToast("Killed ${app.label}")
                }
                selectedAppsQueue.removeAt(pos)
                updateSelectedAppsDock()
                drawerView?.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
                if (isInstantMode) applyLayoutImmediate()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            if (isInstantMode) applyLayoutImmediate()
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shellService = IShellService.Stub.asInterface(binder)
            isBound = true
            updateExecuteButtonColor(true)
            showToast("Shizuku Connected")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            isBound = false
            updateExecuteButtonColor(false)
        }
    }

    // =========================================
    // 3. LIFECYCLE
    // =========================================

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

        try {
            if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindShizuku()
            }
        } catch (e: Exception) {}
        
        loadInstalledApps()
        currentFontSize = AppPreferences.getFontSize(this)
        killAppOnExecute = AppPreferences.getKillOnExecute(this)
        targetDisplayIndex = AppPreferences.getTargetDisplayIndex(this)
        resetTrackpad = AppPreferences.getResetTrackpad(this)
        isInstantMode = AppPreferences.getInstantMode(this)
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
            
            try {
                if (shellService == null && rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    bindShizuku()
                }
            } catch (e: Exception) {}
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch(e: Exception) {}
        try { if (bubbleView != null) windowManager.removeView(bubbleView) } catch(e: Exception) {}
        try { if (isExpanded) windowManager.removeView(drawerView) } catch(e: Exception) {}
        if (isBound) {
            try {
                ShizukuBinder.unbind(ComponentName(packageName, ShellUserService::class.java.name), userServiceConnection)
                isBound = false
            } catch (e: Exception) {}
        }
    }

    // =========================================
    // 4. LOGIC & HELPER METHODS
    // =========================================

    private fun showToast(msg: String) {
        uiHandler.post { Toast.makeText(this@FloatingLauncherService, msg, Toast.LENGTH_SHORT).show() }
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

    private fun refreshDisplayId() {
        val id = displayContext?.display?.displayId ?: Display.DEFAULT_DISPLAY
        currentDisplayId = id
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
        } catch (e: Exception) {
            Log.e(TAG, "Bind Shizuku Failed", e)
        }
    }

    private fun updateExecuteButtonColor(isReady: Boolean) {
        uiHandler.post {
            val executeBtn = drawerView?.findViewById<ImageView>(R.id.icon_execute)
            if (isReady) {
                executeBtn?.setColorFilter(Color.GREEN)
            } else {
                executeBtn?.setColorFilter(Color.RED)
            }
        }
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

        var velocityTracker: VelocityTracker? = null

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f; var isDrag = false
            
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)

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
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vX = velocityTracker?.xVelocity ?: 0f
                        val vY = velocityTracker?.yVelocity ?: 0f
                        val totalVel = hypot(vX.toDouble(), vY.toDouble())
                        if (isDrag && totalVel > 2500) {
                            showToast("Closing...")
                            stopSelf()
                            return true
                        }
                        if (!isDrag) toggleDrawer()
                        velocityTracker?.recycle()
                        velocityTracker = null
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        velocityTracker?.recycle()
                        velocityTracker = null
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
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        drawerParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        
        val searchBar = drawerView!!.findViewById<EditText>(R.id.rofi_search_bar)
        val mainRecycler = drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view)
        val selectedRecycler = drawerView!!.findViewById<RecyclerView>(R.id.selected_apps_recycler)
        val executeBtn = drawerView!!.findViewById<ImageView>(R.id.icon_execute)
        
        if (isBound) executeBtn.setColorFilter(Color.GREEN) else executeBtn.setColorFilter(Color.RED)

        drawerView!!.findViewById<ImageView>(R.id.icon_search_mode).setOnClickListener { switchMode(MODE_SEARCH) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_window).setOnClickListener { switchMode(MODE_LAYOUTS) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_resolution).setOnClickListener { switchMode(MODE_RESOLUTION) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_dpi).setOnClickListener { switchMode(MODE_DPI) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_profiles).setOnClickListener { switchMode(MODE_PROFILES) }
        drawerView!!.findViewById<ImageView>(R.id.icon_mode_settings).setOnClickListener { switchMode(MODE_SETTINGS) }
        executeBtn.setOnClickListener { executeLaunch(selectedLayoutType) }
        
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        searchBar.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                if (searchBar.text.isEmpty() && selectedAppsQueue.isNotEmpty()) {
                    val lastIndex = selectedAppsQueue.size - 1
                    selectedAppsQueue.removeAt(lastIndex)
                    updateSelectedAppsDock()
                    mainRecycler.adapter?.notifyDataSetChanged()
                    return@setOnKeyListener true
                }
            }
            return@setOnKeyListener false
        }

        mainRecycler.layoutManager = LinearLayoutManager(themeContext)
        mainRecycler.adapter = RofiAdapter()
        val itemTouchHelper = ItemTouchHelper(swipeCallback)
        itemTouchHelper.attachToRecyclerView(mainRecycler)
        
        selectedRecycler.layoutManager = LinearLayoutManager(themeContext, LinearLayoutManager.HORIZONTAL, false)
        selectedRecycler.adapter = SelectedAppsAdapter()
        val dockTouchHelper = ItemTouchHelper(selectedAppsDragCallback)
        dockTouchHelper.attachToRecyclerView(selectedRecycler)
        
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

    private fun toggleDrawer() {
        if (isExpanded) {
            try { windowManager.removeView(drawerView) } catch(e: Exception) {}
            bubbleView?.visibility = View.VISIBLE
            isExpanded = false
        } else {
            setupDisplayContext(currentDisplayId) 
            val metrics = windowManager.maximumWindowMetrics
            drawerParams.width = metrics.bounds.width()
            drawerParams.height = metrics.bounds.height()
            val screenW = metrics.bounds.width()
            val screenH = metrics.bounds.height()
            
            val container = drawerView?.findViewById<LinearLayout>(R.id.drawer_container)
            val newW = if (screenW > 1000) (screenW * 0.5).toInt() else (screenW * 0.9).toInt()
            val newH = (screenH * 0.7).toInt()
            
            container?.layoutParams?.width = newW
            container?.layoutParams?.height = newH
            container?.requestLayout()

            try { windowManager.addView(drawerView, drawerParams) } catch(e: Exception) {}
            bubbleView?.visibility = View.GONE
            isExpanded = true
            switchMode(MODE_SEARCH) 
            val et = drawerView?.findViewById<EditText>(R.id.rofi_search_bar)
            et?.setText("")
            et?.requestFocus()
            updateSelectedAppsDock()
            
            if (isInstantMode) {
                fetchRunningApps()
            }
        }
    }

    private fun updateGlobalFontSize() {
        val searchBar = drawerView?.findViewById<EditText>(R.id.rofi_search_bar)
        searchBar?.textSize = currentFontSize
        drawerView?.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
    }

    private fun restartTrackpad() {
        try {
            shellService?.forceStop(TRACKPAD_PACKAGE)
            val i = packageManager.getLaunchIntentForPackage(TRACKPAD_PACKAGE)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(i)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset trackpad", e)
        }
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val riList = pm.queryIntentActivities(intent, 0)
        allAppsList.clear()
        allAppsList.add(MainActivity.AppInfo(" (Blank Space)", PACKAGE_BLANK))
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

    private fun getLayoutName(type: Int): String {
        return when(type) {
            LAYOUT_SIDE_BY_SIDE -> "Split"
            LAYOUT_TOP_BOTTOM -> "Top/Bot"
            LAYOUT_TRI_EVEN -> "Tri-Split"
            LAYOUT_CORNERS -> "Quadrant"
            else -> "Unknown"
        }
    }

    private fun getRatioName(index: Int): String {
        return when(index) {
            1 -> "1:1"
            2 -> "16:9"
            3 -> "32:9"
            else -> "Default"
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
    
    private fun getResolutionCommand(index: Int): String {
        return when(index) {
            1 -> "wm size 1422x1500 -d $currentDisplayId"
            2 -> "wm size 1920x1080 -d $currentDisplayId"
            3 -> "wm size 3840x1080 -d $currentDisplayId"
            else -> "wm size reset -d $currentDisplayId"
        }
    }
    
    private fun updateSelectedAppsDock() {
        val dock = drawerView!!.findViewById<RecyclerView>(R.id.selected_apps_recycler)
        if (selectedAppsQueue.isEmpty()) {
            dock.visibility = View.GONE
        } else {
            dock.visibility = View.VISIBLE
            dock.adapter?.notifyDataSetChanged()
            dock.scrollToPosition(selectedAppsQueue.size - 1)
        }
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
        val sorted = filtered.sortedWith(
            compareBy<MainActivity.AppInfo> { it.packageName != PACKAGE_BLANK }
            .thenByDescending { it.isFavorite }
            .thenBy { it.label.lowercase() }
        )
        displayList.addAll(sorted)
        drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
    }

    private fun addToSelection(app: MainActivity.AppInfo) {
        if (app.packageName == PACKAGE_BLANK) {
            selectedAppsQueue.add(app)
        } else {
            val existing = selectedAppsQueue.find { it.packageName == app.packageName }
            if (existing != null) {
                selectedAppsQueue.remove(existing)
            } else {
                selectedAppsQueue.add(app)
            }
        }
        updateSelectedAppsDock()
        drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
        val et = drawerView!!.findViewById<EditText>(R.id.rofi_search_bar)
        et.setText("")

        if (isInstantMode) {
            if (app.packageName != PACKAGE_BLANK) {
                launchViaApi(app.packageName, null)
                launchViaShell(app.packageName)
                uiHandler.postDelayed({ applyLayoutImmediate() }, 200)
                uiHandler.postDelayed({ applyLayoutImmediate() }, 800)
            } else {
                applyLayoutImmediate()
            }
        }
    }

    private fun toggleFavorite(app: MainActivity.AppInfo) {
        val newState = AppPreferences.toggleFavorite(this, app.packageName)
        app.isFavorite = newState
        allAppsList.find { it.packageName == app.packageName }?.isFavorite = newState
    }

    private fun launchViaApi(pkg: String, bounds: Rect?) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val options = android.app.ActivityOptions.makeBasic()
            if (bounds != null) options.setLaunchBounds(bounds)
            startActivity(intent, options.toBundle())
        } catch (e: Exception) {}
    }

    private fun launchViaShell(pkg: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
            if (shellService != null) {
                val component = intent.component?.flattenToShortString() ?: pkg
                val cmd = "am start -n $component -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --display $currentDisplayId --windowingMode 5 --user 0"
                Thread { shellService?.runCommand(cmd) }.start()
            }
        } catch (e: Exception) {}
    }
    
    private fun cycleDisplay() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        var targetId = if (currentDisplayId == 0) 1 else 0
        var targetDisplay = dm.getDisplay(targetId)
        
        if (targetDisplay == null) {
            val displays = dm.displays
            val currentIdx = displays.indexOfFirst { it.displayId == currentDisplayId }
            val nextIdx = if (currentIdx == -1) 0 else (currentIdx + 1) % displays.size
            targetDisplay = displays[nextIdx]
        }

        if (targetDisplay == null) {
            showToast("Error: Target display unavailable.")
            return
        }

        val newId = targetDisplay.displayId
        try {
            if (bubbleView != null && bubbleView!!.isAttachedToWindow) windowManager.removeView(bubbleView)
            if (drawerView != null && drawerView!!.isAttachedToWindow) windowManager.removeView(drawerView)
        } catch (e: Exception) {}

        currentDisplayId = newId
        setupDisplayContext(currentDisplayId)
        targetDisplayIndex = currentDisplayId
        AppPreferences.setTargetDisplayIndex(this, targetDisplayIndex)

        setupBubble()
        setupDrawer()
        updateBubbleIcon() 
        isExpanded = false
        showToast("Switched to Display $currentDisplayId")
    }

    private fun performExtinguish() {
        toggleDrawer()
        isExtinguished = true
        Thread {
            try {
                shellService?.setScreenOff(targetDisplayIndex, true)
            } catch (e: Exception) {}
        }.start()
        showToast("Screen OFF (Index ${targetDisplayIndex}). Vol+ to Wake.")
    }

    private fun wakeUp() {
        isExtinguished = false
        Thread { 
            shellService?.setScreenOff(0, false) 
            shellService?.setScreenOff(1, false)
        }.start()
        showToast("Screen Woke Up")
        if (currentMode == MODE_SETTINGS) drawerView?.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
    }
    
    private fun applyLayoutImmediate() {
        Thread {
            refreshDisplayId()
            val pkgs = selectedAppsQueue.map { it.packageName }
            AppPreferences.saveLastQueue(this@FloatingLauncherService, pkgs)

            val targetDim = getTargetDimensions(selectedResolutionIndex)
            val w = targetDim?.first ?: windowManager.maximumWindowMetrics.bounds.width()
            val h = targetDim?.second ?: windowManager.maximumWindowMetrics.bounds.height()
            
            val rects = mutableListOf<Rect>()
            when (selectedLayoutType) {
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

            val count = Math.min(selectedAppsQueue.size, rects.size)
            for (i in 0 until count) {
                val pkg = selectedAppsQueue[i].packageName
                val bounds = rects[i]
                if (pkg != PACKAGE_BLANK) {
                    try { shellService?.repositionTask(pkg, bounds.left, bounds.top, bounds.right, bounds.bottom) } catch (e: Exception) {}
                }
            }
        }.start()
    }
    
    private fun fetchRunningApps() {
        if (shellService == null) return
        Thread {
            try {
                val visiblePackages = shellService!!.getVisiblePackages(currentDisplayId)
                val lastQueue = AppPreferences.getLastQueue(this)
                uiHandler.post {
                    selectedAppsQueue.clear()
                    // Reconstruct from Last Queue (Preserves Blanks)
                    for (pkg in lastQueue) {
                        if (pkg == PACKAGE_BLANK) selectedAppsQueue.add(MainActivity.AppInfo(" (Blank Space)", PACKAGE_BLANK))
                        else if (visiblePackages.contains(pkg)) {
                            val appInfo = allAppsList.find { it.packageName == pkg }
                            if (appInfo != null) selectedAppsQueue.add(appInfo)
                        }
                    }
                    // Append new apps
                    for (pkg in visiblePackages) {
                        if (!lastQueue.contains(pkg)) {
                            val appInfo = allAppsList.find { it.packageName == pkg }
                            if (appInfo != null) selectedAppsQueue.add(appInfo)
                        }
                    }
                    updateSelectedAppsDock()
                    drawerView?.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
                    showToast("Instant Mode: Active")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching apps", e)
            }
        }.start()
    }

    private fun selectLayout(type: Int) {
        selectedLayoutType = type
        AppPreferences.saveLastLayout(this, type)
        drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
        if (isInstantMode) applyLayoutImmediate()
    }

    private fun applyResolution(opt: ResolutionOption) {
        if (opt.index != -1) { 
            selectedResolutionIndex = opt.index
            AppPreferences.saveLastResolution(this, opt.index)
        }
        drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
        
        if (isInstantMode) {
            Thread {
                val resCmd = getResolutionCommand(selectedResolutionIndex)
                shellService?.runCommand(resCmd)
                Thread.sleep(1500) // Allow screen refresh
                uiHandler.post { applyLayoutImmediate() }
            }.start()
        }
    }
    
    private fun selectDpi(value: Int) {
        currentDpiSetting = value.coerceIn(100, 400)
        AppPreferences.saveLastDpi(this, currentDpiSetting)
        
        if (isInstantMode) {
            Thread {
                val dpiCmd = "wm density $currentDpiSetting -d $currentDisplayId"
                shellService?.runCommand(dpiCmd)
            }.start()
        }
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
            refreshDisplayId()
            val intent = Intent(this, IconPickerActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val metrics = windowManager.maximumWindowMetrics
            val w = 1000
            val h = (metrics.bounds.height() * 0.7).toInt()
            val x = (metrics.bounds.width() - w) / 2
            val y = (metrics.bounds.height() - h) / 2
            val options = android.app.ActivityOptions.makeBasic()
            options.setLaunchDisplayId(currentDisplayId)
            options.setLaunchBounds(Rect(x, y, x+w, y+h))
            startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            showToast("Error launching picker: ${e.message}")
        }
    }

    private fun saveProfile() {
        var name = drawerView?.findViewById<EditText>(R.id.rofi_search_bar)?.text?.toString()?.trim()
        if (name.isNullOrEmpty()) {
            val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            name = "Profile_$timestamp"
        }
        val pkgs = selectedAppsQueue.map { it.packageName }
        AppPreferences.saveProfile(this, name, selectedLayoutType, selectedResolutionIndex, currentDpiSetting, pkgs)
        showToast("Saved: $name")
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
                    if (pkg == PACKAGE_BLANK) {
                         selectedAppsQueue.add(MainActivity.AppInfo(" (Blank Space)", PACKAGE_BLANK))
                    } else {
                        val app = allAppsList.find { it.packageName == pkg }
                        if (app != null) selectedAppsQueue.add(app)
                    }
                }
            }
            AppPreferences.saveLastLayout(this, selectedLayoutType)
            AppPreferences.saveLastResolution(this, selectedResolutionIndex)
            AppPreferences.saveLastDpi(this, currentDpiSetting)
            activeProfileName = name
            updateSelectedAppsDock()
            showToast("Loaded: $name")
            drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
            
            // FIX: Launch apps in Instant Mode
            if (isInstantMode) {
                 val targetDim = getTargetDimensions(selectedResolutionIndex)
                 // Simple grid calc for launch loop
                 val w = targetDim?.first ?: windowManager.maximumWindowMetrics.bounds.width()
                 val h = targetDim?.second ?: windowManager.maximumWindowMetrics.bounds.height()
                 val rects = mutableListOf<Rect>()
                 when (selectedLayoutType) {
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
                 
                 val count = Math.min(selectedAppsQueue.size, rects.size)
                 for (i in 0 until count) {
                     val pkg = selectedAppsQueue[i].packageName
                     if (pkg != PACKAGE_BLANK) {
                         launchViaApi(pkg, rects[i])
                         launchViaShell(pkg)
                     }
                 }

                Thread {
                    val resCmd = getResolutionCommand(selectedResolutionIndex)
                    shellService?.runCommand(resCmd)
                    val dpiCmd = "wm density $currentDpiSetting -d $currentDisplayId"
                    shellService?.runCommand(dpiCmd)
                    Thread.sleep(1500)
                    uiHandler.post { applyLayoutImmediate() }
                }.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load profile", e)
        }
    }

    private fun executeLaunch(layoutType: Int) {
        toggleDrawer() 
        refreshDisplayId() 
        val pkgs = selectedAppsQueue.map { it.packageName }
        AppPreferences.saveLastQueue(this, pkgs)
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
                if (resetTrackpad) {
                    restartTrackpad()
                    Thread.sleep(200)
                }
                if (selectedAppsQueue.isNotEmpty()) {
                    if (killAppOnExecute) {
                        for (app in selectedAppsQueue) {
                            if (app.packageName != PACKAGE_BLANK) {
                                shellService?.forceStop(app.packageName)
                            }
                        }
                        Thread.sleep(400)
                    } else {
                        Thread.sleep(100)
                    }
                    val count = Math.min(selectedAppsQueue.size, rects.size)
                    for (i in 0 until count) {
                        val pkg = selectedAppsQueue[i].packageName
                        val bounds = rects[i]
                        if (pkg == PACKAGE_BLANK) continue 
                        uiHandler.postDelayed({ launchViaApi(pkg, bounds) }, (i * 150).toLong())
                        uiHandler.postDelayed({ launchViaShell(pkg) }, (i * 150 + 50).toLong())
                        if (!killAppOnExecute) {
                            uiHandler.postDelayed({
                                Thread { 
                                    try { shellService?.repositionTask(pkg, bounds.left, bounds.top, bounds.right, bounds.bottom) } catch (e: Exception) {}
                                }.start()
                            }, (i * 150 + 150).toLong())
                        }
                        uiHandler.postDelayed({
                            Thread { 
                                try { shellService?.repositionTask(pkg, bounds.left, bounds.top, bounds.right, bounds.bottom) } catch (e: Exception) {}
                            }.start()
                        }, (i * 150 + 800).toLong()) 
                    }
                    uiHandler.post { selectedAppsQueue.clear(); updateSelectedAppsDock() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Execute Failed", e)
                showToast("Execute Failed: ${e.message}")
            }
        }.start()
        drawerView?.findViewById<EditText>(R.id.rofi_search_bar)?.setText("")
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

        executeBtn.visibility = if (isInstantMode) View.GONE else View.VISIBLE
        displayList.clear()
        val dock = drawerView!!.findViewById<RecyclerView>(R.id.selected_apps_recycler)
        dock.visibility = if (mode == MODE_SEARCH && selectedAppsQueue.isNotEmpty()) View.VISIBLE else View.GONE
        
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
                displayList.add(ProfileOption("Save Current as New", true, 0,0,0, emptyList()))
                val profileNames = AppPreferences.getProfileNames(this).sorted()
                for (pName in profileNames) {
                    val data = AppPreferences.getProfileData(this, pName)
                    if (data != null) {
                        try {
                            val parts = data.split("|")
                            val lay = parts[0].toInt()
                            val res = parts[1].toInt()
                            val d = parts[2].toInt()
                            val pkgs = parts[3].split(",").filter { it.isNotEmpty() }
                            displayList.add(ProfileOption(pName, false, lay, res, d, pkgs))
                        } catch(e: Exception) {}
                    }
                }
            }
            MODE_SETTINGS -> {
                searchBar.hint = "Settings"
                displayList.add(ActionOption("Switch Display (Current $currentDisplayId)") { cycleDisplay() })
                displayList.add(FontSizeOption(currentFontSize))
                displayList.add(IconOption("Launcher Icon (Tap to Change)"))
                displayList.add(ToggleOption("Instant Mode (Live Changes)", isInstantMode) {
                    isInstantMode = it
                    AppPreferences.setInstantMode(this, it)
                    executeBtn.visibility = if (it) View.GONE else View.VISIBLE
                    if (it) fetchRunningApps()
                })
                displayList.add(ToggleOption("Kill App on Execute", killAppOnExecute) { 
                    killAppOnExecute = it
                    AppPreferences.setKillOnExecute(this, it)
                })
                displayList.add(ToggleOption("Display Off (Touch on)", isExtinguished) { 
                    if (it) performExtinguish() else wakeUp()
                })
                displayList.add(ToggleOption("Reset Trackpad on Execute", resetTrackpad) {
                    resetTrackpad = it
                    AppPreferences.setResetTrackpad(this, it)
                })
            }
        }
        drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
    }

    // =========================================
    // 7. DATA CLASSES
    // =========================================
    data class LayoutOption(val name: String, val type: Int)
    data class ResolutionOption(val name: String, val command: String, val index: Int)
    data class DpiOption(val currentDpi: Int)
    data class ProfileOption(val name: String, val isCurrent: Boolean, 
                             val layout: Int, val resIndex: Int, val dpi: Int, val apps: List<String>)
    data class FontSizeOption(val currentSize: Float)
    data class IconOption(val name: String)
    data class ActionOption(val name: String, val action: () -> Unit)
    data class ToggleOption(val name: String, var isEnabled: Boolean, val onToggle: (Boolean) -> Unit)

    // =========================================
    // 8. ADAPTERS
    // =========================================

    inner class SelectedAppsAdapter : RecyclerView.Adapter<SelectedAppsAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.selected_app_icon)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_selected_app, parent, false))
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = selectedAppsQueue[position]
            if (app.packageName == PACKAGE_BLANK) {
                holder.icon.setImageResource(R.drawable.ic_box_outline)
            } else {
                try {
                    holder.icon.setImageDrawable(packageManager.getApplicationIcon(app.packageName))
                } catch (e: Exception) {
                    holder.icon.setImageResource(R.mipmap.ic_launcher_round)
                }
            }
            holder.itemView.setOnClickListener {
                val appToRemove = selectedAppsQueue[position]
                if (isInstantMode && appToRemove.packageName != PACKAGE_BLANK) {
                    Thread { 
                        try { shellService?.forceStop(appToRemove.packageName) } catch(e: Exception) {}
                    }.start()
                    showToast("Killed ${appToRemove.label}")
                }
                selectedAppsQueue.removeAt(position)
                updateSelectedAppsDock()
                drawerView!!.findViewById<RecyclerView>(R.id.rofi_recycler_view)?.adapter?.notifyDataSetChanged()
                if (isInstantMode) applyLayoutImmediate()
            }
        }
        override fun getItemCount() = selectedAppsQueue.size
    }

    inner class RofiAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class AppHolder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.rofi_app_icon)
            val text: TextView = v.findViewById(R.id.rofi_app_text)
            val star: ImageView = v.findViewById(R.id.rofi_app_star)
        }
        inner class LayoutHolder(v: View) : RecyclerView.ViewHolder(v) {
            val text: TextView = v.findViewById(R.id.layout_name)
            val btnSave: ImageView = v.findViewById(R.id.btn_save_profile)
            val btnExtinguish: ImageView = v.findViewById(R.id.btn_extinguish_item)
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
        inner class ProfileRichHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: EditText = v.findViewById(R.id.profile_name_text)
            val details: TextView = v.findViewById(R.id.profile_details_text)
            val iconsContainer: LinearLayout = v.findViewById(R.id.profile_icons_container)
            val btnSave: ImageView = v.findViewById(R.id.btn_save_profile_rich)
        }
        inner class IconSettingHolder(v: View) : RecyclerView.ViewHolder(v) {
            val preview: ImageView = v.findViewById(R.id.icon_setting_preview)
        }

        override fun getItemViewType(position: Int): Int {
            return when (displayList[position]) {
                is MainActivity.AppInfo -> 0
                is LayoutOption -> 1
                is ResolutionOption -> 1 
                is DpiOption -> 2
                is ProfileOption -> 4 
                is FontSizeOption -> 3
                is IconOption -> 5 
                is ToggleOption -> 1
                is ActionOption -> 6 
                else -> 0
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                0 -> AppHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_app_rofi, parent, false))
                1 -> LayoutHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_layout_option, parent, false))
                2 -> DpiHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_dpi_custom, parent, false))
                3 -> FontSizeHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_font_size, parent, false))
                4 -> ProfileRichHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_profile_rich, parent, false))
                5 -> IconSettingHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_icon_setting, parent, false))
                6 -> LayoutHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_layout_option, parent, false))
                else -> AppHolder(View(parent.context))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = displayList[position]
            
            if (holder is AppHolder) holder.text.textSize = currentFontSize
            if (holder is LayoutHolder) holder.text.textSize = currentFontSize
            if (holder is ProfileRichHolder) holder.name.textSize = currentFontSize

            if (holder is AppHolder && item is MainActivity.AppInfo) {
                holder.text.text = item.label
                if (item.packageName == PACKAGE_BLANK) {
                    holder.icon.setImageResource(R.drawable.ic_box_outline)
                } else {
                    try {
                        holder.icon.setImageDrawable(packageManager.getApplicationIcon(item.packageName))
                    } catch (e: Exception) {
                        holder.icon.setImageResource(R.mipmap.ic_launcher_round)
                    }
                }
                val isSelected = selectedAppsQueue.any { it.packageName == item.packageName }
                if (isSelected) holder.itemView.setBackgroundResource(R.drawable.bg_item_active)
                else holder.itemView.setBackgroundResource(R.drawable.bg_item_press)
                holder.star.visibility = if (item.isFavorite) View.VISIBLE else View.GONE
                holder.itemView.setOnClickListener { addToSelection(item) }
                holder.itemView.setOnLongClickListener { toggleFavorite(item); refreshSearchList(); true }
                
            } else if (holder is ProfileRichHolder && item is ProfileOption) {
                holder.name.setText(item.name)
                holder.iconsContainer.removeAllViews()
                if (!item.isCurrent) {
                    for (pkg in item.apps.take(5)) { 
                        val iv = ImageView(holder.itemView.context)
                        val lp = LinearLayout.LayoutParams(60, 60)
                        lp.marginEnd = 8
                        iv.layoutParams = lp
                        if (pkg == PACKAGE_BLANK) {
                            iv.setImageResource(R.drawable.ic_box_outline)
                        } else {
                            try {
                                iv.setImageDrawable(packageManager.getApplicationIcon(pkg))
                            } catch (e: Exception) {
                                iv.setImageResource(R.mipmap.ic_launcher_round)
                            }
                        }
                        holder.iconsContainer.addView(iv)
                    }
                    val info = "${getLayoutName(item.layout)} | ${getRatioName(item.resIndex)} | ${item.dpi}dpi"
                    holder.details.text = info
                    holder.details.visibility = View.VISIBLE
                    holder.btnSave.visibility = View.GONE
                    
                    if (activeProfileName == item.name) {
                        holder.itemView.setBackgroundResource(R.drawable.bg_item_active)
                    } else {
                        holder.itemView.setBackgroundResource(0)
                    }
                    holder.itemView.setOnClickListener { loadProfile(item.name) }
                    
                    holder.name.setOnEditorActionListener { v, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            val newName = v.text.toString().trim()
                            if (newName.isNotEmpty() && newName != item.name) {
                                if (AppPreferences.renameProfile(v.context, item.name, newName)) {
                                    showToast("Renamed to $newName")
                                    switchMode(MODE_PROFILES)
                                }
                            }
                            true
                        } else false
                    }
                    holder.name.setOnFocusChangeListener { v, hasFocus ->
                        if (!hasFocus) {
                             val newName = (v as EditText).text.toString().trim()
                             if (newName.isNotEmpty() && newName != item.name) {
                                 if (AppPreferences.renameProfile(v.context, item.name, newName)) {
                                     showToast("Renamed to $newName")
                                 }
                             }
                        }
                    }

                } else {
                    holder.iconsContainer.removeAllViews()
                    holder.details.visibility = View.GONE
                    holder.btnSave.visibility = View.VISIBLE
                    holder.itemView.setBackgroundResource(0)
                    holder.itemView.setOnClickListener { saveProfile() }
                    holder.btnSave.setOnClickListener { saveProfile() }
                }

            } else if (holder is LayoutHolder) {
                holder.btnSave.visibility = View.GONE 
                holder.btnExtinguish.visibility = View.GONE

                if (item is LayoutOption) {
                    holder.text.text = item.name
                    val isSelected = (item.type == selectedLayoutType)
                    if (isSelected) holder.itemView.setBackgroundResource(R.drawable.bg_item_active)
                    else holder.itemView.setBackgroundResource(R.drawable.bg_item_press)
                    holder.itemView.setOnClickListener { selectLayout(item.type) }
                    
                } else if (item is ResolutionOption) {
                    holder.text.text = item.name
                    val isSelected = (item.index == selectedResolutionIndex)
                    if (isSelected) holder.itemView.setBackgroundResource(R.drawable.bg_item_active)
                    else holder.itemView.setBackgroundResource(R.drawable.bg_item_press)
                    holder.itemView.setOnClickListener { applyResolution(item) }
                    
                } else if (item is IconOption) {
                    holder.text.text = item.name
                    holder.itemView.setBackgroundResource(R.drawable.bg_item_press)
                    holder.itemView.setOnClickListener { pickIcon() }
                } else if (item is ToggleOption) {
                    holder.text.text = item.name
                    if (item.isEnabled) holder.itemView.setBackgroundResource(R.drawable.bg_item_active)
                    else holder.itemView.setBackgroundResource(R.drawable.bg_item_press)
                    holder.itemView.setOnClickListener {
                        item.isEnabled = !item.isEnabled
                        item.onToggle(item.isEnabled)
                        notifyItemChanged(position)
                    }
                } else if (item is ActionOption) {
                    holder.text.text = item.name
                    holder.itemView.setBackgroundResource(R.drawable.bg_item_press)
                    holder.itemView.setOnClickListener { item.action() }
                }
                
            } else if (holder is IconSettingHolder && item is IconOption) {
                try {
                    val uriStr = AppPreferences.getIconUri(holder.itemView.context)
                    if (uriStr != null) {
                        val uri = Uri.parse(uriStr)
                        val input = contentResolver.openInputStream(uri)
                        val bitmap = BitmapFactory.decodeStream(input)
                        input?.close()
                        holder.preview.setImageBitmap(bitmap)
                    } else {
                        holder.preview.setImageResource(R.mipmap.ic_launcher_round)
                    }
                } catch(e: Exception) {
                    holder.preview.setImageResource(R.mipmap.ic_launcher_round)
                }
                holder.itemView.setOnClickListener { pickIcon() }

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
}
