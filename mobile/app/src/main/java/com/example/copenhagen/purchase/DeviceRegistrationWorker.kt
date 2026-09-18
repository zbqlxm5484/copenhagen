package com.example.copenhagen.purchase

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import kotlinx.coroutines.tasks.await

class DeviceRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val session = SecureSession(applicationContext)
        if (session.tokens.optString("accessToken").isBlank() || FirebaseApp.getApps(applicationContext).isEmpty()) {
            return Result.success()
        }
        return try {
            val fid = FirebaseInstallations.getInstance().id.await()
            ApiClient(session).request("/api/pay/v1/notification-installations", "PUT", PurchaseModel.json("fid" to fid))
            Result.success()
        } catch (error: ApiFailure) {
            if (error.status == 401 || error.status == 403) Result.failure() else Result.retry()
        } catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("notification-installation", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DeviceRegistrationWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
