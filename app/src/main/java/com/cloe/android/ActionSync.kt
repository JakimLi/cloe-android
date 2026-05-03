package com.cloe.android

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Full sync of all action-set GIFs from Cloe Desktop HTTP bridge (port 19851).
 * Runtime display uses the set indicated by WebSocket `set-config.setId` (paths from cached dir for that id).
 */
object ActionSync {

    private const val TAG = "ActionSync"
    const val HTTP_PORT = 19851
    private const val META_VERSION = 2

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun base(host: String) = "http://${host.trim()}:$HTTP_PORT"

    fun remoteRoot(context: Context): File = File(context.filesDir, "cloe_remote_actions")

    fun metaFile(context: Context): File = File(remoteRoot(context), "meta.json")

    data class SetCacheEntry(val gifDir: File, val actionNames: List<String>)

    data class FullCacheMeta(
        val version: Int,
        /** PC active set at end of last full sync (initial display folder). */
        val activeSetIdAtSync: String,
        val sets: Map<String, SetCacheEntry>,
        val initialIdlePlaylist: List<String>,
    ) {
        fun hasSet(setId: String): Boolean = sets.containsKey(setId)
    }

    private fun sanitizeDirSegment(setId: String): String =
        setId.replace(Regex("[^a-zA-Z0-9_.-]"), "_").ifBlank { "set" }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8.name())

    /**
     * Read cache meta (v2 full sync, or legacy v1 single-set).
     */
    fun readFullMeta(context: Context): FullCacheMeta? {
        val mf = metaFile(context)
        if (!mf.isFile) return null
        return try {
            val jo = JSONObject(mf.readText())
            if (jo.optInt("version", 1) >= 2 && jo.has("sets")) {
                val setsJo = jo.getJSONObject("sets")
                val sets = linkedMapOf<String, SetCacheEntry>()
                val kit = setsJo.keys()
                while (kit.hasNext()) {
                    val key = kit.next()
                    val so = setsJo.getJSONObject(key)
                    val dir = File(so.getString("gifDirPath"))
                    val namesJa = so.getJSONArray("actionNames")
                    val names = buildList {
                        for (i in 0 until namesJa.length()) add(namesJa.getString(i))
                    }
                    sets[key] = SetCacheEntry(dir, names)
                }
                val idleJa = jo.optJSONArray("initialIdlePlaylist") ?: JSONArray()
                val idle = buildList {
                    for (i in 0 until idleJa.length()) add(idleJa.getString(i))
                }
                FullCacheMeta(
                    jo.optInt("version", META_VERSION),
                    jo.optString("activeSetIdAtSync", sets.keys.firstOrNull() ?: "default"),
                    sets,
                    idle,
                )
            } else {
                @Suppress("DEPRECATION")
                legacyV1ToFull(jo)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readFullMeta failed", e)
            null
        }
    }

    private fun legacyV1ToFull(jo: JSONObject): FullCacheMeta {
        val setId = jo.getString("setId")
        val dir = File(jo.getString("gifDirPath"))
        val namesJa = jo.getJSONArray("actionNames")
        val names = buildList {
            for (i in 0 until namesJa.length()) add(namesJa.getString(i))
        }
        val idleJa = jo.optJSONArray("idlePlaylist") ?: JSONArray()
        val idle = buildList {
            for (i in 0 until idleJa.length()) add(idleJa.getString(i))
        }
        return FullCacheMeta(
            1,
            setId,
            mapOf(setId to SetCacheEntry(dir, names)),
            idle,
        )
    }

    /** action name → absolute path for one cached set directory */
    fun loadRemoteActionPathsForSet(context: Context, setId: String): Map<String, String> {
        val full = readFullMeta(context) ?: return emptyMap()
        val entry = full.sets[setId] ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (name in entry.actionNames) {
            val f = File(entry.gifDir, "$name.gif")
            if (f.isFile) out[name] = f.absolutePath
        }
        return out
    }

    /**
     * Full sync: every set returned by GET /action-sets; each action GIF under its own folder.
     * @return total GIF files written.
     */
    suspend fun pullFromDesktop(host: String, context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = base(host)
            client.newCall(Request.Builder().url("$baseUrl/status").get().build()).execute()
                .use { resp ->
                    if (!resp.isSuccessful) throw Exception("无法连接 PC 桥接 (${resp.code})，请确认 Cloe Desktop 已运行")
                }

            val setsBody = client.newCall(Request.Builder().url("$baseUrl/action-sets").get().build()).execute()
                .use { resp ->
                    if (!resp.isSuccessful) throw Exception("读取动作套装失败 (${resp.code})")
                    resp.body?.string() ?: throw Exception("空响应")
                }
            val rootJo = JSONObject(setsBody)
            val activeSetId = rootJo.getString("activeSetId")
            val setsJa = rootJo.getJSONArray("sets")

            val root = remoteRoot(context)
            root.mkdirs()

            val setsMetaJo = JSONObject()
            var totalSaved = 0
            var activeIdle: List<String> = emptyList()

            for (i in 0 until setsJa.length()) {
                val summary = setsJa.getJSONObject(i)
                val setId = summary.getString("id")

                val setUrl = "$baseUrl/action-sets/${enc(setId)}"
                val setBody = client.newCall(Request.Builder().url(setUrl).get().build()).execute()
                    .use { resp ->
                        if (!resp.isSuccessful) throw Exception("读取套装 $setId 失败 (${resp.code})")
                        resp.body?.string() ?: throw Exception("空响应")
                    }
                val setJo = JSONObject(setBody)
                val actionsJa = setJo.optJSONArray("actions") ?: JSONArray()
                val names = buildList {
                    for (j in 0 until actionsJa.length()) {
                        val o = actionsJa.getJSONObject(j)
                        add(o.getString("name"))
                    }
                }

                if (setId == activeSetId) {
                    val idleJa = setJo.optJSONArray("idlePlaylist") ?: JSONArray()
                    activeIdle = buildList {
                        for (j in 0 until idleJa.length()) add(idleJa.getString(j))
                    }
                }

                val gifDir = File(root, "gifs_${sanitizeDirSegment(setId)}")
                gifDir.mkdirs()

                if (names.isEmpty()) {
                    setsMetaJo.put(
                        setId,
                        JSONObject()
                            .put("gifDirPath", gifDir.absolutePath)
                            .put("actionNames", JSONArray()),
                    )
                    continue
                }

                for (name in names) {
                    val gifUrl = "$baseUrl/action-sets/${enc(setId)}/actions/${enc(name)}/gif"
                    client.newCall(Request.Builder().url(gifUrl).get().build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("套装 $setId 下载 $name 失败 (${resp.code})")
                        val bytes = resp.body?.bytes() ?: throw Exception("套装 $setId 下载 $name 无内容")
                        File(gifDir, "$name.gif").writeBytes(bytes)
                        totalSaved++
                    }
                }

                val entryJo = JSONObject()
                entryJo.put("gifDirPath", gifDir.absolutePath)
                entryJo.put("actionNames", JSONArray(names))
                setsMetaJo.put(setId, entryJo)
            }

            if (setsMetaJo.length() == 0) {
                throw Exception("未读取到任何套装")
            }
            if (totalSaved == 0) {
                throw Exception("所有套装的 animations 均为空，没有可下载的 GIF")
            }

            val metaJo = JSONObject()
            metaJo.put("version", META_VERSION)
            metaJo.put("syncedAt", System.currentTimeMillis())
            metaJo.put("activeSetIdAtSync", activeSetId)
            metaJo.put("sets", setsMetaJo)
            metaJo.put("initialIdlePlaylist", JSONArray(activeIdle))
            metaFile(context).writeText(metaJo.toString(2))

            totalSaved
        }
    }

    fun clearRemoteCache(context: Context) {
        try {
            remoteRoot(context).deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "clearRemoteCache", e)
        }
    }
}
