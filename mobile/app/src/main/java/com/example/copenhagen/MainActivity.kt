package com.example.copenhagen

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.copenhagen.purchase.BiometricApproval
import com.example.copenhagen.purchase.PurchaseModel
import com.example.copenhagen.purchase.PurchaseRoute
import com.example.copenhagen.purchase.PurchaseScreen
import com.example.copenhagen.ui.theme.CopenhagenAppTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private lateinit var model: PurchaseModel
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && ::model.isInitialized) model.pushMessage = "알림 권한이 꺼져 있습니다. 앱의 승인 목록에서 확인해주세요."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model = PurchaseModel(applicationContext)
        acceptIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val biometric = BiometricApproval(this, model.session.deviceId)
        setContent { CopenhagenAppTheme { PurchaseScreen(model, biometric) } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
        if (model.loggedIn) lifecycleScope.launch { model.action { model.openRoute() } }
    }
    private fun acceptIntent(intent: Intent) {
        val route = if (intent.data != null) PurchaseRoute.link(intent.data.toString(),
            BuildConfig.APP_LINK_HOST, BuildConfig.APP_LINK_PATH_PREFIX)
            else PurchaseRoute.parse(intent.getStringExtra("purchaseRoute"))
        model.route(route)
    }
}
