package com.zerolab.checkin.ui.list

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.engine.CheckinEngine
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.engine.Method
import com.zerolab.checkin.theme.ThemeManager
import com.zerolab.checkin.ui.create.CreateItemActivity
import com.zerolab.checkin.ui.detail.ItemDetailActivity
import kotlin.concurrent.thread

class ItemListFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private val repo get() = (requireActivity().application as CheckinApp).repository
    private val items = mutableListOf<CheckinItem>()
    private lateinit var adapter: CardAdapter

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View {
        return inflater.inflate(R.layout.fragment_item_list, c, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.recycler)
        emptyView = view.findViewById(R.id.empty_view)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = CardAdapter()
        recycler.adapter = adapter
        view.findViewById<ImageButton>(R.id.btn_add).setOnClickListener {
            startActivity(Intent(requireContext(), CreateItemActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    fun reload() {
        if (!isAdded || !::recycler.isInitialized) return
        thread {
            val list = repo.getItems()
            val quickId = repo.getQuickId()
            val enriched = list.map { Triple(it, repo.availableCredits(it.id), CheckinEngine.streak(it, repo)) }
            activity?.runOnUiThread {
                items.clear(); items.addAll(list)
                emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                adapter.submit(enriched, quickId)
            }
        }
    }

    private data class Row(val item: CheckinItem, val credits: Int, val streak: Int)

    private fun methodLabel(item: CheckinItem): String {
        val c = ItemConfig.parse(item.configJson)
        val labels = c.methods.mapNotNull { Method.of(it)?.label?.removeSuffix("打卡") }
        return labels.joinToString("+").ifBlank { "普通" }
    }

    inner class CardAdapter : RecyclerView.Adapter<CardAdapter.VH>() {
        private val rows = mutableListOf<Row>()
        private var quickId: Long? = null

        fun submit(data: List<Triple<CheckinItem, Int, Int>>, qid: Long?) {
            rows.clear(); rows.addAll(data.map { Row(it.first, it.second, it.third) })
            quickId = qid; notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iconBg: View = v.findViewById(R.id.icon_bg)
            val emoji: TextView = v.findViewById(R.id.tv_emoji)
            val name: TextView = v.findViewById(R.id.tv_name)
            val type: TextView = v.findViewById(R.id.tv_type)
            val streak: TextView = v.findViewById(R.id.tv_streak)
            val credits: TextView = v.findViewById(R.id.tv_credits)
            val star: ImageView = v.findViewById(R.id.iv_star)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_checkin_card, parent, false)
            return VH(v)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val row = rows[position]; val item = row.item
            val theme = ThemeManager.of(item.theme)
            h.iconBg.background?.setTint(theme.soft)
            h.emoji.text = theme.emoji
            h.name.text = if (item.isActive == 0) "${item.name}（已暂停）" else item.name
            h.type.text = methodLabel(item)
            h.streak.text = "🔥 ${row.streak}天"
            if (row.credits > 0) { h.credits.visibility = View.VISIBLE; h.credits.text = "🛡️×${row.credits}" }
            else h.credits.visibility = View.GONE
            h.star.visibility = if (quickId == item.id) View.VISIBLE else View.GONE
            h.itemView.alpha = if (item.isActive == 0) 0.5f else 1f

            h.itemView.setOnClickListener {
                // 点卡片进入该打卡项的打卡页（不修改快捷配置；快捷仅通过长按菜单手动切换）
                startActivity(Intent(requireContext(), com.zerolab.checkin.ui.detail.ItemCheckinActivity::class.java)
                    .putExtra(com.zerolab.checkin.ui.detail.ItemCheckinActivity.EXTRA_ID, item.id))
            }
            h.itemView.setOnLongClickListener { showMenu(item); true }
        }
    }

    private fun showMenu(item: CheckinItem) {
        val quickId = repo.getQuickId()
        val isQuick = quickId == item.id
        val options = mutableListOf<String>()
        options += if (isQuick) "⭐ 取消快捷打卡" else "⭐ 设为快捷打卡"
        options += if (item.isPinned == 1) "📌 取消置顶" else "📌 置顶"
        options += if (item.isActive == 0) "▶️ 恢复" else "⏸️ 暂停"
        options += "✏️ 编辑"
        options += "🗑️ 删除"
        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setItems(options.toTypedArray()) { _, which ->
                thread {
                    when (which) {
                        0 -> {
                            repo.setQuick(if (isQuick) null else item.id)
                            // 写入完成后立即通知快捷页刷新，避免切回时读到旧值（状态栏残留）
                            activity?.runOnUiThread {
                                (activity as? com.zerolab.checkin.ui.main.MainActivity)?.refreshQuick()
                            }
                        }
                        1 -> repo.setPinned(item.id, item.isPinned != 1)
                        2 -> repo.setActive(item.id, item.isActive == 0)
                        3 -> {
                            val i = Intent(requireContext(), CreateItemActivity::class.java)
                            i.putExtra(CreateItemActivity.EXTRA_ID, item.id); startActivity(i)
                        }
                        4 -> activity?.runOnUiThread { confirmDelete(item) }
                    }
                    reload()
                }
            }.show()
    }

    private fun confirmDelete(item: CheckinItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除打卡项")
            .setMessage("确定删除「${item.name}」吗？此操作不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                thread { repo.deleteItem(item.id); reload() }
            }.show()
    }
}
