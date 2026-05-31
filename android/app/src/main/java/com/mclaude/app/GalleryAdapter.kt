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

/** 로컬 media/ 파일을 그리드로 표시하는 어댑터 (단일 보기 + 다중선택 삭제) */
class GalleryAdapter(
    private val ctx: Context,
    private val items: MutableList<Item>,
    private val onDelete: (Item) -> Unit,
    private val onOpen: (Item) -> Unit,
    private val onSelectionChanged: (Boolean, Int) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.VH>() {

    /** 다중선택 모드 on/off (읽기 전용 외부 노출) */
    var selectionMode = false
        private set

    private val selected = LinkedHashSet<String>()   // 선택된 item.id

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val badge: TextView = v.findViewById(R.id.badge)
        val del: TextView = v.findViewById(R.id.del)
        val check: TextView = v.findViewById(R.id.check)
        val selDim: View = v.findViewById(R.id.selDim)
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

        // 카테고리 배지
        when (item.type) {
            "style" -> { holder.badge.text = "STYLE"; holder.badge.visibility = View.VISIBLE }
            "video" -> { holder.badge.text = "▶ VIDEO"; holder.badge.visibility = View.VISIBLE }
            "capture" -> { holder.badge.text = "📸 캡쳐"; holder.badge.visibility = View.VISIBLE }
            else -> holder.badge.visibility = View.GONE
        }

        val isSel = selected.contains(item.id)
        if (selectionMode) {
            // 선택 모드: ✕(개별삭제) 숨기고 체크 표시
            holder.del.visibility = View.GONE
            holder.check.visibility = View.VISIBLE
            holder.check.text = if (isSel) "☑" else "☐"
            holder.selDim.visibility = if (isSel) View.VISIBLE else View.GONE
        } else {
            holder.del.visibility = View.VISIBLE
            holder.check.visibility = View.GONE
            holder.selDim.visibility = View.GONE
        }

        holder.img.setOnClickListener {
            if (selectionMode) toggle(item) else onOpen(item)
        }
        holder.img.setOnLongClickListener {
            if (!selectionMode) enterSelection(item) else toggle(item)
            true
        }
        holder.del.setOnClickListener { onDelete(item) }
    }

    // ---- 선택 모드 제어 -------------------------------------------------

    /** 길게 눌러 선택 모드 진입 (해당 항목을 선택 상태로) */
    fun enterSelection(initial: Item?) {
        if (selectionMode) {
            if (initial != null) toggle(initial)
            return
        }
        selectionMode = true
        selected.clear()
        if (initial != null) selected.add(initial.id)
        notifyDataSetChanged()
        onSelectionChanged(true, selected.size)
    }

    /** 선택 모드 종료 + 선택 해제 */
    fun exitSelection() {
        if (!selectionMode) return
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(false, 0)
    }

    /** 현재 보이는 모든 항목 선택 */
    fun selectAll() {
        if (!selectionMode) return
        selected.clear()
        items.forEach { selected.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged(true, selected.size)
    }

    /** 선택된 항목들 (현재 목록 기준) */
    fun selectedItems(): List<Item> = items.filter { selected.contains(it.id) }

    private fun toggle(item: Item) {
        if (selected.contains(item.id)) selected.remove(item.id) else selected.add(item.id)
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) notifyItemChanged(idx)
        onSelectionChanged(selectionMode, selected.size)
    }

    /** 항목 1개 제거 후 화면 갱신 */
    fun removeItem(item: Item) {
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            items.removeAt(idx)
            selected.remove(item.id)
            notifyItemRemoved(idx)
        }
    }

    /** 전체 목록 교체 — 사라진 항목의 선택은 정리 */
    fun replaceAll(newItems: List<Item>) {
        items.clear()
        items.addAll(newItems)
        if (selected.isNotEmpty()) {
            val present = newItems.map { it.id }.toHashSet()
            selected.retainAll(present)
        }
        notifyDataSetChanged()
    }
}
