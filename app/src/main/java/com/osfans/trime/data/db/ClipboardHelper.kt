/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.room.Room
import androidx.room.withTransaction
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.compileRegexSet
import com.osfans.trime.util.matchesAny
import com.osfans.trime.util.removeRegexSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.systemservices.clipboardManager
import timber.log.Timber

object ClipboardHelper :
    ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var clbDb: Database
    private lateinit var clbDao: DatabaseDao

    fun interface OnClipboardUpdateListener {
        fun onUpdate(bean: DatabaseBean)
    }

    private val mutex = Mutex()

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
    }

    private val clipPref = AppPrefs.defaultInstance().clipboard

    private val enabledPref = clipPref.clipboardListening

    @Keep
    private val enabledListener = PreferenceDelegate.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
        }
    }

    private val limitPref = clipPref.clipboardLimit

    @Keep
    private val limitListener = PreferenceDelegate.OnChangeListener<Int> { _, _ ->
        launch { removeOutdated() }
    }

    private val compareRulesPref = clipPref.clipboardCompareRules
    private val outputRulesPref = clipPref.clipboardOutputRules

    /**
     * Bumped when either rule set changes so the compiled [compareRules] / [outputRules] are
     * rebuilt from the preferences.
     *
     * Both are user-editable in the settings, and they used to be `by lazy`, which kept the
     * original rules in force for the life of the process: the edit was accepted and silently did
     * nothing until the app was killed. Same generation-counter shape as
     * [ColorManager.colorGeneration], which invalidates the colour caches the same way.
     *
     * Volatile because the bump lands on whichever thread committed the preference while the rules
     * are read from the clipboard coroutine on [Dispatchers.Default].
     */
    @Volatile
    private var rulesGeneration = 0L

    private var compiledRulesGeneration = -1L
    private var compiledCompareRules: Set<Regex> = emptySet()
    private var compiledOutputRules: Set<Regex> = emptySet()

    private fun ensureRulesCompiled() {
        if (compiledRulesGeneration == rulesGeneration) return
        compiledCompareRules = compileRegexSet(compareRulesPref.getValue(), trim = true)
        compiledOutputRules = compileRegexSet(outputRulesPref.getValue(), trim = false)
        compiledRulesGeneration = rulesGeneration
    }

    /** Deduplication rules: clips are stored with these stripped. */
    private val compareRules: Set<Regex>
        get() {
            ensureRulesCompiled()
            return compiledCompareRules
        }

    /** Filter rules: a clip matching any of them is not recorded at all. */
    private val outputRules: Set<Regex>
        get() {
            ensureRulesCompiled()
            return compiledOutputRules
        }

    @Keep
    private val rulesListener = PreferenceDelegate.OnChangeListener<String> { _, _ ->
        rulesGeneration++
    }

    var lastBean: DatabaseBean? = null

    private fun updateLastBean(bean: DatabaseBean) {
        lastBean = bean
        onUpdateListeners.forEach { it.onUpdate(bean) }
    }

    fun init(context: Context) {
        clipboardManager.addPrimaryClipChangedListener(this)
        clbDb =
            Room
                .databaseBuilder(context, Database::class.java, "clipboard.db")
                .addMigrations(Database.MIGRATION_3_4)
                .build()
        clbDao = clbDb.databaseDao()
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitListener.onChange(limitPref.key, limitPref.getValue())
        limitPref.registerOnChangeListener(limitListener)
        // Both rule sets are user-editable, so their compiled caches have to be droppable.
        compareRulesPref.registerOnChangeListener(rulesListener)
        outputRulesPref.registerOnChangeListener(rulesListener)
        launch { updateItemCount() }
    }

    suspend fun get(id: Int) = clbDao.get(id)

    suspend fun haveUnpinned() = clbDao.haveUnpinned()

    fun allBeans() = clbDao.allBeans()

    suspend fun pin(id: Int) = clbDao.updatePinned(id, true)

    suspend fun unpin(id: Int) = clbDao.updatePinned(id, false)

    suspend fun updateText(
        id: Int,
        text: String,
    ) {
        lastBean?.let {
            if (id == it.id) updateLastBean(it.copy(text = text))
        }
        clbDao.updateText(id, text)
    }

    suspend fun delete(id: Int) {
        clbDao.delete(id)
        updateItemCount()
    }

    /**
     * Deletes clipboard entries.
     *
     * Defaults to [BulkDeleteScope.KEEP_PINNED]: a pinned entry is one the user deliberately kept,
     * so bulk deletion has to leave it alone. Wiping the whole table is opt-in and has to be spelled
     * [BulkDeleteScope.ALL] at the call site.
     */
    suspend fun deleteAll(scope: BulkDeleteScope = BulkDeleteScope.KEEP_PINNED) {
        if (scope.removesPinned) {
            clbDao.deleteAll()
        } else {
            clbDao.deleteAllUnpinned()
        }
        updateItemCount()
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    /**
     * 此方法设置监听剪贴板变化，如有新的剪贴内容，就启动选定的剪贴板管理器
     *
     * - [compareRules] 比较规则。每次通知剪贴板管理器，都会保存 ClipBoardCompare 处理过的 string。
     * 如果两次处理过的内容不变，则不通知。
     *
     * - [outputRules] 输出规则。如果剪贴板内容与规则匹配，则不通知剪贴板管理器。
     */
    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val timestamp = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = timestamp
            lastClipHash = hash
        }
        launch {
            mutex.withLock {
                val bean = DatabaseBean.fromClipData(clip) ?: return@withLock
                if (bean.text.isNullOrBlank()) return@withLock
                if (bean.text.matchesAny(outputRules) ||
                    bean.text.removeRegexSet(compareRules).isEmpty()
                ) {
                    return@withLock
                }
                try {
                    clbDao.find(bean.text)?.let {
                        updateLastBean(it.copy(time = bean.time))
                        clbDao.updateTime(it.id, bean.time)
                        return@withLock
                    }
                    val insertedBean =
                        clbDb.withTransaction {
                            val rowId = clbDao.insert(bean)
                            removeOutdated()
                            updateItemCount()
                            clbDao.get(rowId) ?: bean
                        }
                    updateLastBean(insertedBean)
                    updateItemCount()
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database: $exception")
                    updateLastBean(bean)
                }
            }
        }
    }

    private suspend fun removeOutdated() {
        val limit = limitPref.getValue()
        val unpinned = clbDao.getAllUnpinned()
        if (unpinned.size > limit) {
            val outdated =
                unpinned
                    .sortedBy { it.id }
                    .getOrNull(unpinned.size - limit)
            clbDao.deletedUnpinnedEarlierThan(outdated?.time ?: System.currentTimeMillis())
        }
    }
}
