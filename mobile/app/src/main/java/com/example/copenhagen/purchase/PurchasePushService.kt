package com.example.copenhagen.purchase

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.copenhagen.MainActivity
import com.example.copenhagen.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PurchasePushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Pay uses the Firebase Installation ID, never the deprecated registration token.
        DeviceRegistrationWorker.enqueue(this)
    }
    override fun onMessageReceived(message: RemoteMessage) {
        val kind = if (message.data["type"] == "PURCHASE_HANDOFF") "purchase" else "approval"
        val id = message.data[if (kind == "purchase") "purchaseRequestId" else "approvalId"]
        val route = PurchaseRoute.parse("$kind/$id") ?: return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("purchases", "구매 및 결제", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(this, MainActivity::class.java).setAction(route.persisted)
            .putExtra("purchaseRoute", route.persisted).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, route.persisted.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val isResult = message.data["type"] == "PAYMENT_RESULT"
        manager.notify(route.persisted.hashCode(), NotificationCompat.Builder(this, "purchases")
            .setSmallIcon(R.drawable.ic_payment_notification)
            .setContentTitle(if (isResult) "결제 결과 확인" else "구매 요청 확인")
            .setContentText("앱에서 주문 내용과 진행 상태를 확인해주세요.")
            .setContentIntent(pending).setAutoCancel(true).build())
    }
}
