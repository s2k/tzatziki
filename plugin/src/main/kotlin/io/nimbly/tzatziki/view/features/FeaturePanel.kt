package io.nimbly.tzatziki.view.features

import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.dnd.aware.DnDAwareTree
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import icons.CollaborationToolsIcons
import io.nimbly.tzatziki.services.Tag
import io.nimbly.tzatziki.services.TagComparator
import io.nimbly.tzatziki.services.TzPersistenceStateService
import io.nimbly.tzatziki.services.TzTagService
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

// See com.intellij.ide.bookmark.ui.BookmarksView
class FeaturePanel(val project: Project) : SimpleToolWindowPanel(true), Disposable {

    val structure: GherkinTreeTagStructure
    val model: StructureTreeModel<GherkinTreeTagStructure>

    init {

        val state = ServiceManager.getService(project, TzPersistenceStateService::class.java)
        val grouping = state.groupTag == true

        structure = GherkinTreeTagStructure(this).apply { groupByTags = grouping }
        model = StructureTreeModel(structure, this)

        val tree = DnDAwareTree(AsyncTreeModel(model, this))

        layout = BorderLayout()

        add(JBScrollPane(tree))
        TreeSpeedSearch(tree)

        val toolbarGroup = DefaultActionGroup()
        val treeExpander = DefaultTreeExpander(tree)
        toolbarGroup.add(CommonActionsManager.getInstance().createExpandAllAction(treeExpander, this))
        toolbarGroup.add(CommonActionsManager.getInstance().createCollapseAllAction(treeExpander, this))
        toolbarGroup.add(GroupByTagAction(this))

        val toolbar = ActionManager.getInstance().createActionToolbar("CucumberPlusFeatureTree", toolbarGroup, false)
        toolbar.targetComponent = tree

        setToolbar(toolbar.component)

        tree.addMouseListener(MouseListening(tree, project))

        forceRefresh()
    }

    private fun forceRefresh() {
        DumbService.getInstance(project).smartInvokeLater {
            PsiDocumentManager.getInstance(project).performWhenAllCommitted {

                // First tag list initialization
                val tagService = project.getService(TzTagService::class.java)
                val tags = tagService.getTags()
                refreshTags(tags)
            }
        }
    }

    override fun dispose() {
        //NA
    }

    fun groupByTag(grouping: Boolean) {

        val state = ServiceManager.getService(project, TzPersistenceStateService::class.java)
        state.groupTag = grouping

        this.structure.groupByTags = grouping

        forceRefresh()
    }

    fun refreshTags(tags: SortedMap<String, Tag>) {
        if (structure.groupByTags) {
            val stags = tags
                .map { it.key to it.value.gFiles.toList() }
                .toMap()
                .toSortedMap(TagComparator)
            structure.tags = stags
        }
        model.invalidateAsync()
    }

    class GroupByTagAction(val panel: FeaturePanel) : ToggleAction() {
        init {
            this.templatePresentation.text = "Group by tags"
            this.templatePresentation.icon = CollaborationToolsIcons.Review.Branch
        }
        override fun isSelected(e: AnActionEvent): Boolean {
            val state = ServiceManager.getService(panel.project, TzPersistenceStateService::class.java)
            return state.groupTag == true
        }
        override fun setSelected(e: AnActionEvent, state: Boolean) {

            panel.groupByTag(state)

//            DumbService.getInstance(panel.project).smartInvokeLater {
//                PsiDocumentManager.getInstance(panel.project).performLaterWhenAllCommitted() {
//                    panel.groupByTag(state)
//                }
//            }
        }
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }
}

class MouseListening(val tree: DnDAwareTree, private val project: Project) : MouseAdapter() {

    override fun mouseClicked(e: MouseEvent) {

        if (e.clickCount == 2) {
            val elt = tree.lastSelectedPathComponent
            if (elt is DefaultMutableTreeNode) {
                val psiElt = (elt.userObject as? AbstractTreeNode<*>)?.value as? PsiElement
                if (psiElt != null) {

                    val fileEditorManager = FileEditorManager.getInstance(project)
                    val virtualFile = psiElt.containingFile.virtualFile
                    fileEditorManager.openFile(virtualFile, true, fileEditorManager.isFileOpen(virtualFile))
                    fileEditorManager.setSelectedEditor(virtualFile, "text-editor")

                    (psiElt as? Navigatable)?.navigate(true)
                }
            }
        }
    }
}


