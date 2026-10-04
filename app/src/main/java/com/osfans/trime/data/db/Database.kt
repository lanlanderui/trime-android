// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.db

import android.content.Context
import androidx.room.Database as RoomAnnotation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// The annotation is imported under an alias on purpose: a plain `androidx.room.Database` import
// would shadow this class inside its own body, and `Database::class.java` below would then name
// the annotation instead of the schema.
@RoomAnnotation(entities = [DatabaseBean::class], version = 5)
@TypeConverters(DatabaseBean.Converters::class)
abstract class Database : RoomDatabase() {
    abstract fun databaseDao(): DatabaseDao

    companion object {
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    if (db.needUpgrade(4)) {
                        db.execSQL("ALTER TABLE ${DatabaseBean.TABLE_NAME} RENAME TO _t_data")
                        db.execSQL("ALTER TABLE _t_data ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS ${DatabaseBean.TABLE_NAME} (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                text TEXT,
                                html TEXT,
                                type INTEGER NOT NULL,
                                time INTEGER NOT NULL,
                                pinned INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            INSERT INTO ${DatabaseBean.TABLE_NAME} (id, text, html, type, time, pinned)
                            SELECT id, text, html, type, time, pinned FROM _t_data
                            """.trimIndent(),
                        )
                        db.execSQL("DROP TABLE _t_data")
                    }
                }
            }

        /**
         * Adds the column an earlier build used for clipboard images.
         *
         * The image feature itself was taken back out, but the databases that ran it were migrated
         * to version 5, and Room will not open a database that is newer than the entity -- so the
         * version and this migration stay, and the column stays nullable and unused.
         */
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    if (db.needUpgrade(5)) {
                        db.execSQL("ALTER TABLE ${DatabaseBean.TABLE_NAME} ADD COLUMN imagePath TEXT")
                    }
                }
            }

        /**
         * Opens a database of this schema under [name], with every migration registered.
         *
         * One schema class backs two files -- `clipboard.db` and `collection.db` -- and each builder
         * has to know about the migrations on its own: one that is missing a path throws on its
         * first query, which for the paging sources happens while the clipboard window opens and
         * takes the whole process with it. Hence the single place that lists them.
         */
        fun open(
            context: Context,
            name: String,
        ): Database =
            Room
                .databaseBuilder(context, Database::class.java, name)
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
