package com.mclaude.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 로컬에 저장되는 한 항목 */
data class Item(
    val id: String,
    val jobId: String,
    val type: String,   // image / style / video
    val url: String,    // 원본 썸네일 URL (다운로드 소스)
    val link: String,   // midjourney job 링크
    val file: String    // media 폴더 내 로컬 파일명
)

/** metadata.json + media/ 파일을 폰 내부 저장소(filesDir)에 관리 */
object Storage {

    fun mediaDir(ctx: Context): File =
        File(ctx.filesDir, "media").apply { if (!exists()) mkdirs() }

    private fun metaFile(ctx: Context): File = File(ctx.filesDir, "metadata.json")

    fun load(ctx: Context): MutableList<Item> {
        val f = metaFile(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Item(
                        id = o.optString("id"),
                        jobId = o.optString("job_id"),
                        type = o.optString("type", "image"),
                        url = o.optString("url"),
                        link = o.optString("link"),
                        file = o.optString("file")
                    )
                )
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("job_id", it.jobId)
                    .put("type", it.type)
                    .put("url", it.url)
                    .put("link", it.link)
                    .put("file", it.file)
            )
        }
        metaFile(ctx).writeText(arr.toString())
    }

    /** 항목 1개 삭제: 로컬 파일 제거 후 남은 목록 반환 */
    fun delete(ctx: Context, items: MutableList<Item>, item: Item): MutableList<Item> {
        try {
            val f = File(mediaDir(ctx), item.file)
            if (f.exists()) f.delete()
        } catch (_: Exception) {
        }
        items.removeAll { it.id == item.id }
        save(ctx, items)
        return items
    }
}
