package com.example.podscribe

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.content.Context
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("podcast-feed-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class FeedRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { Repo.refreshAll(); Result.success() } catch (_: Exception) { Result.retry() }
}
