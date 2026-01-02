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

val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `queue_items` ADD COLUMN `name` TEXT")
            db.execSQL("ALTER TABLE `queue_items` ADD COLUMN `lastSeenAt` INTEGER")
            db.execSQL("ALTER TABLE `queue_items` ADD COLUMN `source` TEXT")
        }
    }

val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `inventory_items_new` (" +
                    "`libraryId` TEXT NOT NULL, " +
                    "`entryId` TEXT NOT NULL, " +
                    "`epcNormalized` TEXT NOT NULL, " +
                    "`name` TEXT, " +
                    "`content` TEXT, " +
                    "`locationPath` TEXT, " +
                    "`status` TEXT, " +
                    "`category` TEXT, " +
                    "`comment` TEXT, " +
                    "`labelRev` TEXT, " +
                    "`toPrint` INTEGER, " +
                    "`um` TEXT, " +
                    "`qrRaw` TEXT, " +
                    "`photoThumbUrlOrRef` TEXT, " +
                    "`updatedAt` INTEGER, " +
                    "`syncRunId` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`libraryId`, `entryId`)" +
                    ")",
            )
            db.execSQL(
                "INSERT INTO `inventory_items_new` (" +
                    "`libraryId`, `entryId`, `epcNormalized`, `name`, `content`, `locationPath`, " +
                    "`status`, `category`, `comment`, `labelRev`, `toPrint`, `um`, `qrRaw`, " +
                    "`photoThumbUrlOrRef`, `updatedAt`, `syncRunId`" +
                    ") " +
                    "SELECT " +
                    "COALESCE((SELECT `libraryId` FROM `sync_state` ORDER BY `lastSyncAt` DESC LIMIT 1), ''), " +
                    "`entryId`, `epcNormalized`, `name`, `content`, `locationPath`, " +
                    "`status`, `category`, `comment`, `labelRev`, `toPrint`, `um`, `qrRaw`, " +
                    "`photoThumbUrlOrRef`, `updatedAt`, " +
                    "COALESCE((SELECT `lastSyncAt` FROM `sync_state` ORDER BY `lastSyncAt` DESC LIMIT 1), 0) " +
                    "FROM `inventory_items`",
            )
            db.execSQL("DROP TABLE `inventory_items`")
            db.execSQL("ALTER TABLE `inventory_items_new` RENAME TO `inventory_items`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_inventory_items_libraryId_epcNormalized` " +
                    "ON `inventory_items` (`libraryId`, `epcNormalized`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_inventory_items_libraryId_status` " +
                    "ON `inventory_items` (`libraryId`, `status`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_inventory_items_libraryId_category` " +
                    "ON `inventory_items` (`libraryId`, `category`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_inventory_items_libraryId_locationPath` " +
                    "ON `inventory_items` (`libraryId`, `locationPath`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_inventory_items_libraryId_toPrint` " +
                    "ON `inventory_items` (`libraryId`, `toPrint`)",
            )
        }
    }
