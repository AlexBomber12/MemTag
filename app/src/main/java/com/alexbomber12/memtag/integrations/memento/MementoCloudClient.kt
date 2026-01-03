package com.alexbomber12.memtag.integrations.memento

import com.alexbomber12.memtag.core.logging.Logger
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.lang.IllegalStateException

class MementoCloudClient(
    private val logger: Logger,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
    moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
) : MementoClient {
    private val mapAdapter =
        moshi.adapter<Map<String, Any?>>(
            Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                Any::class.java,
            ),
        )

    override suspend fun fetchLibrarySchema(config: MementoConfig): MementoLibrarySchema {
        val api = createApi(config.baseUrl)
        val body =
            api.getLibrary(config.libraryId, config.token).use { response ->
                response.string()
            }
        val response = parseJsonMap(body, "library schema")
        return parseLibrarySchema(response)
    }

    override suspend fun fetchEntriesPage(
        config: MementoConfig,
        request: MementoEntriesRequest,
    ): MementoEntriesPage {
        val api = createApi(config.baseUrl)
        val body =
            if (request.nextUrl != null) {
                val resolvedUrl = resolveNextUrl(config, request.nextUrl)
                api.getEntriesByUrl(resolvedUrl).use { response -> response.string() }
            } else {
                api.getEntries(
                    libraryId = config.libraryId,
                    token = config.token,
                    fields = "all",
                    pageSize = request.pageSize,
                    pageToken = request.pageToken,
                    pageIndex = request.pageIndex,
                ).use { response -> response.string() }
            }
        val response = parseJsonMap(body, "entries page")
        return parseEntriesPage(response)
    }

    private fun parseJsonMap(
        body: String,
        label: String,
    ): Map<String, Any?> {
        return try {
            mapAdapter.fromJson(body)
                ?: throw MementoResponseException("Unable to parse $label response.")
        } catch (error: Exception) {
            throw MementoResponseException("Unable to parse $label response.")
        }
    }

    private fun parseLibrarySchema(response: Map<String, Any?>): MementoLibrarySchema {
        val containers = buildContainers(response)
        val fieldKeys = listOf("fields", "fieldSchemas", "fieldSchema", "schema", "columns")
        val fields =
            containers
                .firstNotNullOfOrNull { container ->
                    fieldKeys.firstNotNullOfOrNull { key ->
                        extractFields(container[key])
                    }
                }
                .orEmpty()
        if (fields.isEmpty()) {
            throw MementoResponseException("Library schema did not include field metadata.")
        }
        return MementoLibrarySchema(fields = fields)
    }

    private fun parseEntriesPage(response: Map<String, Any?>): MementoEntriesPage {
        val containers = buildContainers(response)
        val entryKeys = listOf("entries", "items", "data", "results")
        val entryMapsOrNull =
            containers.firstNotNullOfOrNull { container ->
                entryKeys.firstNotNullOfOrNull { key ->
                    extractEntryMaps(container[key])
                }
            }
        if (entryMapsOrNull == null) {
            throw MementoResponseException("Entries response did not include an entries list.")
        }
        val entryMaps = entryMapsOrNull
        val entries = entryMaps.map { parseEntry(it) }
        val nextPageToken = valueAsString(response["nextPageToken"] ?: response["next_page_token"])
        val nextUrl =
            valueAsString(response["next"])
                ?: valueAsString((response["links"] as? Map<*, *>)?.get("next"))
                ?: valueAsString((response["_links"] as? Map<*, *>)?.get("next"))
        val page =
            valueAsInt(response["page"])
                ?: containers.firstNotNullOfOrNull { valueAsInt(it["page"]) }
        val pageCount =
            valueAsInt(response["pageCount"] ?: response["page_count"] ?: response["pageTotal"])
                ?: containers.firstNotNullOfOrNull {
                    valueAsInt(it["pageCount"] ?: it["page_count"] ?: it["pageTotal"])
                }
        return MementoEntriesPage(
            entries = entries,
            nextPageToken = nextPageToken,
            nextUrl = nextUrl,
            page = page,
            pageCount = pageCount,
        )
    }

    private fun parseEntry(entry: Map<String, Any?>): MementoEntry {
        val entryId =
            valueAsString(
                entry["id"]
                    ?: entry["entryId"]
                    ?: entry["entry_id"]
                    ?: entry["uuid"]
                    ?: entry["key"],
            )
        val fieldValues = extractFieldValues(entry)
        val updatedAt =
            valueAsLong(
                entry["updatedAt"]
                    ?: entry["updated_at"]
                    ?: entry["modifiedAt"]
                    ?: entry["modified_at"]
                    ?: entry["lastUpdated"]
                    ?: entry["last_updated"],
            )
        val status =
            valueAsString(
                entry["status"]
                    ?: entry["entryStatus"]
                    ?: entry["entry_status"]
                    ?: entry["state"],
            )
        return MementoEntry(
            entryId = entryId,
            fieldValues = fieldValues,
            updatedAt = updatedAt,
            status = status,
        )
    }

    private fun extractFieldValues(entry: Map<String, Any?>): Map<String, Any?> {
        val candidates = listOf("fields", "fieldValues", "values", "data")
        candidates.forEach { key ->
            val value = entry[key]
            extractFieldValueMap(value)?.let { return it }
        }
        return emptyMap()
    }

    private fun extractFieldValueMap(value: Any?): Map<String, Any?>? {
        return when (value) {
            is Map<*, *> -> toStringKeyMap(value)
            is List<*> -> listToFieldMap(value)
            else -> null
        }
    }

    private fun listToFieldMap(list: List<*>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        list.forEach { item ->
            val map = item as? Map<*, *> ?: return@forEach
            val fieldId =
                valueAsString(
                    map["fieldId"]
                        ?: map["field_id"]
                        ?: map["id"],
                )
            if (!fieldId.isNullOrBlank()) {
                result[fieldId] = toStringKeyMap(map)
            }
        }
        return result
    }

    private fun extractEntryMaps(value: Any?): List<Map<String, Any?>>? {
        return when (value) {
            is List<*> ->
                value.mapNotNull { item ->
                    (item as? Map<*, *>)?.let { toStringKeyMap(it) }
                }
            else -> null
        }
    }

    private fun extractFields(value: Any?): List<MementoField>? {
        return when (value) {
            is List<*> ->
                value.mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    val id =
                        valueAsString(
                            map["id"]
                                ?: map["fieldId"]
                                ?: map["field_id"],
                        )
                    val name =
                        valueAsString(
                            map["name"]
                                ?: map["label"]
                                ?: map["fieldName"],
                        )
                    if (!id.isNullOrBlank() && !name.isNullOrBlank()) {
                        MementoField(id = id, name = name)
                    } else {
                        null
                    }
                }
            else -> null
        }
    }

    private fun buildContainers(response: Map<String, Any?>): List<Map<String, Any?>> {
        val containers = mutableListOf(response)
        listOf("library", "data", "result").forEach { key ->
            val nested = response[key] as? Map<*, *>
            if (nested != null) {
                containers.add(toStringKeyMap(nested))
            }
        }
        return containers
    }

    private fun toStringKeyMap(map: Map<*, *>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        map.forEach { (key, value) ->
            val stringKey = key?.toString() ?: return@forEach
            result[stringKey] = value
        }
        return result
    }

    private fun valueAsString(value: Any?): String? {
        return when (value) {
            is String -> value
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun valueAsInt(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun valueAsLong(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun resolveNextUrl(
        config: MementoConfig,
        nextUrl: String,
    ): String {
        val baseUrl = ensureTrailingSlash(config.baseUrl)
        val base =
            baseUrl.toHttpUrlOrNull()
                ?: throw IllegalStateException("Invalid base URL.")
        val resolved =
            nextUrl.toHttpUrlOrNull()
                ?: base.resolve(nextUrl)
                ?: base.newBuilder().addPathSegments(nextUrl.trimStart('/')).build()
        val builder = resolved.newBuilder()
        if (resolved.queryParameter("token") == null) {
            builder.addQueryParameter("token", config.token)
        }
        val url = builder.build().toString()
        return url
    }

    private fun createApi(baseUrl: String): MementoApi {
        val normalized = ensureTrailingSlash(baseUrl)
        val retrofit =
            Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient)
                .build()
        return retrofit.create(MementoApi::class.java)
    }

    private fun ensureTrailingSlash(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private companion object {
        const val TAG = "MementoCloudClient"
    }
}
