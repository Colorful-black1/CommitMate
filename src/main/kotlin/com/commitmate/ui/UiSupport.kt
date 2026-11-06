package com.commitmate.ui

import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/** 为对话框内容面板绑定 Esc 关闭，保证隐藏 Cancel 按钮后仍有键盘退出方式。 */
internal fun installEscapeToClose(component: JComponent, onEscape: () -> Unit) {
    val actionKey = "CommitMate.escape"
    component.actionMap.put(
        actionKey,
        object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                onEscape()
            }
        },
    )
    component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), actionKey)
}
