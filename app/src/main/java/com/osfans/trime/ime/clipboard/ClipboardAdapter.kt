/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.util.ImePopupMenu
import splitties.resources.styledColor
import kotlin.math.min

abstract class ClipboardAdapter(
    private val scope: ThemeScope,
) : PagingDataAdapter<DatabaseBean, ClipboardAdapter.ViewHolder>(diffCallback) {
    companion object {
        private val diffCallback =
            object : DiffUtil.ItemCallback<DatabaseBean>() {
                override fun areItemsTheSame(
                    oldItem: DatabaseBean,
                    newItem: DatabaseBean,
                ): Boolean = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: DatabaseBean,
                    newItem: DatabaseBean,
                ): Boolean = oldItem == newItem
            }

        private fun excerptText(
            str: String,
            lines: Int = 4,
            chars: Int = 128,
        ): String = buildString {
            val length = str.length
            var lineBreak = -1
            for (i in 1..lines) {
                val start = lineBreak + 1 // skip previous '\n'
                val excerptEnd = min(start + chars, length)
                lineBreak = str.indexOf('\n', start)
                if (lineBreak < 0) {
                    // no line breaks remaining, substring to end of text
                    append(str.substring(start, excerptEnd))
                    break
                } else {
                    val end = min(excerptEnd, lineBreak)
                    // append one line exactly
                    appendLine(str.substring(start, end))
                }
            }
        }
    }

    private var popupMenu: ImePopupMenu? = null

    class ViewHolder(
        val ui: ClipboardBeanUi,
    ) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(ClipboardBeanUi(parent.context, scope))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val bean = getItem(position) ?: return
        with(holder.ui) {
            refreshColors()
            setBean(excerptText(bean.text ?: ""), bean.pinned)
            root.setOnClickListener {
                onPaste(bean)
            }
            root.setOnLongClickListener { anchor ->
                val iconTint = ctx.styledColor(android.R.attr.colorControlNormal)
                val menu =
                    ImePopupMenu(anchor)
                        .item(R.string.edit, R.drawable.ic_baseline_edit_24, iconTint) {
                            onEdit(bean.id)
                        }.item(R.string.share, R.drawable.ic_baseline_share_24, iconTint) {
                            onShare(bean)
                        }.item(R.string.word_segment, R.drawable.ic_baseline_view_comfy_24, iconTint) {
                            onSegment(bean)
                        }.apply {
                            if (enableCollection) {
                                item(R.string.collect, R.drawable.ic_baseline_star_24, iconTint) {
                                    onCollect(bean)
                                }
                                if (bean.pinned) {
                                    item(R.string.simple_key_unpin, R.drawable.ic_outline_push_pin_24, iconTint) {
                                        onUnpin(bean.id)
                                    }
                                } else {
                                    item(R.string.simple_key_pin, R.drawable.ic_baseline_push_pin_24, iconTint) {
                                        onPin(bean.id)
                                    }
                                }
                            }
                            item(R.string.delete, R.drawable.ic_baseline_delete_24, iconTint) {
                                onDelete(bean.id)
                            }
                            onDismiss = { if (this === popupMenu) popupMenu = null }
                        }
                popupMenu?.dismiss()
                popupMenu = menu
                menu.show()
                true
            }
        }
    }

    abstract fun onPaste(bean: DatabaseBean)

    open fun onPin(id: Int) {}

    open fun onUnpin(id: Int) {}

    abstract fun onEdit(id: Int)

    abstract fun onShare(bean: DatabaseBean)

    abstract fun onSegment(bean: DatabaseBean)

    open fun onCollect(bean: DatabaseBean) {}

    abstract fun onDelete(id: Int)

    abstract val enableCollection: Boolean

    fun dismissPopupMenu() {
        popupMenu?.dismiss()
        popupMenu = null
    }

    /** Re-colors the visible rows after a scheme switch; rows re-apply colors on bind. */
    fun refreshColors() {
        notifyDataSetChanged()
    }
}
