/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

object CollectionHelper : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var cltDb: Database
    private lateinit var cltDao: DatabaseDao

    private val mutex = Mutex()

    private var lastBean: DatabaseBean? = null

    fun init(context: Context) {
        cltDb = Database.open(context, "collection.db")
        cltDao = cltDb.databaseDao()
    }

    suspend fun insert(bean: DatabaseBean) = cltDao.insert(bean)

    suspend fun get(id: Int) = cltDao.get(id)

    suspend fun haveUnpinned() = cltDao.haveUnpinned()

    fun allBeans() = cltDao.allBeans()

    suspend fun pin(id: Int) = cltDao.updatePinned(id, true)

    suspend fun unpin(id: Int) = cltDao.updatePinned(id, false)

    suspend fun delete(id: Int) = cltDao.delete(id)

    /**
     * Deletes collection entries.
     *
     * Mirrors [ClipboardHelper.deleteAll]: pinned entries survive unless [BulkDeleteScope.ALL] is
     * passed explicitly.
     */
    suspend fun deleteAll(scope: BulkDeleteScope = BulkDeleteScope.KEEP_PINNED) {
        if (scope.removesPinned) {
            cltDao.deleteAll()
        } else {
            cltDao.deleteAllUnpinned()
        }
    }

    suspend fun updateText(
        id: Int,
        text: String,
    ) {
        lastBean?.let {
            if (id == it.id) lastBean = it.copy(text = text)
        }
        cltDao.updateText(id, text)
    }

    fun addNewBean(text: String) {
        launch {
            mutex.withLock {
                val bean = DatabaseBean(text = text)
                if (bean.text.isNullOrBlank()) return@withLock
                try {
                    cltDao.find(text)?.let {
                        lastBean = it.copy(time = bean.time)
                        cltDao.updateTime(it.id, bean.time)
                        return@withLock
                    }
                    val insertedBean = cltDb.withTransaction {
                        val rowId = cltDao.insert(bean)
                        cltDao.get(rowId) ?: bean
                    }
                    lastBean = insertedBean
                } catch (e: Exception) {
                    Timber.w("Failed to update collection database: $e")
                    lastBean = bean
                }
            }
        }
    }
}
