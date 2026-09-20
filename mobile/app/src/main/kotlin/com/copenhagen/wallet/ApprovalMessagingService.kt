package com.copenhagen.wallet

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.copenhagen.wallet.data.validId
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** 푸시는 승인 ID만 전달한다. 상세 조회와 사용자 인증은 기존 앱 흐름에서 처리한다. */
@SuppressLint("MissingFirebaseInstanceTokenRefresh") // FID 모드: 토큰 대신 로그인/복원/재연결 때 현재 FID를 등록한다.
class ApprovalMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val id = message.data["approvalId"]?.takeIf(::validId) ?: return
        createChannel(this)
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(this, MainActivity::class.java)
            .setData("copenhagen://approvals/$id".toUri())
            .putExtra("approvalId", id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("결제 승인 요청")
            .setContentText("앱에서 요청 내용을 확인해 주세요.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        manager.notify(id, 0, notification)
    }

    companion object {
        const val CHANNEL = "approvals"
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "승인 요청 알림", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
