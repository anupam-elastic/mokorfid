package com.mokobara.mokoapp

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log

/**
 * Best-effort home-screen icon for C72 / industrial launchers.
 * Tries legacy INSTALL_SHORTCUT, then [ShortcutManager.requestPinShortcut].
 */
object HomeScreenHelper {
    private const val TAG = "HomeScreenHelper"
    private const val SHORTCUT_ID = "moko_rfid_home"

    fun ensureLauncherShortcut(activity: Activity) {
        try {
            if (isAlreadyPinned(activity)) return

            // Many handheld launchers still honor the legacy broadcast.
            sendLegacyInstallShortcut(activity)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requestPinShortcut(activity)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ensureLauncherShortcut: ${t.message}")
        }
    }

    private fun isAlreadyPinned(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return false
        val sm = activity.getSystemService(ShortcutManager::class.java) ?: return false
        return sm.pinnedShortcuts.any { it.id == SHORTCUT_ID }
    }

    private fun launchIntent(activity: Activity): Intent {
        return Intent(activity, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    @Suppress("DEPRECATION")
    private fun sendLegacyInstallShortcut(activity: Activity) {
        val shortcut = Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent(activity))
            putExtra(Intent.EXTRA_SHORTCUT_NAME, activity.getString(R.string.app_name))
            putExtra(
                Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(activity, R.mipmap.ic_launcher)
            )
            putExtra("duplicate", false)
        }
        activity.sendBroadcast(shortcut)
    }

    private fun requestPinShortcut(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sm = activity.getSystemService(ShortcutManager::class.java) ?: return
        if (!sm.isRequestPinShortcutSupported) return

        val info = ShortcutInfo.Builder(activity, SHORTCUT_ID)
            .setShortLabel(activity.getString(R.string.app_name))
            .setLongLabel(activity.getString(R.string.app_name))
            .setIcon(Icon.createWithResource(activity, R.mipmap.ic_launcher))
            .setIntent(launchIntent(activity))
            .build()
        sm.requestPinShortcut(info, null)
    }
}
