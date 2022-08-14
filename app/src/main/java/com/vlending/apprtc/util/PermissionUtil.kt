package com.vlending.apprtc.util

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionUtil {

    private val MANDATORY_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA
    )

    fun checkPermission(activity: Activity, requestPermission: (Boolean, Array<String>) -> Unit) {
        val notGrantedPermissions = mutableListOf<String>()

        for (permission in MANDATORY_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                notGrantedPermissions.add(permission)
            }
        }

        requestPermission(notGrantedPermissions.isEmpty(), notGrantedPermissions.toTypedArray())
    }
}