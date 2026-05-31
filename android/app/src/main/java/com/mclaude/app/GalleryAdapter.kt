package com.mclaude.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.io.File

/** 로컬 media/ 파일을 그리드로 표시하는 어댑터 */
class GalleryAdapter(
    private val ctx: Context,
    private val items: MutableList<Item>,
    private val onDelete: (Item) -> Unit,
    private val onOpen: (Item) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val badge: TextView = v.findViewById(R.id.badge)
        val del: TextView = v.findViewById(R.id.del)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        val f = File(Storage.mediaDir(ctx), item.file)
        Glide.with(ctx)
            .load(f)
            .centerCrop()
            .into(holder.img)

        // style/video 는 배지 표시
        when (item.type) {
            "style" -> { holder.badge.text = "STYLE"; holder.badge.visibility = View.VISIBLE }
            "video" -> { holder.badge.text = "▶ VIDEO"; holder.badge.visibility = View.VISIBLE }
            else -> holder.badge.visibility = View.GONE
        }

        holder.img.setOnClickListener { onOpen(item) }
        holder.del.setOnClickListener { onDelete(item) }
    }

    /** 항목 1개 제거 후 화면 갱신 */
    fun removeItem(item: Item) {
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    /** 전체 목록 교체 */
    fun replaceAll(newItems: List<Item>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
