package com.mojaloss.core.downloads

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler

/** Wi-Fi is a real transport constraint, including after the app process has gone away. */
internal class WifiDownloadScheduler(context: Context, private val wifiOnly: Boolean) : Scheduler {
    private val context = context.applicationContext
    private val jobs = this.context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    override fun schedule(requirements: Requirements, servicePackage: String, serviceAction: String): Boolean {
        val extras = PersistableBundle().apply { putBoolean(WIFI_ONLY, wifiOnly) }
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, OfflineResumeJobService::class.java))
            .setExtras(extras).setPersisted(true).setRequiresStorageNotLow(true)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        if (Build.VERSION.SDK_INT >= 28) {
            val network = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (wifiOnly) network.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            builder.setRequiredNetwork(network.build())
        } else builder.setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
        return jobs.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
    }
    override fun cancel(): Boolean { jobs.cancel(JOB_ID); return true }
    override fun getSupportedRequirements(requirements: Requirements): Requirements =
        Requirements(requirements.requirements and (Requirements.NETWORK or Requirements.NETWORK_UNMETERED))
    companion object { const val JOB_ID = 2016; const val WIFI_ONLY = "wifi_only" }
}

/** A scheduler wake never downloads directly, and never bypasses the account/header/transport guards. */
class OfflineResumeJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val wifiOnly = params.extras.getBoolean(WifiDownloadScheduler.WIFI_ONLY, true)
        val wifiAllowed = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        // API 26-27 scheduler cannot express an actual Wi-Fi transport: check it before waking transfer work.
        if (wifiOnly && !wifiAllowed) { jobFinished(params, true); return false }
        return try {
            DownloadService.sendResumeDownloads(this, OfflineDownloadService::class.java, true)
            false
        } catch (_: Exception) { jobFinished(params, true); false }
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
