package io.nimbly.tzatziki.util

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import io.nimbly.tzatziki.psi.*
import org.jetbrains.plugins.cucumber.CucumberElementFactory
import org.jetbrains.plugins.cucumber.psi.GherkinFileType
import org.jetbrains.plugins.cucumber.psi.GherkinTable
import org.jetbrains.plugins.cucumber.psi.GherkinTableCell
import org.jetbrains.plugins.cucumber.psi.GherkinTableRow

const val FEATURE_HEAD =
    "Feature: x\n" +
        "Scenario Outline: xx\n" +
        "Examples: xxx\n"

fun Editor.where(table: GherkinTable) : Int {

    val offset = caretModel.offset
    val origineColumn = document.getColumnAt(offset)

    val firstCell = table.firstRow.firstCell ?: return 0

    val tableColumnStart = document.getColumnAt(firstCell.startOffset)
    val tableColumnEnd = document.getColumnAt(table.firstRow.lastCell!!.endOffset)
    return when {
        origineColumn<tableColumnStart -> -1 // Left
        origineColumn>tableColumnEnd -> 1    // Right
        else -> 0
    }
}

fun Editor.addNewColum(c: Char, project: Project, fileType: FileType): Boolean {

    if (c != '|') return false
    if (fileType != GherkinFileType.INSTANCE) return false
    val offset = this.caretModel.offset
    val table = this.findTableAt(offset) ?: return false
    var currentCol = table.columnNumberAt(offset)
    val currentRow = table.rowNumberAt(offset) ?: return false

    // Where I am ? In table ? At its left ? At its right ?
    var where = this.where(table)

    // adjust left of right of current column
    if (where == 0 && currentCol != null) {
        val startOfCell = this.cellAt(offset)?.previousPipe?.startOffset
        if (startOfCell != null) {
            val margin = offset - startOfCell
            if (margin in 1..2) {
                currentCol -= 1
                if (currentCol < 0)
                    where = -1
            }
        }
    }

    // Build new table as string
    val s = StringBuilder()
    table.allRows.forEachIndexed { y, row ->
        if (where < 0)
            s.append("|   ")
        row.psiCells.forEachIndexed { x, cell ->
            s.append('|').append(cell.text)
            if (x == currentCol)
                s.append("|   ")
        }
        if (where > 0)
            s.append("|   ")

        s.append('|').append('\n')
    }

    // Commit document
    PsiDocumentManager.getInstance(project).let {
        it.commitDocument(this.document)
        it.doPostponedOperationsAndUnblockDocument(this.document)
    }

    // Apply modifications
    ApplicationManager.getApplication().runWriteAction {

        // replace table
        val tempTable = CucumberElementFactory
            .createTempPsiFile(project, FEATURE_HEAD + s)
            .children[0].children[0].children[0].children[0]

        val newTable = table.replace(tempTable) as GherkinTable
        val newRow = newTable.row(currentRow)

        // Find caret target
        val targetColumn =
            when {
                where < 0 -> 0
                where > 0 -> newRow.psiCells.size - 1
                else -> currentCol!! + 1
            }

        // Move caret
        this.caretModel.moveToOffset(newRow.cell(targetColumn).previousPipe.textOffset + 2)

        // Format table
        newTable.format()
    }

    return true
}

fun Editor.addTableRow(offset: Int = caretModel.offset): Boolean {

    val colIdx = getTableColumnIndexAt(offset) ?: return false
    val table = findTableAt(offset) ?: return false
    val row = getTableRowAt(offset) ?: return false

    val insert = offset == getLineEndOffset(offset)
    if (insert && row.isLastRow)
        return false

    if (offset < row.startOffset)
        return false

    ApplicationManager.getApplication().runWriteAction {

        val newRow = row.createRowAfter()

        var newCaret = newRow.textOffset + 1
        if (!insert)
            newCaret += colIdx * 2
        caretModel.moveToOffset(newCaret)

        CodeStyleManager.getInstance(project!!).reformatText(
            table.containingFile,
            table.textRange.startOffset, table.textRange.endOffset
        )

        caretModel.moveToOffset(caretModel.offset + 1)
    }

    return true
}

fun Editor.stopBeforeDeletion(cleanCells: Boolean, cleanHeader: Boolean): Boolean {
    if (!selectionModel.hasSelection(true))
        return false
    val table = findTableAt(selectionModel.selectionStart)
    if (table != null) {

        if (selectionModel.selectionStart >= table.allRows.last().endOffset)
            return false

        val text = selectionModel.getSelectedText(true)
        if (text != null && text.contains(Regex("[\\n|]"))) {
            if (!cleanCells && !cleanHeader)
                return true

            cleanSelection(this, table, cleanHeader, selectionModel.blockSelectionStarts, selectionModel.blockSelectionEnds)
            return true
        }
    }
    return false
}

fun Editor.stopBeforeDeletion(actionId: String, offset: Int = caretModel.offset): Boolean {

    if (selectionModel.hasSelection(true)) {
        val text = selectionModel.getSelectedText(true)
        if (text != null
            && text.isNotEmpty()
            && !text.startsWith("\n")
            && (!text.contains("|")
                || text.matches(Regex("^[ |\\n]+$"))))
            return false
    }

    if (stopBeforeDeletion(true, true)) {
        return true
    }

    val table = findTableAt(offset) ?:
        return false

    if (table.textLength == 1)
        return false // Table is a single pipe !!

    if (selectionModel.hasSelection(true)) {

        if (table.offsetIsOnAnyLine(selectionModel.selectionStart)
            && table.offsetIsOnAnyLine(selectionModel.selectionEnd))
            return true
    }
    else {
        val o = if (actionId == IdeActions.ACTION_EDITOR_DELETE) offset else offset - 1
        if (table.offsetIsOnAnyLine(o)) {

            val eof = document.charAt(offset) == '\n'
            val oo = if (eof) o+1 else o
            if (table.offsetIsOnLeft(oo))
                return document.getTextLine(oo).isNotBlank()

            val c = document.charAt(o)
            if (c != null && (c == '|' || c == '\n'))
                return true
        }
    }

    return false
}

private fun cleanSelection(editor: Editor, table: GherkinTable, cleanHeader: Boolean, starts: IntArray, ends: IntArray): Int {

    // Find cells to delete
    val toClean = mutableListOf<GherkinTableCell>()
    val toCleanRows = mutableSetOf<GherkinTableRow>()
    starts.indices.forEach { i ->
        val r = TextRange(starts[i], ends[i])
        table.findCellsInRange(r, cleanHeader)
            .forEach {
                toClean.add(it)
                toCleanRows.add(it.row)
            }
    }
    if (toClean.size < 1) return 0

    // Remember deleted column
    val blankSelection = editor.isSelectionOfBlankCells()
    val fullLine = toCleanRows.size == 1
    val targetColumn = toClean.first().columnNumber + (if (blankSelection) 1 else 0)
    val targetRow = toClean.first().row.rowNumber

    // Build temp string
    val sb = StringBuilder()
    table.allRows.forEach { row ->
        sb.append("| ")
        row.psiCells.forEach {
            sb.append(if (toClean.contains(it)) " " else it.text)
            sb.append(" |")
        }
        sb.append('\n')
    }

    // Replace table
    val coordinate = toClean[0].coordinate
    val tableSmart = SmartPointerManager.getInstance(editor.project).createSmartPsiElementPointer(table, editor.getFile()!!)
    ApplicationManager.getApplication().runWriteAction {

        // replace table
        val tempTable = CucumberElementFactory
            .createTempPsiFile(table.project, FEATURE_HEAD + sb.toString())
            .children[0].children[0].children[0].children[0]
        val newTable = table.replace(tempTable) as GherkinTable

        // Move cursor
        val targetCell = newTable.row(coordinate.second).cell(coordinate.first)
        editor.caretModel.removeSecondaryCarets()
        editor.caretModel.moveToOffset(targetCell.previousPipe.startOffset+2)
        editor.selectionModel.removeSelection()

        // Format table
        newTable.format()
    }

    // Select next column
    tableSmart.element?.let {
        if (it.columnCount > targetColumn) {
            if (fullLine)
                editor.selectTableRow(tableSmart.element!!, targetRow)
            else
                editor.selectTableColumn(tableSmart.element!!, targetColumn)
        }
    }

    return toClean.size
}

