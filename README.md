# CoverScreen Launcher & Tiling Manager

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue.svg) ![Platform](https://img.shields.io/badge/Platform-Android%2014%2B-green.svg) ![Status](https://img.shields.io/badge/Status-Experimental-orange.svg)

**CoverScreen Launcher** is an advanced window manager and app launcher designed primarily for Samsung Galaxy Z Flip devices (and other foldables/external displays). 

It bypasses system restrictions to launch **any** application on the Cover Screen and provides desktop-like window tiling capabilities (Quadrants, Split Screen) that the stock OS does not support. It is also optimized for **AR Glasses** (XREAL, Rokid, Viture) by allowing the phone screen to be turned off while keeping the system running.

## 🚀 Key Features

### 🔓 Unrestricted Launching
- Bypasses the "Good Lock" / System Allowlist restriction.
- Launches *any* installed application directly on the Cover Screen (Display 1) using ADB Shell commands via Shizuku.

### 🔲 Advanced Window Tiling
Android's native split-screen on cover screens is limited. This app forces windows into specific geometries:
- **2 Apps (Side-by-Side):** Perfect vertical split.
- **2 Apps (Top-Bottom):** Horizontal split.
- **3 Apps (Tri-Split):** Evenly divided vertical columns.
- **4 Apps (Quadrant):** A 2x2 grid for maximum multitasking.

### 🕶️ AR Glasses Mode ("Extinguish")
Designed for users of AR Glasses who use the phone as a computing unit/trackpad:
- **Screen Off, System On:** Turns off the physical display panel (to save battery and heat) without putting the CPU to sleep.
- **Targeted Control:** Can target specific displays (Main vs. Cover).
- **Trackpad Support:** Integrated toggle to reset/launch a specific Trackpad application when executing layouts.

### 💾 Profiles
- Save your favorite app combinations and layout configurations.
- Quickly load specific workspaces (e.g., "Work" with Docs/Chrome, "Social" with Discord/Reddit).

---

## 🛠️ Prerequisites

This app requires **Shizuku** to function, as it relies on elevated Shell permissions to manage windows and power states.

1.  **Shizuku:** Must be installed and running. [Download Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
2.  **Developer Options Enabled:**
    * `Settings > About Phone > Software Information > Tap "Build Number" 7 times`.
3.  **Freeform Windows Enabled:**
    * In Developer Options, enable: **"Force activities to be resizable"**.
    * In Developer Options, enable: **"Enable freeform windows"**.
    * *Recommended:* Reboot device after changing these settings.

---

## ⚙️ How It Works (Technical)

Standard Android APIs (`startActivity`) are blocked by Samsung on the Cover Screen for unapproved apps. This project uses a hybrid approach:

1.  **The Shotgun Launch:**
    * It attempts a standard API launch (for reliability on the main screen).
    * Simultaneously, it executes an `am start` shell command via Shizuku to force the activity onto `Display 1` (Cover Screen).

2.  **Post-Launch Resize (The "Magic" Fix):**
    * Android 14+ removed the ability to set window bounds during the launch command.
    * This app launches the app first, then scans the system using `dumpsys activity activities` to find the specific **Task ID** of the new window.
    * It then issues an `am task resize [taskId] [rect]` command to snap the window into the desired tile position.

3.  **Extinguish Mode:**
    * Uses Java Reflection to access the hidden `SurfaceControl` or `DisplayControl` system APIs.
    * Calls `setDisplayPowerMode(token, 0)` to cut power to the display panel hardware while leaving the OS active.

---

## 📥 Installation

1.  Clone the repo or download the latest APK (check Releases).
2.  Install the APK on your Samsung Z Flip.
3.  Open **Shizuku** and start the service (via Wireless Debugging or Root).
4.  Open **CoverScreen Launcher** and grant Shizuku permission when prompted.
5.  Grant "Overlay Permission" if prompted (required for the floating menu).

---

## 🎮 Usage

1.  **Open the Menu:** Click the floating bubble to open the launcher drawer.
2.  **Select Apps:** Use the search bar to find apps. Click them to add to the launch queue.
3.  **Choose Layout:** Click the "Window" icon and select a layout (e.g., 4-Quadrant).
4.  **Launch:** Click the Green Play button.
    * *Note:* The app will cycle through your selected apps, launching them and then resizing them into position.
5.  **Extinguish (AR Mode):**
    * Go to Settings (Gear Icon).
    * Toggle "Target: Cover Screen".
    * Click the **Power Off** icon next to the toggle.
    * *To Wake:* Press Volume Up or the physical Power button.

---

## 🤝 Contributing

Pull requests are welcome!
* **Architecture:** Kotlin, Android Service, AIDL (for Shizuku IPC).
* **Key Files:** `FloatingLauncherService.kt` (Logic), `ShellUserService.kt` (Shizuku Commands).


