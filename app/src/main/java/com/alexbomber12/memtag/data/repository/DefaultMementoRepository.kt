package com.alexbomber12.memtag.data.repository

import androidx.room.withTransaction
import com.alexbomber12.memtag.BuildConfig
import com.alexbomber12.memtag.core.logging.Logger
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.db.InventoryItemDao
import com.alexbomber12.memtag.db.InventoryItemEntity
import com.alexbomber12.memtag.db.MemTagDatabase
import com.alexbomber12.memtag.db.SyncStateDao
import com.alexbomber12.memtag.db.SyncStateEntity
import com.alexbomber12.memtag.domain.InventoryItem
import com.alexbomber12.memtag.domain.LookupResult
import com.alexbomber12.memtag.domain.SyncProgress
import com.alexbomber12.memtag.domain.SyncResult
import com.alexbomber12.memtag.domain.SyncStage
import com.alexbomber12.memtag.domain.SyncState
import com.alexbomber12.memtag.domain.SyncStatus
import com.alexbomber12.memtag.integrations.memento.FieldIdMap
import com.alexbomber12.memtag.integrations.memento.MementoClient
import com.alexbomber12.memtag.integrations.memento.MementoEntriesPager
import com.alexbomber12.memtag.integrations.memento.MementoPagingException
import com.alexbomber12.memtag.integrations.memento.MementoResponseException
import com.alexbomber12.memtag.integrations.memento.MementoSchemaException
import com.alexbomber12.memtag.integrations.memento.MementoSettingsValidation
import com.alexbomber12.memtag.integrations.memento.MementoSettingsValidator
import com.alexbomber12.memtag.integrations.memento.PagingStrategy
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import com.alexbomber12.memtag.util.epc.EpcValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

class DefaultMementoRepository(
    private val settingsStore: SettingsStore,
    private val database: MemTagDatabase,
    private val inventoryItemDao: InventoryItemDao,
    private val syncStateDao: SyncStateDao,
    private val mementoClient: MementoClient,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MementoRepository {
    private val pager = MementoEntriesPager(mementoClient, logger)

    override fun observeSyncState(libraryId: String): Flow<SyncState?> {
        if (libraryId.isBlank()) {
            return flowOf(null)
        }
        return syncStateDao.observe(libraryId).map { entity ->
            entity?.toDomain()
        }
    }

    override suspend fun getSyncState(libraryId: String): SyncState? {
        if (libraryId.isBlank()) {
            return null
        }
        return withContext(ioDispatcher) {
            syncStateDao.get(libraryId)?.toDomain()
        }
    }

    override fun observeLocalItemCount(): Flow<Int> {
        return inventoryItemDao.observeCount()
    }

    override suspend fun syncLibrary(
        libraryId: String,
        onProgress: (SyncProgress) -> Unit,
    ): SyncResult {
        return withContext(ioDispatcher) {
            val settings = settingsStore.settingsFlow.first()
            val resolvedLibraryId = if (libraryId.isNotBlank()) libraryId else settings.mementoLibraryId
            val validation =
                MementoSettingsValidator.validate(
                    baseUrl = settings.mementoBaseUrl,
                    token = settings.mementoToken,
                    libraryId = resolvedLibraryId,
                )
            if (validation is MementoSettingsValidation.Error) {
                val message = validation.message
                logger.w(TAG, "Sync aborted: $message")
                return@withContext SyncResult(
                    status = SyncStatus.ERROR,
                    fetchedCount = 0,
                    storedCount = 0,
                    skippedCount = 0,
                    durationMs = 0L,
                    pagingStrategy = null,
                    errorMessage = message,
                )
            }
            val config = (validation as MementoSettingsValidation.Valid).config
            val startTime = System.currentTimeMillis()
            var fetchedRawCount = 0
            var fetchedCount = 0
            var storedCount = 0
            var skippedCount = 0
            var duplicateCount = 0
            var pagingStrategy: PagingStrategy? = null
            val entryStatuses = mutableMapOf<String, EntryStatus>()
            onProgress(SyncProgress(stage = SyncStage.STARTING, fetchedCount = 0, storedCount = 0, skippedCount = 0))
            try {
                logger.i(TAG, "Sync start for library ${config.libraryId} at ${config.baseUrl}")
                onProgress(
                    SyncProgress(
                        stage = SyncStage.FETCHING_SCHEMA,
                        fetchedCount = fetchedCount,
                        storedCount = storedCount,
                        skippedCount = skippedCount,
                    ),
                )
                val schema = mementoClient.fetchLibrarySchema(config)
                val fieldIdMap = FieldIdMap.fromSchema(schema)
                val summary =
                    pager.pageThroughEntries(config, PAGE_SIZE) { page ->
                        if (BuildConfig.DEBUG) {
                            logger.d(
                                TAG,
                                "Sync page entries=${page.entries.size} " +
                                    "page=${page.page} pageCount=${page.pageCount} " +
                                    "nextTokenPresent=${!page.nextPageToken.isNullOrBlank()} " +
                                    "nextUrl=${redactTokenInUrl(page.nextUrl)}",
                            )
                        }
                        fetchedRawCount += page.entries.size
                        val items = mutableListOf<InventoryItemEntity>()
                        page.entries.forEach { entry ->
                            val entryId = entry.entryId?.trim().orEmpty()
                            val rawEpc = valueAsString(entry.fieldValues[fieldIdMap.epcId])
                            val key = resolveEntryKey(entryId, rawEpc)
                            if (key == null) {
                                fetchedCount += 1
                                skippedCount += 1
                                if (BuildConfig.DEBUG) {
                                    logger.d(TAG, "Sync skip reason=missing_key")
                                }
                                return@forEach
                            }
                            val status = entryStatuses[key]
                            val outcome = parseEntry(entry, fieldIdMap, entryId, rawEpc)
                            if (outcome.item == null) {
                                if (status == null) {
                                    entryStatuses[key] = EntryStatus.IGNORED
                                    fetchedCount += 1
                                    skippedCount += 1
                                    if (BuildConfig.DEBUG) {
                                        logger.d(TAG, "Sync skip key=$key reason=${outcome.reason}")
                                    }
                                } else {
                                    duplicateCount += 1
                                    if (BuildConfig.DEBUG) {
                                        logger.d(TAG, "Sync duplicate key=$key")
                                    }
                                }
                                return@forEach
                            }
                            if (status == EntryStatus.SAVED) {
                                duplicateCount += 1
                                if (BuildConfig.DEBUG) {
                                    logger.d(TAG, "Sync duplicate key=$key")
                                }
                                return@forEach
                            }
                            if (status == EntryStatus.IGNORED) {
                                skippedCount = (skippedCount - 1).coerceAtLeast(0)
                            } else {
                                fetchedCount += 1
                            }
                            entryStatuses[key] = EntryStatus.SAVED
                            items.add(outcome.item.toEntity())
                        }
                        if (items.isNotEmpty()) {
                            items.chunked(BATCH_SIZE).forEach { batch ->
                                database.withTransaction {
                                    inventoryItemDao.upsertAll(batch)
                                }
                                storedCount += batch.size
                                onProgress(
                                    SyncProgress(
                                        stage = SyncStage.SAVING_BATCH,
                                        fetchedCount = fetchedCount,
                                        storedCount = storedCount,
                                        skippedCount = skippedCount,
                                    ),
                                )
                            }
                        } else {
                            onProgress(
                                SyncProgress(
                                    stage = SyncStage.FETCHING_ENTRIES,
                                    fetchedCount = fetchedCount,
                                    storedCount = storedCount,
                                    skippedCount = skippedCount,
                                ),
                            )
                        }
                    }
                pagingStrategy = summary.strategy
                val durationMs = System.currentTimeMillis() - startTime
                logger.i(
                    TAG,
                    "Sync completed. fetched=$fetchedCount stored=$storedCount skipped=$skippedCount " +
                        "duplicates=$duplicateCount raw=$fetchedRawCount durationMs=$durationMs",
                )
                val result =
                    SyncResult(
                        status = SyncStatus.SUCCESS,
                        fetchedCount = fetchedCount,
                        storedCount = storedCount,
                        skippedCount = skippedCount,
                        durationMs = durationMs,
                        pagingStrategy = pagingStrategy,
                        errorMessage = null,
                    )
                syncStateDao.upsert(
                    SyncStateEntity(
                        libraryId = config.libraryId,
                        lastSyncAt = System.currentTimeMillis(),
                        lastSyncStatus = SyncStatus.SUCCESS.name,
                        lastErrorMessage = null,
                    ),
                )
                onProgress(
                    SyncProgress(
                        stage = SyncStage.COMPLETED,
                        fetchedCount = fetchedCount,
                        storedCount = storedCount,
                        skippedCount = skippedCount,
                        message = "Sync completed.",
                    ),
                )
                result
            } catch (error: Throwable) {
                val message = mapError(error)
                logger.w(TAG, "Sync failed: $message", error)
                syncStateDao.upsert(
                    SyncStateEntity(
                        libraryId = config.libraryId,
                        lastSyncAt = System.currentTimeMillis(),
                        lastSyncStatus = SyncStatus.ERROR.name,
                        lastErrorMessage = message,
                    ),
                )
                onProgress(
                    SyncProgress(
                        stage = SyncStage.ERROR,
                        fetchedCount = fetchedCount,
                        storedCount = storedCount,
                        skippedCount = skippedCount,
                        message = message,
                    ),
                )
                SyncResult(
                    status = SyncStatus.ERROR,
                    fetchedCount = fetchedCount,
                    storedCount = storedCount,
                    skippedCount = skippedCount,
                    durationMs = System.currentTimeMillis() - startTime,
                    pagingStrategy = pagingStrategy,
                    errorMessage = message,
                )
            }
        }
    }

    override suspend fun lookupByEpc(epcRaw: String): LookupResult {
        return withContext(ioDispatcher) {
            val settings = settingsStore.settingsFlow.first()
            val validation =
                MementoSettingsValidator.validate(
                    baseUrl = settings.mementoBaseUrl,
                    token = settings.mementoToken,
                    libraryId = settings.mementoLibraryId,
                )
            if (validation is MementoSettingsValidation.Error) {
                return@withContext LookupResult.Error(validation.message)
            }
            if (!EpcValidator.isValidEpcHex(epcRaw)) {
                return@withContext LookupResult.Error("EPC must be a non-empty hex value.")
            }
            val normalized =
                runCatching { EpcNormalizer.normalize(epcRaw) }.getOrElse {
                    return@withContext LookupResult.Error("EPC must be a non-empty hex value.")
                }
            val local = inventoryItemDao.getByEpc(normalized)
            if (local != null) {
                return@withContext LookupResult.Found(local.toDomain())
            }
            LookupResult.NotFound
        }
    }

    override suspend fun searchInventory(
        query: String,
        limit: Int,
    ): List<InventoryItem> {
        return withContext(ioDispatcher) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) {
                return@withContext emptyList()
            }
            val normalizedQuery =
                if (EpcValidator.isValidEpcHex(trimmed)) {
                    runCatching { EpcNormalizer.normalize(trimmed) }.getOrElse { trimmed }
                } else {
                    trimmed
                }
            val likeQuery = "%$normalizedQuery%"
            inventoryItemDao.searchByText(likeQuery, limit).map { it.toDomain() }
        }
    }

    private fun parseEntry(
        entry: com.alexbomber12.memtag.integrations.memento.MementoEntry,
        fieldIdMap: FieldIdMap,
        entryId: String,
        epcRaw: String?,
    ): ParseOutcome {
        if (entryId.isEmpty()) {
            return ParseOutcome(null, "missing_entry_id")
        }
        val resolvedEpcRaw = epcRaw ?: return ParseOutcome(null, "missing_epc")
        val epcNormalized =
            runCatching { EpcNormalizer.normalize(resolvedEpcRaw) }.getOrNull()
                ?: return ParseOutcome(null, "invalid_epc")
        val name = valueAsString(entry.fieldValues[fieldIdMap.nameId()])
        val content = valueAsString(entry.fieldValues[fieldIdMap.contentId()])
        val locationFieldId = fieldIdMap.locationId()
        val rawLocation = locationFieldId?.let { entry.fieldValues[it] }
        val locationPath = valueAsLocation(rawLocation)
        logLocationDebug(
            entryId = entryId,
            epcNormalized = epcNormalized,
            name = name,
            locationFieldId = locationFieldId,
            rawLocation = rawLocation,
            locationPath = locationPath,
            fieldKeys = entry.fieldValues.keys,
        )
        val status = valueAsString(entry.fieldValues[fieldIdMap.statusId()])
        val category = valueAsString(entry.fieldValues[fieldIdMap.categoryId()])
        val comment = valueAsString(entry.fieldValues[fieldIdMap.commentId()])
        val labelRev = valueAsString(entry.fieldValues[fieldIdMap.labelRevId()])
        val toPrint = valueAsBoolean(entry.fieldValues[fieldIdMap.toPrintId()])
        val um = valueAsString(entry.fieldValues[fieldIdMap.umId()])
        val qrRaw = valueAsString(entry.fieldValues[fieldIdMap.qrId()])
        val photo = valueAsPhoto(entry.fieldValues[fieldIdMap.photoId()])
        return ParseOutcome(
            InventoryItem(
                entryId = entryId,
                epcNormalized = epcNormalized,
                name = name,
                content = content,
                locationPath = locationPath,
                status = status,
                category = category,
                comment = comment,
                labelRev = labelRev,
                toPrint = toPrint,
                um = um,
                qrRaw = qrRaw,
                photoThumbUrlOrRef = photo,
                updatedAt = entry.updatedAt,
            ),
            null,
        )
    }

    private fun InventoryItem.toEntity(): InventoryItemEntity {
        return InventoryItemEntity(
            entryId = entryId,
            epcNormalized = epcNormalized,
            name = name,
            content = content,
            locationPath = locationPath,
            status = status,
            category = category,
            comment = comment,
            labelRev = labelRev,
            toPrint = toPrint,
            um = um,
            qrRaw = qrRaw,
            photoThumbUrlOrRef = photoThumbUrlOrRef,
            updatedAt = updatedAt,
        )
    }

    private fun InventoryItemEntity.toDomain(): InventoryItem {
        return InventoryItem(
            entryId = entryId,
            epcNormalized = epcNormalized,
            name = name,
            content = content,
            locationPath = locationPath,
            status = status,
            category = category,
            comment = comment,
            labelRev = labelRev,
            toPrint = toPrint,
            um = um,
            qrRaw = qrRaw,
            photoThumbUrlOrRef = photoThumbUrlOrRef,
            updatedAt = updatedAt,
        )
    }

    private fun SyncStateEntity.toDomain(): SyncState {
        return SyncState(
            libraryId = libraryId,
            lastSyncAt = lastSyncAt,
            lastSyncStatus = SyncStatus.valueOf(lastSyncStatus),
            lastErrorMessage = lastErrorMessage,
        )
    }

    private fun valueAsString(value: Any?): String? {
        return when (value) {
            is String -> value.trim().takeIf { it.isNotEmpty() }
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Map<*, *> -> extractStringFromMap(value)
            is List<*> -> valueAsString(value.firstOrNull())
            else -> null
        }
    }

    private fun extractStringFromMap(map: Map<*, *>): String? {
        val candidates = listOf("url", "thumbUrl", "thumbnailUrl", "link", "value", "name", "path")
        candidates.forEach { key ->
            val candidate = map[key]
            val value = valueAsString(candidate)
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    private fun valueAsLocation(value: Any?): String? {
        return when (value) {
            is String -> value.trim().takeIf { it.isNotEmpty() }
            is List<*> -> locationFromList(value)
            is Map<*, *> -> locationFromMap(value)
            else -> valueAsString(value)
        }
    }

    private fun locationFromMap(map: Map<*, *>): String? {
        val pathCandidate = map["path"] ?: map["fullPath"] ?: map["locationPath"]
        val resolvedPath =
            when (pathCandidate) {
                is String -> pathCandidate.trim()
                is List<*> -> locationFromList(pathCandidate)
                is Map<*, *> -> locationFromMap(pathCandidate)
                else -> null
            }
        if (!resolvedPath.isNullOrBlank()) {
            return resolvedPath
        }
        val nameCandidate = map["name"] ?: map["value"] ?: map["title"]
        val resolvedName = valueAsString(nameCandidate)
        if (!resolvedName.isNullOrBlank()) {
            return resolvedName
        }
        return null
    }

    private fun locationFromList(list: List<*>): String? {
        val parts =
            list.mapNotNull { entry ->
                when (entry) {
                    is Map<*, *> -> locationFromMap(entry) ?: valueAsString(entry)
                    else -> valueAsString(entry)
                }
            }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    private fun valueAsPhoto(value: Any?): String? {
        return valueAsString(value)
    }

    private fun valueAsBoolean(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> {
                when (value.trim().lowercase()) {
                    "true",
                    "yes",
                    "1",
                    "on",
                    "checked",
                    -> true
                    "false",
                    "no",
                    "0",
                    "off",
                    "unchecked",
                    -> false
                    else -> null
                }
            }

            else -> null
        }
    }

    private fun mapError(error: Throwable): String {
        return when (error) {
            is HttpException -> {
                when (error.code()) {
                    401,
                    403,
                    -> "Unauthorized. Check the Memento token."
                    else -> "Memento API error (${error.code()})."
                }
            }

            is SocketTimeoutException -> "Network timeout while contacting Memento."
            is IOException -> "Network error while contacting Memento."
            is MementoSchemaException -> error.message ?: "Library schema missing required fields."
            is MementoResponseException -> error.message ?: "Unexpected response format from Memento."
            is MementoPagingException -> error.message ?: "Unable to page through Memento entries."
            else -> error.message ?: "Unexpected error."
        }
    }

    private companion object {
        const val TAG = "MementoRepository"
        const val DEBUG_LOCATION_NAME = "DGX Spark"
        const val PAGE_SIZE = 250
        const val BATCH_SIZE = 250
    }

    private enum class EntryStatus {
        SAVED,
        IGNORED,
    }

    private data class ParseOutcome(
        val item: InventoryItem?,
        val reason: String?,
    )

    private fun resolveEntryKey(
        entryId: String,
        rawEpc: String?,
    ): String? {
        val trimmedId = entryId.trim()
        if (trimmedId.isNotEmpty()) {
            return trimmedId
        }
        val trimmedEpc = rawEpc?.trim().orEmpty()
        return trimmedEpc.ifBlank { null }
    }

    private fun redactTokenInUrl(url: String?): String? {
        if (url.isNullOrBlank()) {
            return url
        }
        val tokenRegex = Regex("([?&]token=)[^&]+")
        return url.replace(tokenRegex, "$1***")
    }

    private fun logLocationDebug(
        entryId: String,
        epcNormalized: String,
        name: String?,
        locationFieldId: String?,
        rawLocation: Any?,
        locationPath: String?,
        fieldKeys: Set<String>,
    ) {
        if (!BuildConfig.DEBUG || !shouldLogLocationDebug(name)) {
            return
        }
        logger.d(
            TAG,
            "Location debug entryId=$entryId epc=$epcNormalized " +
                "locationFieldId=${locationFieldId ?: "--"} fields=$fieldKeys " +
                "rawLocation=$rawLocation mappedLocation=${locationPath ?: "--"}",
        )
    }

    private fun shouldLogLocationDebug(name: String?): Boolean {
        return name?.equals(DEBUG_LOCATION_NAME, ignoreCase = true) == true
    }
}
