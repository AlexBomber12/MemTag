package com.alexbomber12.memtag.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `actions_log` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`createdAtEpochMs` INTEGER NOT NULL, " +
                    "`actionType` TEXT NOT NULL, " +
                    "`expectedEpc` TEXT, " +
                    "`currentEpc` TEXT, " +
                    "`result` TEXT NOT NULL, " +
                    "`message` TEXT" +
                    ")",
            )
        }
    }
