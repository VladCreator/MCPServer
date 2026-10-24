package com.vlad.mcpserver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appContext = applicationContext

        requestRuntimePermissionsIfNeeded()

        setContent {
            App()
        }
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.isForeground = true
    }

    override fun onPause() {
        AppVisibility.isForeground = false
        super.onPause()
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), RUNTIME_PERMISSIONS_REQUEST)
        }
    }

    private companion object {
        const val RUNTIME_PERMISSIONS_REQUEST = 100
    }
}
