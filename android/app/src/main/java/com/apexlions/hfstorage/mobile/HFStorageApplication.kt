package com.apexlions.hfstorage.mobile

import android.app.Application
import android.util.Log
import com.apexlions.hfstorage.mobile.data.XetNative

class HFStorageApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // rustls-platform-verifier needs the Android Context before Xet opens
        // its first HTTPS connection. Keep the app usable if initialization
        // fails; the upload worker will surface the exact error to the user.
        XetNative.initialize(this).onFailure { error ->
            Log.e("HFStorageXet", "Xet TLS verifier initialization failed", error)
        }
    }
}
