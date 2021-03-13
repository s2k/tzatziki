package io.nimbly.tzatziki.util

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.refactoring.suggested.startOffset
import com.intellij.util.DocumentUtil
import org.jetbrains.plugins.cucumber.CucumberElementFactory
import org.jetbrains.plugins.cucumber.psi.GherkinTable
import org.jetbrains.plugins.cucumber.psi.GherkinTableCell
import org.jetbrains.plugins.cucumber.psi.GherkinTableRow
import org.jetbrains.plugins.cucumber.psi.GherkinTokenTypes

fun GherkinTable.format() {
    val range = textRange
    WriteCommandAction.runWriteCommandAction(project) {
        CodeStyleManager.getInstance(project).reformatText(
            containingFile,
            range.startOffset, range.endOffset
        )
    }
}

fun GherkinTableRow.addRowAfter(): GherkinTableRow {

    val cellCount = psiCells.size
    val header =
        "Feature: x\n" +
            "Scenario Outline: xx\n" +
            "Examples: xxx\n"

    var rowString = "|"
    for (int in 1..cellCount)
        rowString += " |"

    val tempTable = CucumberElementFactory
        .createTempPsiFile(project, header + rowString + '\n' + rowString)
        .children[0].children[0].children[0].children[0]

    val tempRow = tempTable.children[1]
    val returnn = tempRow.prevSibling

    val newRow = this.parent.addAfter(tempRow, this)
    this.parent.addAfter(returnn, this)

    return newRow as GherkinTableRow
}

fun PsiElement.previousPipe(): PsiElement {
    var el = prevSibling
    while (el != null) {
        if (el is LeafPsiElement && el.elementType == GherkinTokenTypes.PIPE) {
            return el
        }
        el = el.prevSibling
    }
    throw Exception("Psi structure corrupted !")
}

fun GherkinTableRow.next(): GherkinTableRow? {
    val table = PsiTreeUtil.getParentOfType(this, GherkinTable::class.java) ?: return null
    return table.nextRow(this)
}

fun GherkinTable.nextRow(row: GherkinTableRow): GherkinTableRow? {
    val allRows = allRows()
    val i = allRows.indexOf(row) + 1
    return if (i < allRows.size)
        allRows[i] else null
}

fun GherkinTableRow.previous(): GherkinTableRow? {
    val table = PsiTreeUtil.getParentOfType(this, GherkinTable::class.java) ?: return null
    return table.previousRow(this)
}

fun GherkinTable.previousRow(row: GherkinTableRow): GherkinTableRow? {
    val allRows = allRows()
    val i = allRows.indexOf(row) - 1
    return if (i >= 0)
        allRows[i] else null
}

fun GherkinTable.allRows(): List<GherkinTableRow> {
    if (headerRow == null)
        return dataRows

    val rows = mutableListOf<GherkinTableRow>()
    rows.add(headerRow!!)
    rows.addAll(dataRows)
    return rows
}

fun GherkinTable.cellAt(offset: Int): GherkinTableCell? = containingFile.cellAt(offset)

fun GherkinTableRow.cellAt(offset: Int): GherkinTableCell? = containingFile.cellAt(offset)

fun GherkinTableRow.columnNumberAt(offset: Int): Int? {
    val cell = cellAt(offset) ?: return null
    return psiCells.indexOf(cell)
}

fun GherkinTableRow.table(): GherkinTable = parent as GherkinTable

fun GherkinTableCell.row(): GherkinTableRow = parent as GherkinTableRow

fun GherkinTable.columnNumberAt(offset: Int): Int? {
    return cellAt(offset)?.row()?.columnNumberAt(offset)
}

fun GherkinTable.rowNumberAt(offset: Int): Int? {
    val row = rowAt(offset) ?: return null
    return allRows().indexOf(row)
}

fun GherkinTable.rowAt(offset: Int): GherkinTableRow? {
    val row = cellAt(offset)?.row()
    if (row != null)
        return row

    val el : PsiElement? = findElementAt(offset) ?: return null
    return PsiTreeUtil.getContextOfType(el, GherkinTableRow::class.java)
}

fun GherkinTableRow.cell(columnNumber: Int): GherkinTableCell = psiCells[columnNumber]

fun GherkinTable.row(rowNumber: Int): GherkinTableRow = allRows()[rowNumber]


