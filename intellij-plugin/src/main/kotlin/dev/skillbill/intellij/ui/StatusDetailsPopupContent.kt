package dev.skillbill.intellij.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.skillbill.intellij.presentation.GoalControlDescriptor
import dev.skillbill.intellij.presentation.GoalControlKind
import dev.skillbill.intellij.presentation.SkillBillStatusBarPresentation
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants

/**
 * Builds the status details popup panel from an already-mapped presentation.
 *
 * Extracted from the click handler so construction is exercisable without showing a
 * Swing popup: nothing here needs a running popup or a visible frame. Colours are
 * theme-derived only, and the panel decides nothing — eligibility and label text arrive
 * pre-resolved on [SkillBillStatusBarPresentation.MappedPresentation.controls].
 */
object StatusDetailsPopupContent {
    /** Label/value pairs, identical in content and order to the previous popup lines. */
    fun statusLines(presentation: SkillBillStatusBarPresentation.MappedPresentation): List<Pair<String, String>> {
        val details = presentation.details
        return buildList {
            add("State" to details.lifecycleState)
            details.issueKey?.let { add("Issue" to it) }
            details.workflowId?.let { add("Workflow" to it) }
            details.stepLabel?.let { add("Step" to it) }
            details.progressText?.let { add("Progress" to it) }
            add("Goal ${details.elapsedNoun}" to details.goalElapsedText)
            add("Subtask ${details.elapsedNoun}" to details.subtaskElapsedText)
            details.lastUpdateText?.let { add("Last update" to it) }
            details.problemSummary?.let { add("" to it) }
            details.staleNote?.let { add("" to it) }
        }
    }

    fun build(
        presentation: SkillBillStatusBarPresentation.MappedPresentation,
        onActivate: (GoalControlDescriptor) -> Unit,
    ): Built {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12)
            isOpaque = false
        }
        panel.add(statusBlock(presentation), BorderLayout.CENTER)

        if (presentation.controls.isEmpty()) {
            return Built(panel = panel, buttons = emptyMap(), separator = null, actionRow = null, messageLabel = null)
        }

        val separator = JSeparator(SwingConstants.HORIZONTAL).apply {
            border = JBUI.Borders.empty(8, 0, 4, 0)
            foreground = JBColor.border()
        }
        val messageLabel = JLabel("").apply {
            isVisible = false
            foreground = UIUtil.getErrorForeground()
            border = JBUI.Borders.emptyTop(4)
        }
        val buttons = presentation.controls.associate { descriptor ->
            descriptor.kind to JButton(descriptor.text).apply {
                isEnabled = descriptor.enabled
                // Disabled buttons stay focusable so the registered-request text is
                // reachable by keyboard and readable by a screen reader.
                isFocusable = true
                accessibleContext.accessibleName = descriptor.accessibleName
                addActionListener { onActivate(descriptor) }
            }
        }
        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            // Reading order follows the descriptor order, so focus traversal matches it.
            presentation.controls.forEach { descriptor -> buttons[descriptor.kind]?.let { add(it) } }
        }

        val footer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(separator)
            add(actionRow)
            add(messageLabel)
        }
        panel.add(footer, BorderLayout.SOUTH)
        return Built(
            panel = panel,
            buttons = buttons,
            separator = separator,
            actionRow = actionRow,
            messageLabel = messageLabel,
        )
    }

    private fun statusBlock(presentation: SkillBillStatusBarPresentation.MappedPresentation): JPanel {
        val block = JPanel(GridBagLayout()).apply { isOpaque = false }
        val title = JLabel("Skill Bill details").apply {
            font = font.deriveFont(java.awt.Font.BOLD)
            border = JBUI.Borders.emptyBottom(4)
        }
        block.add(
            title,
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                gridwidth = 2
                anchor = GridBagConstraints.LINE_START
            },
        )
        statusLines(presentation).forEachIndexed { index, (label, value) ->
            val row = index + 1
            if (label.isEmpty()) {
                block.add(
                    JLabel(value).apply { foreground = UIUtil.getContextHelpForeground() },
                    GridBagConstraints().apply {
                        gridx = 0
                        gridy = row
                        gridwidth = 2
                        anchor = GridBagConstraints.LINE_START
                        insets = Insets(1, 0, 1, 0)
                    },
                )
                return@forEachIndexed
            }
            block.add(
                JLabel("$label:").apply { foreground = UIUtil.getContextHelpForeground() },
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    anchor = GridBagConstraints.LINE_START
                    insets = Insets(1, 0, 1, JBUI.scale(8))
                },
            )
            block.add(
                JLabel(value),
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = row
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.LINE_START
                    insets = Insets(1, 0, 1, 0)
                },
            )
        }
        return block
    }

    /** The built panel plus the pieces tests and the click handler need to address. */
    class Built(
        val panel: JPanel,
        val buttons: Map<GoalControlKind, JButton>,
        val separator: Component?,
        val actionRow: Component?,
        private val messageLabel: JLabel?,
    ) {
        /** Renders a bounded failure summary inline; the next snapshot stays authoritative. */
        fun showMessage(summary: String) {
            messageLabel?.apply {
                text = summary
                isVisible = true
                revalidate()
                repaint()
            }
        }

        fun messageText(): String? = messageLabel?.takeIf { it.isVisible }?.text

        fun buttonFor(kind: GoalControlKind): JButton? = buttons[kind]
    }
}
