package com.vlad.mcpserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var model: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appContext = applicationContext

        requestRuntimePermissionsIfNeeded()
        model = ViewModelProvider(this)[AppViewModel::class.java]
        startFromDesktop(intent)

        setContent {
            App(model)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startFromDesktop(intent)
    }

    private fun startFromDesktop(intent: Intent) {
        if (intent.component?.className == "com.vlad.mcpserver.DesktopLaunchActivity") {
            model.startServerFromDesktop()
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
