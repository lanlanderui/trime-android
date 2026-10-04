// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.db

import android.content.ClipData
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(tableName = DatabaseBean.TABLE_NAME)
data class DatabaseBean(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String? = null,
    val html: String? = null,
    val type: BeanType = BeanType.TEXT,
    val time: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    /**
     * Path of a copied image; unused now and kept only so the schema matches the databases that
     * were migrated to version 5.
     *
     * Image support was built and then taken back out (it was not used often enough), but Room
     * refuses to open a database whose version is newer than the entity's, and dropping the
     * column would also change the schema Room validates on every open. A leftover row of an
     * earlier build keeps working: it reads back as [BeanType.IMAGE] and the panel shows it as an
     * empty entry the user can delete.
     */
    val imagePath: String? = null,
) {
    companion object {
        const val TABLE_NAME = "t_data"

        fun fromClipData(clipData: ClipData): DatabaseBean? {
            val str = clipData.getItemAt(0).text?.toString() ?: return null
            return DatabaseBean(text = str)
        }

        fun fromInputConnection(inputConnection: InputConnection): DatabaseBean? {
            val str = inputConnection.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString() ?: return null
            return DatabaseBean(text = str)
        }
    }

    /**
     * [IMAGE] is what an earlier build stored a copied image as. It stays in the enum for the same
     * reason [imagePath] stays in the entity: the ordinal is what the database holds, and a row
     * written by that build would fail to read (`entries[ordinal]`) without it.
     *
     * New types are appended for the same reason -- the ordinals are the stored values, so putting
     * one anywhere else would rename every row that is already there.
     */
    enum class BeanType {
        TEXT,
        HTML,
        IMAGE,
    }

    class Converters {
        @TypeConverter
        fun beanTypeToInt(beanType: BeanType?): Int? = beanType?.ordinal

        @TypeConverter
        fun intToBeanType(ordinal: Int?): BeanType? = ordinal?.let { BeanType.entries[it] }
    }
}
