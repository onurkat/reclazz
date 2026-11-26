/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.setup

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * The "Setup" tab of the Reclazz tool window: two or three step cards, one under the other. Finished steps fold to one
 * line with a check, the current step is open with its one main button, later steps are dimmed until it is done.
 *
 * Non-modal on purpose: the user can keep working while it is open. The state is recomputed from the project on every
 * refresh ([SetupFactsCollector]); while ant server runs, its output streams into the open card.
 */
internal class SetupPanel(private val project: Project, private val wizard: Boolean = false) : Disposable {

    private val content = JPanel(VerticalLayout(JBUI.scale(12))).apply { isOpaque = false }
    private val antLog = JBTextArea(10, 80).apply { isEditable = false; lineWrap = false }
    private var wizardConsole: JBScrollPane? = null
    /** The page: a centred column of at most [MAX_WIDTH], scrolling vertically and following the window's width. */
    private val page = object : JPanel(), javax.swing.Scrollable {
        init {
            isOpaque = false
            layout = object : java.awt.LayoutManager {
                override fun addLayoutComponent(name: String?, comp: java.awt.Component?) {}
                override fun removeLayoutComponent(comp: java.awt.Component?) {}
                override fun minimumLayoutSize(parent: java.awt.Container) = Dimension(0, 0)
                override fun preferredLayoutSize(parent: java.awt.Container): Dimension {
                    val w = columnWidth(parent.width.takeIf { it > 0 } ?: JBUI.scale(MAX_WIDTH))
                    content.setSize(w, content.height); content.doLayout()
                    val h = content.preferredSize.height
                    return Dimension(w + 2 * pad(), h + 2 * verticalPad(h))
                }
                override fun layoutContainer(parent: java.awt.Container) {
                    val w = columnWidth(parent.width)
                    val h = content.preferredSize.height
                    content.setBounds((parent.width - w) / 2, verticalPad(h), w, h)
                }
            }
            add(content)
        }
        private fun pad() = if (width in 1 until JBUI.scale(420)) JBUI.scale(10) else JBUI.scale(24)
        private fun verticalPad(contentHeight: Int): Int {
            val normal = pad()
            val viewport = parent as? javax.swing.JViewport ?: return normal
            if (!wizard || viewport.height <= 0) return normal
            return ((viewport.extentSize.height - contentHeight) / 2).coerceIn(JBUI.scale(8), normal)
        }
        private fun columnWidth(total: Int) = maxOf(minOf(total - 2 * pad(), JBUI.scale(MAX_WIDTH)), 1)
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = r.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
    private val pageScroll = JBScrollPane(page).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        viewport.background = UIUtil.getPanelBackground()
    }

    val component: JComponent get() = pageScroll

    @Volatile private var observed = SetupFactsCollector.Observed()
    private var facts: SetupFacts? = null
    private var evaluated: List<EvaluatedStep> = emptyList()
    private val refreshing = AtomicBoolean()
    /** A refresh asked for while one was collecting: run again once it lands, so no state change is lost. */
    private val rerun = AtomicBoolean()
    private var antRunner: AntServerRunner? = null
    private var busy: String? = null
    private var message: Pair<String, Boolean>? = null
    private val timer = Timer(3000) { if (component.isShowing && (observed.antRunning || busy != null)) refresh() }
    private val runner = SetupActionRunner(project)

    init {
        antLog.font = EditorColorsManager.getInstance().globalScheme.let { Font(it.editorFontName, Font.PLAIN, JBUI.scaleFontSize(12f)) }
        if (wizard) pageScroll.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(event: java.awt.event.ComponentEvent) = sizeWizardConsole()
        })
        timer.start()
        refresh()
    }

    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) { rerun.set(true); return }
        val snapshot = observed
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try { SetupFactsCollector.collect(project, snapshot) } catch (_: Throwable) { null }
            ApplicationManager.getApplication().invokeLater({
                refreshing.set(false)
                if (result != null && !project.isDisposed && snapshot == observed) show(result)
                onFirstRefresh?.also { onFirstRefresh = null }?.invoke()
                if (rerun.getAndSet(false) && !project.isDisposed) refresh()
            }, ModalityState.any())
        }
    }

    private fun show(result: SetupFacts) {
        facts = result
        evaluated = SetupFlow.evaluate(result)
        render()
    }

    private var onFirstRefresh: (() -> Unit)? = null

    fun prepareDialog(open: () -> Unit) {
        pageIndex = 0
        if (facts == null) {
            onFirstRefresh = open
            refresh()
        } else {
            render()
            open()
        }
    }

    /** Measure the widest column at the width the dialog can actually give it. */
    fun preferredPageSize(availableWidth: Int): Dimension {
        val width = minOf(availableWidth, JBUI.scale(MAX_WIDTH + 48))
        val f = facts ?: return Dimension(width, JBUI.scale(480))
        val sample = JPanel(VerticalLayout(JBUI.scale(12)))
        var height = 0
        for ((index, step) in evaluated.withIndex()) {
            sample.removeAll()
            sample.add(header(f))
            sample.add(JBLabel("Step"))
            sample.add(StepCard(index + 1, step, true, f, measuring = true))
            sample.setSize((width - JBUI.scale(48)).coerceAtLeast(1), Short.MAX_VALUE.toInt())
            sample.doLayout()
            height = maxOf(height, sample.preferredSize.height)
        }
        return Dimension(width, height + JBUI.scale(48))
    }

    private var pageIndex = 0
    var onNavigation: ((Boolean, String?, Boolean) -> Unit)? = null

    fun move(delta: Int) {
        if (evaluated.isEmpty()) return
        pageIndex = (pageIndex + delta).coerceIn(0..evaluated.size)
        render()
    }

    fun skipOptional() {
        if (evaluated.getOrNull(pageIndex)?.step?.id == "assistant") perform(SetupAction.SkipAssistant)
    }

    private fun render() {
        val f = facts ?: return
        if (evaluated.isEmpty()) return
        wizardConsole = null
        content.removeAll()
        content.add(header(f))
        val current = SetupFlow.current(evaluated)
        val complete = SetupFlow.complete(evaluated)
        if (wizard) {
            val count = if (f.sapCommerce) evaluated.size else evaluated.size + 1
            if (pageIndex >= evaluated.size && !complete) pageIndex = evaluated.indexOf(current)
            val step = evaluated.getOrNull(pageIndex)
            if (step != null || !f.sapCommerce) content.add(JBLabel("Step ${pageIndex + 1} of $count"))
            if (step != null) content.add(StepCard(pageIndex + 1, step, true, f))
            else {
                content.add(JBLabel("All set", AllIcons.General.InspectionsOK, SwingConstants.LEFT).apply { font = JBFont.h3().asBold() })
                content.add(finished(f))
            }
            if (evaluated.first().status.finished) {
                com.onurkat.reclazz.plugin.settings.ReclazzSettings.getInstance(project).state.dismissedSetupGeneration = null
            }
            val forward = when {
                step == null && complete -> "Finish"
                step?.status?.finished != true -> null
                step.status is StepStatus.Skipped -> "Keep skipped"
                else -> "Next"
            }
            onNavigation?.invoke(pageIndex > 0, forward, step?.step?.optional == true && !step.status.finished)
        } else evaluated.forEachIndexed { index, step ->
            val open = !complete && step === current
            content.add(StepCard(index + 1, step, open, f))
        }
        if (!wizard && complete) content.add(finished(f))
        content.revalidate(); content.repaint()
        component.revalidate(); component.repaint()
    }

    /** Reserve the body for instructions and retry actions; longer output scrolls within the log. */
    private fun sizeWizardConsole() {
        val console = wizardConsole ?: return
        val available = pageScroll.viewport.extentSize.height
        if (available <= 0) return
        val cap = JBUI.scale(160)
        val twoLines = 2 * antLog.getFontMetrics(antLog.font).height + antLog.insets.top + antLog.insets.bottom
        val height = (available / 6).coerceIn(minOf(twoLines, cap), cap)
        val current = console.preferredSize
        if (current.height != height) console.preferredSize = Dimension(current.width, height)
    }

    // ---- header ----

    private fun header(f: SetupFacts): JComponent = JPanel(VerticalLayout(JBUI.scale(4))).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(8)
        add(JBLabel("Set up Reclazz").apply { font = JBFont.h1().asBold() })
        val done = evaluated.count { it.status.finished }
        val kind = if (f.sapCommerce) "SAP Commerce project" else "Java project"
        add(JBLabel(if (wizard) kind else "$kind  ·  $done of ${evaluated.size} done").apply { foreground = Colors.MUTED })
        if (!wizard) add(Progress(done, evaluated.size).apply { border = JBUI.Borders.emptyTop(8) })
    }

    private fun finished(f: SetupFacts): JComponent = Banner(Tone.SUCCESS, AllIcons.General.InspectionsOK,
        if (f.sapCommerce) "Setup is complete. Restart your SAP Commerce server, change a class, build it, and the change is live."
        else "Setup is complete. Run or debug your application, change a class, build it, and the change is live.")

    // ---- one step ----

    private inner class StepCard(private val number: Int, private val step: EvaluatedStep, private val open: Boolean, private val f: SetupFacts, private val measuring: Boolean = false) :
        JPanel(BorderLayout(JBUI.scale(14), 0)) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(if (open) 18 else 12, 18)
            add(Marker(number, step, open), BorderLayout.WEST)
            val body = JPanel(VerticalLayout(JBUI.scale(10))).apply { isOpaque = false }
            body.add(titleRow())
            if (open) openBody(body) else closedLine()?.let(body::add)
            add(body, BorderLayout.CENTER)
        }

        private fun titleRow(): JComponent = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(step.step.title).apply {
                font = if (open) JBFont.h3().asBold() else JBFont.regular().asBold()
                foreground = if (!step.reachable && !step.status.finished) Colors.MUTED else UIUtil.getLabelForeground()
            }, BorderLayout.CENTER)
            chip()?.let { add(it, BorderLayout.EAST) }
        }

        private fun chip(): JComponent? = when {
            step.status is StepStatus.Done -> Chip("Done", Tone.SUCCESS)
            step.status is StepStatus.Skipped -> Chip("Skipped", Tone.NEUTRAL)
            step.step.optional -> Chip("Optional", Tone.NEUTRAL)
            else -> null
        }

        private fun closedLine(): JComponent? = when (val s = step.status) {
            is StepStatus.Done -> text(s.summary, Colors.MUTED)
            is StepStatus.Skipped -> text(s.why, Colors.MUTED)
            else -> null
        }

        private fun openBody(body: JPanel) {
            body.add(text(step.step.explanation, Colors.MUTED))
            when (val s = step.status) {
                is StepStatus.NeedsAction -> {
                    if (step.step.id == "assistant") assistants(body, s.actions) else {
                        if (s.problem != "Not installed yet." && s.problem != "Not run since the install.")
                            body.add(if (step.step.id == "ant" && f.antFailure != null)
                                Banner(Tone.ERROR, AllIcons.General.Error, s.problem)
                            else Banner(Tone.WARNING, AllIcons.General.Warning, s.problem))
                        actions(body, s.actions)
                    }
                }
                is StepStatus.Waiting -> {
                    body.add(Banner(Tone.INFO, AnimatedIcon.Default(), s.what))
                    actions(body, s.actions)
                }
                is StepStatus.Done -> {
                    body.add(Banner(Tone.SUCCESS, AllIcons.General.InspectionsOK, s.summary))
                    if (s.actions.isNotEmpty()) assistants(body, s.actions)
                }
                is StepStatus.Skipped -> {
                    body.add(Banner(Tone.NEUTRAL, AllIcons.General.InspectionsOK, s.why))
                    assistants(body, s.actions)
                }
            }
            if (step.step.id == "ant" && (measuring || antLog.text.isNotEmpty())) {
                body.add(code("Ant output", if (measuring) JBTextArea() else antLog, JBUI.scale(160)))
            }
            busy?.let { body.add(Banner(Tone.INFO, AnimatedIcon.Default(), it)) }
            message?.let { (text, ok) -> body.add(Banner(if (ok) Tone.SUCCESS else Tone.ERROR,
                if (ok) AllIcons.General.InspectionsOK else AllIcons.General.Error, text)) }
        }

        private fun actions(body: JPanel, list: List<SetupAction>) {
            val preview = list.firstNotNullOfOrNull { a ->
                when (a) {
                    is SetupAction.Install, is SetupAction.Update -> f.installPreview
                    else -> null
                }
            }
            if (preview != null && preview.startsWith("Install would stop")) {
                // The installer refuses to touch a hand-made setup; say why instead of offering a button that would fail.
                body.add(Banner(Tone.WARNING, AllIcons.General.Warning, preview.removePrefix("Install would stop without changing anything: ")))
                body.add(ActionLink("Check again") { message = null; refresh() })
                return
            }
            if (preview != null) {
                val lines = preview.lines()
                val title = lines.first().removeSuffix(":")
                body.add(code(title, JBTextArea(lines.drop(1).joinToString("\n")).apply { isEditable = false; lineWrap = true }, null))
            }
            val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply { isOpaque = false; border = JBUI.Borders.emptyTop(2) }
            list.filter { it != SetupAction.CheckAgain }.forEachIndexed { i, a -> row.add(button(a, primary = i == 0 && a != SetupAction.CancelAntServer)) }
            if (SetupAction.CheckAgain in list) row.add(ActionLink("Check again") { message = null; refresh() })
            body.add(row)
        }

        private fun assistants(body: JPanel, list: List<SetupAction>) {
            // Two tiles per row when there is room, one per row in a narrow tool window.
            val grid = JPanel(java.awt.GridLayout(0, if (content.width in 1 until JBUI.scale(520)) 1 else 2, JBUI.scale(10), JBUI.scale(10))).apply { isOpaque = false }
            list.filterIsInstance<SetupAction.ConnectAssistant>().forEach { a ->
                val fact = f.assistants.first { it.client == a.client }
                grid.add(AssistantTile(a, fact.detected))
            }
            body.add(grid)
            if (!wizard && step.status is StepStatus.NeedsAction)
                body.add(ActionLink("Skip this step") { perform(SetupAction.SkipAssistant) })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(12).toFloat()
                val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
                g2.color = if (open) Colors.CARD else Colors.CARD_QUIET
                g2.fill(shape)
                g2.color = if (open) Colors.CARD_BORDER_OPEN else Colors.CARD_BORDER
                g2.stroke = BasicStroke(if (open) JBUI.scale(1.5f) else 1f)
                g2.draw(shape)
            } finally { g2.dispose() }
        }
    }

    private inner class AssistantTile(action: SetupAction.ConnectAssistant, detected: Boolean) : JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(6))) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(12, 14)
            val name = action.client.display.substringBefore(" (")
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JBLabel(name).apply { font = JBFont.regular().asBold() }, BorderLayout.CENTER)
                if (detected) add(Chip("Found on this computer", Tone.SUCCESS), BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(text("Adds Reclazz to ${action.client.projectFile}", Colors.MUTED), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(button(action, primary = detected)) }, BorderLayout.SOUTH)
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(10).toFloat()
                val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
                g2.color = Colors.CARD_QUIET; g2.fill(shape)
                g2.color = Colors.CARD_BORDER; g2.draw(shape)
            } finally { g2.dispose() }
        }
    }

    private fun button(action: SetupAction, primary: Boolean): JButton = JButton(if (action == SetupAction.RunAntServer && observed.antFailure != null) "Retry" else action.label).apply {
        if (primary) putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        isEnabled = busy == null
        addActionListener { perform(action) }
    }

    /** A code block: an optional file name above, editor font, editor background, wrapped or scrolled. */
    private fun code(title: String?, area: JBTextArea, maxHeight: Int?): JComponent {
        area.isOpaque = false
        area.font = EditorColorsManager.getInstance().globalScheme.let { Font(it.editorFontName, Font.PLAIN, JBUI.scaleFontSize(12f)) }
        area.border = JBUI.Borders.empty(10, 12)
        area.wrapStyleWord = false
        val panel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = JBUI.scale(8).toFloat()
                    val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
                    g2.color = Colors.CODE; g2.fill(shape)
                    g2.color = Colors.CARD_BORDER; g2.draw(shape)
                } finally { g2.dispose() }
            }
        }.apply { isOpaque = false }
        if (title != null) panel.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(Colors.CARD_BORDER), JBUI.Borders.empty(7, 12))
            add(JBLabel(title, AllIcons.FileTypes.Properties, SwingConstants.LEFT).apply { foreground = Colors.MUTED; font = JBFont.small() }, BorderLayout.CENTER)
            add(ActionLink(if (area === antLog) "Copy output" else "Copy") { CopyPasteManager.getInstance().setContents(StringSelection(area.text)) }.apply { font = JBFont.small() }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        if (maxHeight == null) panel.add(area, BorderLayout.CENTER)
        else panel.add(JBScrollPane(area).apply {
            isOpaque = false; viewport.isOpaque = false; border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(400), maxHeight)
            if (wizard && area === antLog) {
                wizardConsole = this
                sizeWizardConsole()
            }
        }, BorderLayout.CENTER)
        return panel
    }

    // ---- actions ----

    private fun perform(action: SetupAction) {
        message = null
        when (action) {
            SetupAction.RunAntServer -> runAnt()
            SetupAction.CancelAntServer -> antRunner?.cancel()
            SetupAction.CheckAgain -> {}
            else -> if (runner.handles(action)) {
                if (runner.writesFiles(action)) background(when (action) {
                    is SetupAction.ConnectAssistant -> "Connecting ${action.client.display.substringBefore(" (")}..."
                    else -> "Installing..."
                }) { runner.run(action) ?: ("Done." to true) }
                else runner.run(action)?.let { message = it }
            }
        }
        refresh()
    }

    private fun background(title: String, work: () -> Pair<String, Boolean>) {
        busy = title; render()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try { work() } catch (failure: Throwable) { (failure.message ?: failure.javaClass.simpleName) to false }
            ApplicationManager.getApplication().invokeLater({ busy = null; message = result; refresh() }, ModalityState.any())
        }
    }

    private fun runAnt() {
        if (observed.antRunning) return
        val home = HybrisProjectDetector.findHybrisHome(project) ?: return
        val ant = AntServerRunner(home)
        antRunner = ant
        antLog.text = ""
        observed = observed.copy(antRunning = true, antFailure = null, antRan = false)
        // Show "running" with Stop at once, so a second click cannot start another ant.
        facts?.let { show(it.copy(antRunning = true, antFailure = null)) }
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            fun finish(failure: String?) = app.invokeLater({
                if (!project.isDisposed && antRunner === ant) {
                    antRunner = null
                    observed = observed.copy(antRunning = false, antFailure = failure, antRan = failure == null)
                    refresh()
                }
            }, ModalityState.any())
            try {
                ant.start(onText = { text -> app.invokeLater({
                    if (!project.isDisposed && antRunner === ant) {
                        antLog.append(text)
                        antLog.caretPosition = antLog.document.length
                    }
                }, ModalityState.any()) }, onExit = ::finish)
            } catch (failure: Exception) {
                finish(failure.message ?: "Could not start ant server.")
            }
        }
    }

    internal fun observedForTests() = observed

    /** Collects and renders synchronously on the calling (event) thread; for screenshots in tests. */
    internal fun refreshNowForTests() = show(SetupFactsCollector.collect(project, observed))

    /** Renders given facts without touching the project; for screenshots of every state in tests. */
    internal fun showForTests(f: SetupFacts, antOutput: String = "", note: Pair<String, Boolean>? = null) {
        antLog.text = antOutput; message = note; show(f)
    }

    override fun dispose() {
        timer.stop()
        antRunner?.cancel()
        antRunner = null
    }

    companion object {
        const val MAX_WIDTH = 720
    }
}

// ---- small visual pieces ----

private enum class Tone { SUCCESS, WARNING, ERROR, INFO, NEUTRAL }

private object Colors {
    val CARD: Color = JBColor.namedColor("Reclazz.Setup.card", JBColor(0xFFFFFF, 0x2B2D30))
    val CARD_QUIET: Color = JBColor.namedColor("Reclazz.Setup.cardQuiet", JBColor(0xF7F8FA, 0x27282C))
    val CARD_BORDER: Color = JBColor.namedColor("Reclazz.Setup.border", JBColor(0xE1E3E8, 0x393B40))
    val CARD_BORDER_OPEN: Color = JBColor.namedColor("Reclazz.Setup.borderOpen", JBColor(0x3574F0, 0x548AF7))
    val ACCENT: Color = JBColor.namedColor("Reclazz.Setup.accent", JBColor(0x3574F0, 0x548AF7))
    val SUCCESS: Color = JBColor.namedColor("Reclazz.Setup.success", JBColor(0x208A3C, 0x5FAD65))
    val MUTED: Color get() = UIUtil.getContextHelpForeground()
    val CODE: Color = JBColor.namedColor("Reclazz.Setup.code", JBColor(0xF5F6F8, 0x1E1F22))
    val TRACK: Color = JBColor.namedColor("Reclazz.Setup.track", JBColor(0xE6E8EC, 0x393B40))
}

private class Chip(text: String, private val tone: Tone) : JBLabel(text) {
    init {
        font = JBFont.small().asBold()
        border = JBUI.Borders.empty(2, 8)
        foreground = when (tone) { Tone.SUCCESS -> Colors.SUCCESS; else -> UIUtil.getContextHelpForeground() }
        verticalAlignment = SwingConstants.CENTER
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = when (tone) {
                Tone.SUCCESS -> JBUI.CurrentTheme.Banner.SUCCESS_BACKGROUND
                else -> Colors.TRACK
            }
            g2.fillRoundRect(0, 0, width, height, height, height)
        } finally { g2.dispose() }
        super.paintComponent(g)
    }
}

/** A rounded, tinted message with an icon: success, warning, error or info. Text wraps. */
private class Banner(private val tone: Tone, icon: Icon, text: String) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(9, 12)
        add(JBLabel(icon).apply { verticalAlignment = SwingConstants.TOP; border = JBUI.Borders.emptyTop(1) }, BorderLayout.WEST)
        add(text(text, JBUI.CurrentTheme.Banner.FOREGROUND), BorderLayout.CENTER)
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val (fill, line) = when (tone) {
                Tone.SUCCESS -> JBUI.CurrentTheme.Banner.SUCCESS_BACKGROUND to JBUI.CurrentTheme.Banner.SUCCESS_BORDER_COLOR
                Tone.WARNING -> JBUI.CurrentTheme.Banner.WARNING_BACKGROUND to JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR
                Tone.ERROR -> JBUI.CurrentTheme.Banner.ERROR_BACKGROUND to JBUI.CurrentTheme.Banner.ERROR_BORDER_COLOR
                else -> JBUI.CurrentTheme.Banner.INFO_BACKGROUND to JBUI.CurrentTheme.Banner.INFO_BORDER_COLOR
            }
            val arc = JBUI.scale(8).toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
            g2.color = fill; g2.fill(shape); g2.color = line; g2.draw(shape)
        } finally { g2.dispose() }
    }
}

/** The step's circle: a check when done, its number otherwise; filled for the open step. */
private class Marker(private val number: Int, private val step: EvaluatedStep, private val open: Boolean) : JComponent() {
    private val size = JBUI.scale(26)
    override fun getPreferredSize() = Dimension(size, size)
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val d = size - 2
            when {
                step.status is StepStatus.Done -> {
                    g2.color = Colors.SUCCESS; g2.fillOval(1, 1, d, d)
                    g2.color = Color.WHITE; g2.stroke = BasicStroke(JBUI.scale(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    val s = size / 26f
                    g2.draw(Path2D.Float().apply { moveTo(8 * s, 13.5f * s); lineTo(11.5f * s, 17 * s); lineTo(18 * s, 9.5f * s) })
                }
                step.status is StepStatus.Skipped -> {
                    g2.color = Colors.CARD_BORDER; g2.stroke = BasicStroke(JBUI.scale(1.5f)); g2.drawOval(1, 1, d, d)
                    g2.color = UIUtil.getContextHelpForeground(); g2.drawLine(size / 2 - JBUI.scale(4), size / 2, size / 2 + JBUI.scale(4), size / 2)
                }
                open -> { g2.color = Colors.ACCENT; g2.fillOval(1, 1, d, d); number(g2, Color.WHITE) }
                else -> {
                    g2.color = Colors.CARD_BORDER; g2.stroke = BasicStroke(JBUI.scale(1.5f)); g2.drawOval(1, 1, d, d)
                    number(g2, UIUtil.getContextHelpForeground())
                }
            }
        } finally { g2.dispose() }
    }
    private fun number(g2: Graphics2D, color: Color) {
        g2.color = color
        g2.font = JBFont.regular().asBold()
        val fm = g2.fontMetrics
        val text = number.toString()
        g2.drawString(text, (size - fm.stringWidth(text)) / 2f, (size - fm.height) / 2f + fm.ascent)
    }
}

/** A thin rounded progress track. */
private class Progress(private val done: Int, private val total: Int) : JComponent() {
    override fun getPreferredSize() = Dimension(JBUI.scale(200), JBUI.scale(6) + insets.top + insets.bottom)
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = JBUI.scale(6); val y = insets.top; val w = width
            g2.color = Colors.TRACK; g2.fillRoundRect(0, y, w, h, h, h)
            if (total > 0 && done > 0) { g2.color = if (done == total) Colors.SUCCESS else Colors.ACCENT; g2.fillRoundRect(0, y, w * done / total, h, h, h) }
        } finally { g2.dispose() }
    }
}

/** Wrapping text; `code` spans in backticks are shown in the editor font. */
private fun text(value: String, color: Color): JComponent {
    val html = StringUtil.escapeXmlEntities(value).replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }.replace("\n", "<br>")
    return javax.swing.JEditorPane().apply {
        editorKit = com.intellij.util.ui.HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        isEditable = false; isOpaque = false; isFocusable = false; border = JBUI.Borders.empty()
        font = JBFont.regular(); foreground = color
        text = "<html><body>$html</body></html>"
    }
}
