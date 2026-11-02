package com.taskra.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*

@Stable
class ImagePasteController(private val clipboard: ClipboardManager) {
    var available by mutableStateOf(false)
    var receive: (List<Uri>, () -> Unit) -> Boolean = { _, _ -> false }
    fun refresh() { available = clipboard.primaryClipDescription?.hasMimeType("image/*") == true }
    fun paste(): Boolean {
        refresh()
        if (!available) return false
        val clip = clipboard.primaryClip ?: return false
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return uris.isNotEmpty() && receive(uris, {})
    }
}

@Composable
fun rememberImagePaste(receive: (List<Uri>, () -> Unit) -> Boolean): ImagePasteController {
    val context = LocalContext.current
    val controller = remember { ImagePasteController(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager) }
    controller.receive = receive
    DisposableEffect(controller) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val listener = ClipboardManager.OnPrimaryClipChangedListener { controller.refresh() }
        clipboard.addPrimaryClipChangedListener(listener)
        controller.refresh()
        onDispose { clipboard.removePrimaryClipChangedListener(listener) }
    }
    return controller
}

fun Modifier.imagePasteKeys(controller: ImagePasteController) = onPreviewKeyEvent {
    it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.V && controller.paste()
}

/** 原生粘贴菜单、键盘粘贴和 IME 图片提交共用导入入口，不改变富文本输入模型。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImagePasteHost(controller: ImagePasteController, content: @Composable () -> Unit) {
    val original = LocalTextToolbar.current
    val toolbar = remember(original, controller) {
        object : TextToolbar {
            override val status: TextToolbarStatus get() = original.status
            override fun hide() = original.hide()
            override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
                controller.refresh()
                original.showMenu(rect, onCopyRequested,
                    if (controller.available) ({ controller.paste(); original.hide() }) else onPasteRequested,
                    onCutRequested, onSelectAllRequested)
            }
        }
    }
    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        InterceptPlatformTextInput(interceptor = { request, next ->
            next.startInputMethod(object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    outAttributes.contentMimeTypes = arrayOf("image/*")
                    return object : InputConnectionWrapper(connection, false) {
                        override fun performContextMenuAction(id: Int): Boolean {
                            if (id == android.R.id.paste && controller.paste()) return true
                            return super.performContextMenuAction(id)
                        }
                        override fun commitContent(info: InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
                            if (!info.description.hasMimeType("image/*")) return super.commitContent(info, flags, opts)
                            val grant = flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
                            return try {
                                if (grant) info.requestPermission()
                                val accepted = controller.receive(listOf(info.contentUri)) { if (grant) runCatching { info.releasePermission() } }
                                if (!accepted && grant) info.releasePermission()
                                accepted
                            } catch (_: Exception) { if (grant) runCatching { info.releasePermission() }; false }
                        }
                    }
                }
            })
        }, content = content)
    }
}
