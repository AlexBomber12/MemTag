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

val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `queue_items` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`epcNormalized` TEXT NOT NULL, " +
                    "`status` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`note` TEXT, " +
                    "`lastProximity` INTEGER" +
                    ")",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_queue_items_epcNormalized` " +
                    "ON `queue_items` (`epcNormalized`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_queue_items_status` " +
                    "ON `queue_items` (`status`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_queue_items_createdAt` " +
                    "ON `queue_items` (`createdAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_queue_items_updatedAt` " +
                    "ON `queue_items` (`updatedAt`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `queue_meta` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`currentEpcNormalized` TEXT, " +
                    "`lastImportAt` INTEGER, " +
                    "`lastExportAt` INTEGER, " +
                    "PRIMARY KEY(`id`)" +
                    ")",
            )
        }
    }
