package com.example.copenhagen

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/** FCM can start the process without an Activity; initialize the same project in that case as well. */
class CopenhagenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.FIREBASE_APP_ID.isNotBlank() && FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this, FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build())
        }
    }
}
