// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.osfans.trime.R
import com.osfans.trime.TrimeApplication
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeLifecycle
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.RimeSchema
import com.osfans.trime.core.StatusProto
import com.osfans.trime.core.lifecycleScope
import com.osfans.trime.core.whenReady
import com.osfans.trime.data.sync.RimeDataSync
import com.osfans.trime.ui.main.LogActivity
import com.osfans.trime.util.DeployNotification
import com.osfans.trime.util.appContext
import com.osfans.trime.util.readText
import com.osfans.trime.util.subprocess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import splitties.systemservices.notificationManager
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes

/**
 * Manage the singleton instance of [Rime]
 *
 * To use rime, client should call [createSession] to obtain a [RimeSession],
 * and call [destroySession] on client destroyed. Client should not leak the instance of [RimeApi],
 * and must use [RimeSession] to access rime functionalities.
 *
 * The instance of [Rime] always exists,but whether the dispatcher runs and callback works depend on clients, i.e.
 * if no clients are connected, [Rime.finalize] will be called.
 *
 * Functions are thread-safe in this class.
 *
 * Adapted from [fcitx5-android/FcitxDaemon.kt](https://github.com/fcitx5-android/fcitx5-android/blob/364afb44dcf0d9e3db3d43a21a32601b2190cbdf/app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt)
 */
object RimeDaemon {
    private const val STARTUP_RETRY_DELAY_MS = 1_000L
    private const val STARTUP_RETRY_MAX_ATTEMPTS = 30
    private val DEPLOY_WAKE_LOCK_TIMEOUT = 20.minutes

    private val realRime by lazy { Rime() }

    private val rimeImpl by lazy { object : RimeApi by realRime {} }

    private val sessions = mutableMapOf<String, RimeSession>()

    private val lock = ReentrantLock()

    /**
     * Lock-free snapshots of [sessions], republished whenever the map changes.
     *
     * Callers that only need "some session" or "is this session still
     * established" read these instead of touching the mutable map: the keyboard
     * asks on every draw, and iterating a HashMap while another thread runs put
     * or remove is not safe.
     */
    @Volatile
    private var cachedSession: RimeSession? = null

    @Volatile
    private var establishedNames: Set<String> = emptySet()

    /** Republishes the snapshots above. Call while holding [lock]. */
    private fun publishSessionsLocked() {
        cachedSession = sessions.firstNotNullOfOrNull { it.value }
        establishedNames = sessions.keys.toSet()
    }

    @Volatile
    private var startupRetryJob: Job? = null

    private val deployWakeLock: PowerManager.WakeLock by lazy {
        (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Trime:RimeDeploy")
            .apply { setReferenceCounted(false) }
    }

    private fun acquireDeployWakeLock() {
        if (deployWakeLock.isHeld) deployWakeLock.release()
        deployWakeLock.acquire(DEPLOY_WAKE_LOCK_TIMEOUT.inWholeMilliseconds)
    }

    private fun releaseDeployWakeLock() {
        if (deployWakeLock.isHeld) deployWakeLock.release()
    }

    private fun establish(name: String) = object : RimeSession {
        private inline fun <T> ensureEstablished(block: () -> T) = if (name in establishedNames) {
            block()
        } else {
            throw IllegalStateException("Session $name is not established")
        }

        // Cached state: plain field reads, so they neither block nor reach the
        // native layer. Deliberately not gated by ensureEstablished() -- reading
        // a stale snapshot is harmless and keeps UI hot paths lock-free.
        override val status: StatusProto
            get() = rimeImpl.statusCached
        override val paging: Boolean
            get() = rimeImpl.paging
        override val hasMenu: Boolean
            get() = rimeImpl.hasMenu
        override val composition: CompositionProto
            get() = rimeImpl.compositionCached
        override val schema: RimeSchema
            get() = rimeImpl.schemaCached

        override fun <T> run(block: suspend RimeApi.() -> T): T = ensureEstablished {
            runBlocking { block(rimeImpl) }
        }

        override suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T = ensureEstablished {
            realRime.lifecycle.whenReady { block(rimeImpl) }
        }

        override fun runIfReady(block: suspend RimeApi.() -> Unit) {
            ensureEstablished {
                if (realRime.isReady) {
                    realRime.lifecycleScope.launch {
                        block(rimeImpl)
                    }
                }
            }
        }

        override val lifecycleScope: CoroutineScope
            get() = realRime.lifecycle.lifecycleScope
    }

    private fun tryStartRimeLocked(): Boolean {
        if (realRime.lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            return true
        }
        return realRime.startup()
    }

    private fun scheduleStartupRetry() {
        if (startupRetryJob?.isActive == true) return
        Timber.i("Scheduling Rime startup retry until storage is available")
        startupRetryJob =
            TrimeApplication.getInstance().coroutineScope.launch {
                repeat(STARTUP_RETRY_MAX_ATTEMPTS) { attempt ->
                    delay(STARTUP_RETRY_DELAY_MS)
                    if (sessions.isEmpty()) return@launch
                    if (realRime.lifecycle.currentState != RimeLifecycle.State.STOPPED) return@launch
                    if (!RimeDataSync.isStorageAvailable(appContext)) {
                        Timber.d("Rime startup retry ${attempt + 1}: storage still unavailable")
                        return@repeat
                    }
                    val started =
                        lock.withLock {
                            if (sessions.isEmpty()) return@launch
                            tryStartRimeLocked()
                        }
                    if (started) {
                        Timber.i("Rime started after storage became available")
                        return@launch
                    }
                }
                Timber.w("Rime startup retry exhausted while sessions remain connected")
            }
    }

    fun createSession(name: String): RimeSession = lock.withLock {
        if (name in sessions) {
            return@withLock sessions.getValue(name)
        }
        if (!tryStartRimeLocked()) {
            scheduleStartupRetry()
        }
        val session = establish(name)
        sessions[name] = session
        publishSessionsLocked()
        return@withLock session
    }

    fun destroySession(name: String): Unit = lock.withLock {
        if (name !in sessions) {
            return
        }
        sessions -= name
        publishSessionsLocked()
        if (sessions.isEmpty()) {
            startupRetryJob?.cancel()
            startupRetryJob = null
            realRime.finalize()
            releaseDeployWakeLock()
        }
    }

    /** Immediately retries startup after the user restores storage access. */
    fun retryStartup() = lock.withLock {
        if (sessions.isEmpty()) return@withLock
        startupRetryJob?.cancel()
        startupRetryJob = null
        if (!tryStartRimeLocked()) {
            scheduleStartupRetry()
        }
    }

    /**
     * Reuse a session for remote service.
     *
     * Reads a lock-free snapshot rather than the session map: this is called
     * from the keyboard draw path.
     */
    fun getFirstSessionOrNull(): RimeSession? = cachedSession

    private var restartId = 0

    init {
        DeployNotification.ensureChannel()
        TrimeApplication.getInstance().coroutineScope.launch {
            realRime.messageFlow.collect {
                handleRimeMessage(it)
            }
        }
    }

    private inline fun sendNotification(
        id: Int,
        buildAction: NotificationCompat.Builder.() -> Unit,
    ) {
        val builder =
            NotificationCompat
                .Builder(appContext, DeployNotification.CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.rime_daemon))
        builder.buildAction()
        builder.build().let { notificationManager.notify(id, it) }
    }

    /**
     * Restart Rime instance to deploy while keep the session
     */
    fun restartRime(fullCheck: Boolean = false) = lock.withLock {
        val id = restartId++
        if (!fullCheck) {
            sendNotification(id) {
                setSmallIcon(R.drawable.ic_baseline_sync_24)
                setContentTitle(appContext.getString(R.string.rime_daemon))
                setContentText(appContext.getString(R.string.restarting_rime))
                setOngoing(true)
                setProgress(100, 0, true)
                setPriority(NotificationCompat.PRIORITY_HIGH)
            }
        }
        realRime.finalize()
        if (!tryStartRimeLocked()) {
            scheduleStartupRetry()
        }
        TrimeApplication.getInstance().coroutineScope.launch {
            realRime.lifecycle.whenReady {
                notificationManager.cancel(id)
            }
        }
    }

    private suspend fun handleRimeMessage(it: RimeMessage<*>) {
        if (it is RimeMessage.DeployMessage) {
            when (it.data) {
                RimeMessage.DeployMessage.State.Start -> {
                    acquireDeployWakeLock()
                    DeployNotification.showProgress()
                    withContext(Dispatchers.IO) { subprocess("logcat", "--clear") }
                }

                RimeMessage.DeployMessage.State.Success -> {
                    releaseDeployWakeLock()
                    DeployNotification.showSuccess()
                }

                RimeMessage.DeployMessage.State.Failure -> {
                    releaseDeployWakeLock()
                    val intent =
                        Intent(appContext, LogActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            val log =
                                subprocess("logcat", "-v", "brief", "-s", "rime.trime:W", "-d")
                                    .readText()
                            putExtra(LogActivity.FROM_DEPLOY, true)
                            putExtra(LogActivity.DEPLOY_FAILURE_TRACE, log)
                        }
                    val pendingIntent =
                        PendingIntent.getActivity(
                            appContext,
                            0,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    DeployNotification.showFailure(pendingIntent)
                }
            }
        }
    }
}
