package com.example.podscribe

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

class ShareToolbar(private val view: View) : TextToolbar {
    private var mode: ActionMode? = null
    private var st = TextToolbarStatus.Hidden
    private var rect = Rect.Zero
    private var onCopy: (() -> Unit)? = null
    private var onSelectAll: (() -> Unit)? = null

    override val status: TextToolbarStatus get() = st

    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        this.rect = rect
        onCopy = onCopyRequested
        onSelectAll = onSelectAllRequested
        st = TextToolbarStatus.Shown
        if (mode == null) mode = view.startActionMode(Cb(), ActionMode.TYPE_FLOATING) else mode?.invalidate()
    }

    override fun hide() {
        st = TextToolbarStatus.Hidden
        mode?.finish()
        mode = null
    }

    private inner class Cb : ActionMode.Callback2() {
        override fun onCreateActionMode(m: ActionMode, menu: Menu): Boolean {
            menu.add(0, 1, 0, "Copy")
            menu.add(0, 2, 1, "Share")
            menu.add(0, 3, 2, "Select all")
            return true
        }

        override fun onPrepareActionMode(m: ActionMode, menu: Menu) = true

        override fun onActionItemClicked(m: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                1 -> onCopy?.invoke()
                2 -> {
                    onCopy?.invoke()
                    val cm = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    if (t.isNotBlank()) view.context.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                3 -> { onSelectAll?.invoke(); return true }
            }
            m.finish()
            return true
        }

        override fun onDestroyActionMode(m: ActionMode) {
            mode = null
            st = TextToolbarStatus.Hidden
        }

        override fun onGetContentRect(m: ActionMode, v: View, outRect: android.graphics.Rect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }
}
