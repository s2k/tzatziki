/*
 * TRANSLATION +
 * Copyright (C) 2024  Maxime HAMM - NIMBLY CONSULTING - Maxime.HAMM@nimbly-consulting.com
 *
 * This document is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This work is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package io.nimbly.i18n.translation

import com.intellij.codeInsight.daemon.impl.HintRenderer
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vcs.VcsDataKeys
import icons.ActionI18nIcons
import io.nimbly.i18n.util.*

@Suppress("MissingActionUpdateThread")
open class TranslateAction : DumbAwareAction()  {

    override fun update(event: AnActionEvent) {
        doUpdate(event, null)
    }

    fun doUpdate(event: AnActionEvent, editorRef: Editor?) {

        val editor = editorRef ?: event.editor
        event.presentation.isEnabledAndVisible = editor!=null

        val output = PropertiesComponent.getInstance().getValue(SAVE_OUTPUT, "EN")

        event.presentation.icon =  TranslationIcons.getFlag(output.trim().lowercase())
            ?: ActionI18nIcons.I18N

        var readyToApplyTranslation = false
        editor?.let { ed ->
            readyToApplyTranslation = ed.inlayModel.getBlockElementsInRange(0, ed.document.textLength - 1)
                .filter { (it.renderer as? EditorHint)?.type == EHint.TRANSLATION }
                .isNotEmpty()
        }
        if (readyToApplyTranslation) {
            event.presentation.text = "Apply Translation"
        }
        else {
            event.presentation.text = "Translate"
        }

        super.update(event)
    }

    override fun actionPerformed(event: AnActionEvent) {
        doActionPerformed(event, null)
    }

    fun doActionPerformed(event: AnActionEvent, editorRef: Editor?) {

        val editor = editorRef ?: event.editor ?: return

        val isVCS = VcsDataKeys.COMMIT_MESSAGE_DOCUMENT.getData(event.dataContext) != null

        val inlayModel = editor.inlayModel

        val editorImpl = event.getData(CommonDataKeys.EDITOR) as? EditorImpl
        val zoom = editorImpl?.fontSize?.let { it / 13.0 } ?: 1.0
        val file = event.getData(CommonDataKeys.PSI_FILE)
        val caret = CommonDataKeys.CARET.getData(event.dataContext)?.offset

        val project = CommonDataKeys.PROJECT.getData(event.dataContext) ?: editor.project ?: file?.project ?: return
        var startOffset: Int
        var endOffset: Int
        var text: String?
        val format: EFormat
        val style: EStyle
        val selectionEnd: Boolean
        if (editor.selectionModel.hasSelection()) {

            val offsetFirstNotEmpty = editor.selectionModel.findOffsetFirstNotNull()
            if (offsetFirstNotEmpty != editor.selectionModel.selectionStart) {
                editor.selectionModel.setSelection(offsetFirstNotEmpty, editor.selectionModel.selectionEnd)
            }

            startOffset = editor.selectionModel.selectionStart
            endOffset = editor.selectionModel.selectionEnd
            text = editor.selectionModel.getSelectedText(false)
            format = editor.detectFormat()
            style = text?.removeQuotes()?.detectStyle() ?: EStyle.NORMAL

            selectionEnd = caret == endOffset
        }
        else if (isVCS) {

            startOffset = 0
            endOffset = editor.document.textLength
            text = editor.document.text
            format = EFormat.TEXT
            style = EStyle.NORMAL

            selectionEnd = caret == endOffset
        }
        else if (file != null && caret != null) {

            val l = file.findElementAt(caret) ?: return
            startOffset = l.textRange.startOffset
            endOffset = l.textRange.endOffset

            // Do not select multiple line
            val lineStart = editor.document.getLineNumber(startOffset)
            val lineEnd = editor.document.getLineNumber(endOffset)
            if (lineStart != lineEnd) {

                val ln = editor.document.getLineNumber(caret)
                startOffset = editor.document.getLineStartOffset(ln) + editor.document.getLineTextStartOffset(caret)
                endOffset = editor.document.getLineEndOffset(ln)

                text = editor.document.getText(TextRange(startOffset, endOffset))
            }
            else {
                text = l.text ?: ""
            }

            if (caret == startOffset
                    && (text.isBlank() || text.replace("[\\p{L}\\p{N}\\p{M}]+".toRegex(), "").isNotBlank() && caret > 1)) {

                val ll = file.findElementAt(caret - 1) ?: return
                startOffset = ll.textRange.startOffset
                endOffset = ll.textRange.endOffset
                text = ll.text

                if (text.isBlank()) {
                    text = null
                }
            }

            format = file.detectFormat()
            style = text?.removeQuotes()?.detectStyle() ?: EStyle.NORMAL

            selectionEnd = caret == endOffset

            editor.selectionModel.setSelection(startOffset, endOffset)
        }
        else {
            return
        }

        if (text == null)
            return

        val activeInlays = inlayModel.getBlockElementsInRange(startOffset, startOffset)
            .filter { (it.renderer as? EditorHint)?.type == EHint.TRANSLATION }

        if (activeInlays.isNotEmpty()) {

            //
            // Apply inlay's translation
            //
            val joinToString = activeInlays
                .map { it.renderer as EditorHint }
                .map { it.translation }
                .reversed()
                .map { it.trim().escapeFormat(format) }
                .joinToString("\n")

            val document = editor.document
            if (document.isWritable) {

                val t = document.getText(TextRange(startOffset, endOffset))
                val indented = joinToString.indentAs(t)

                executeWriteCommand(project, "Translating with Translation+") {
                    document.replaceString(startOffset, endOffset, indented)
                }
                EditorFactory.getInstance().clearInlays()

                if (selectionEnd)
                    editor.selectionModel.removeSelection()
            }

            return
        }
        else {

            //
            // Translate and show inlay
            //
            val input = PropertiesComponent.getInstance().getValue(SAVE_INPUT, Lang.AUTO.code)
            val output = PropertiesComponent.getInstance().getValue(SAVE_OUTPUT, Lang.DEFAULT.code)

            val translation = TranslationManager.translate(output, input, text, format, style, project)
                ?: return

            EditorFactory.getInstance().clearInlays()

            val translationLines = translation.translated
                .split('\n')
                .reversed()
                .toMutableList()

            if (translationLines.size > 1 && translationLines.first().isBlank()) {
                translationLines[1] = translationLines[1] + '\n' + translationLines[0]
                translationLines.removeAt(0)
            }

            val xindent = editor.offsetToPoint2D(startOffset).x.toInt()

            translationLines
                .forEachIndexed { index, translationLine ->

                    val renderer = EditorHint(
                        type = EHint.TRANSLATION,
                        zoom = zoom,
                        translation = translationLine,
                        flag = if (index == translationLines.size - 1) output.trim().lowercase() else null,
                        indent = if (index == translationLines.size - 1) xindent else 4
                    )
                    val p = InlayProperties().apply {
                        showAbove(true)
                        relatesToPrecedingText(false)
                        priority(1000)
                        disableSoftWrapping(false)
                    }

                    inlayModel.addBlockElement<HintRenderer>(startOffset, p, renderer)
                }

            val renderer = EditorHint(
                type = EHint.TRANSLATION,
                zoom = zoom,
                flag = translation.sourceLanguageIndentified.trim().lowercase(),
                translation = "  "
            )
            val p = InlayProperties().apply {
                showAbove(false)
                relatesToPrecedingText(false)
                priority(1000)
                disableSoftWrapping(false)
            }
            inlayModel.addInlineElement<HintRenderer>(startOffset, p, renderer)
        }
    }

    override fun isDumbAware()
        = true
}