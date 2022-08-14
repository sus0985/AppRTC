package com.vlending.apprtc.util

import android.content.Context
import androidx.core.content.PermissionChecker
import androidx.core.content.PermissionChecker.checkCallingOrSelfPermission

object PermissionUtil {

    fun checkPermission(context: Context): Boolean {
        for (permission in MANDATORY_PERMISSIONS) {
            if (checkCallingOrSelfPermission(context, permission) != PermissionChecker.PERMISSION_GRANTED) {
                return false
            }
        }

        return true
    }

    private val MANDATORY_PERMISSIONS = arrayOf("android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.RECORD_AUDIO", "android.permission.INTERNET")
}