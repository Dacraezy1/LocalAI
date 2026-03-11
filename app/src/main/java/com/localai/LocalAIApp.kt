package com.localai

import android.app.Application
import android.util.Log

class LocalAIApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("LocalAI", "App started — Unisoc T610 ARM64 optimized build")
    }
}
