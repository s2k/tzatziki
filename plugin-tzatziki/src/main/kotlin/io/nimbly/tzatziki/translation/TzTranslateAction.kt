package io.nimbly.tzatziki.translation

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import io.nimbly.i18n.translation.TranslateAction

class TzTranslateAction : TranslateAction() {

    override fun update(event: AnActionEvent) {

        val actionManager = ActionManager.getInstance()
        //actionManager.getActionIds("io.nimbly.i18n")
        val taction = actionManager.getAction("io.nimbly.i18n.translation.TranslateAction")
        if (taction != null) {
            event.presentation.isVisible = false
            event.presentation.isEnabledAndVisible = false
            return
        }

        super.update(event)
    }
}