package com.copenhagen.wallet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.copenhagen.wallet.ui.App

class MainActivity : FragmentActivity() {
    private val app: AppModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        ApprovalMessagingService.createChannel(this)
        setContent {
            LaunchedEffect(app.notificationsRegistered) {
                if (app.notificationsRegistered && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            App(app, this)
        }
        if (savedInstanceState == null) acceptLink(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); acceptLink(intent)
    }
    private fun acceptLink(intent: Intent?) {
        intent?.getStringExtra("approvalId")?.let {
            intent.removeExtra("approvalId")
            app.acceptApprovalLink(it)
            return
        }
        val uri = intent?.data ?: return
        if (uri.scheme == "https" && uri.host == BuildConfig.APP_LINK_HOST &&
            uri.pathSegments.size == 2 && uri.pathSegments[0] == "approvals") {
            app.acceptApprovalLink(uri.pathSegments[1])
        }
    }
    override fun onStart() { super.onStart(); app.foregrounded() }
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) app.backgrounded() }
}
