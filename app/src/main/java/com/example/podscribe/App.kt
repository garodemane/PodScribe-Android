package com.example.podscribe

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("podcast-feed-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
        // Refresh on launch as well; periodic work may not have run recently while the app was closed.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { Repo.refreshIfStale() }
    }
}

class FeedRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { Repo.refreshIfStale(force = true); Result.success() } catch (_: Exception) { Result.retry() }
}
