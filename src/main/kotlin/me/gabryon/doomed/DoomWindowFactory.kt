package me.gabryon.doomed

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JComponent

class DoomWindowContent(val content: JComponent = DoomPanel())

class DoomWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowContent = DoomWindowContent()
        val content = ContentFactory
            .getInstance()
            .createContent(toolWindowContent.content, "Doom 🔫", false)
        toolWindow.contentManager.addContent(content)
    }
}