/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.onurkat.reclazz.plugin.PluginPresence
import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import com.onurkat.reclazz.plugin.agent.McpLauncherStaging
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*

/** Opened only by Settings. All filesystem work runs in the pool; UI-only completions may run in the nested Connect dialog. */
class ConnectAiAssistantPanel internal constructor(
    private val project: Project,
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val stage: (McpLauncherStaging.Staged) -> McpLauncherStaging.Staged = { stageBundled(it) },
    private val jsonEditor: () -> JsonMcpEditor? = { project.getService(JsonMcpEditor::class.java) },
    private val tomlEditor: () -> TomlMcpEditor? = { project.getService(TomlMcpEditor::class.java) },
    private val copy: (String)->Unit = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it),null) },
    private val plan: () -> McpLauncherStaging.Staged = ::planBundled,
    private val detect: ((McpClient)->Boolean)? = null,
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows")
) : JPanel(BorderLayout(8,8)) {
    internal val client=JComboBox(McpClient.values())
    internal val global=JCheckBox("Global (all projects)")
    internal val directory=JTextField(project.basePath.orEmpty())
    internal val previewButton=JButton("Preview")
    internal val connectButton=JButton("Connect")
    internal val copyButton=JButton("Copy command / config")
    internal val cleanupPreviewButton=JButton("Preview old versions")
    internal val cleanupDeleteButton=JButton("Delete listed versions")
    internal var busy=false; private set
    private var closed=false
    private var cleanupPreview:List<Path> = emptyList()
    private var cleanupCurrent:McpLauncherStaging.Staged?=null
    private var detectedClients:Map<McpClient,Boolean> = emptyMap()
    internal val status=object:JTextArea(2,70) {
        override fun setText(text:String?) { super.setText(text);toolTipText=text }
    }.apply {
        isEditable=false;isFocusable=false;isOpaque=false
        lineWrap=true;wrapStyleWord=true
        font=UIManager.getFont("Label.font")
        minimumSize=Dimension(0,0)
        accessibleContext.accessibleName="Status"
        text="Select a client and preview its Reclazz entry. No client config has been written."
    }
    private val formatNotice=JLabel("VS Code uses editor-managed MCP configuration (deprecated; removal February 2027).").apply { isVisible=false }
    private val before=JTextArea(12,35).apply { isEditable=false;lineWrap=true;wrapStyleWord=false;minimumSize=Dimension(0,0) }
    private val after=JTextArea(12,35).apply { isEditable=false;lineWrap=true;wrapStyleWord=false;minimumSize=Dimension(0,0) }
    private data class Selection(val client:McpClient,val global:Boolean,val directory:String)
    private data class Prepared(val entry:McpEntry,val path:Path,val scope:McpClientConfigs.Scope,val staged:McpLauncherStaging.Staged)
    private data class Connection(val prepared:Prepared,val config:McpClientConfigs.Preview)
    private data class Copied(val prepared:Prepared,val text:String)
    private var preview:Connection?=null
    private var pendingCopy:Copied?=null
    init {
        client.renderer=object:DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list:JList<*>?,value:Any?,index:Int,selected:Boolean,focus:Boolean):java.awt.Component {
                val c=super.getListCellRendererComponent(list,value,index,selected,focus)
                if(value is McpClient)text=value.display+when(detectedClients[value]) {
                    true -> " — detected"
                    false -> " — not detected; manual setup available"
                    null -> " — checking"
                }
                return c
            }
        }
        val inputs=JPanel(GridLayout(0,1,4,4)).apply {
            add(JLabel("AI assistant"));add(client);add(global)
            add(JLabel("Project directory (this IDE project or a directory inside it)"));add(directory);add(formatNotice)
        }
        add(inputs,BorderLayout.NORTH)
        fun box(title:String,area:JTextArea)=JPanel(BorderLayout()).apply { border=BorderFactory.createTitledBorder(title);add(JScrollPane(area)) }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT,box("Existing config — review the current Reclazz target",before),box("Preview — only the Reclazz entry changes",after)).apply { resizeWeight=0.5 },BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JPanel().apply { add(previewButton);add(connectButton);add(copyButton);add(cleanupPreviewButton);add(cleanupDeleteButton) },BorderLayout.NORTH)
            add(status,BorderLayout.SOUTH)
        },BorderLayout.SOUTH)
        preferredSize=Dimension(960,540)
        fun invalidatePreview() {
            preview=null;pendingCopy=null;cleanupPreview=emptyList();cleanupCurrent=null
            before.text="";after.text="";refreshControls()
        }
        client.addActionListener {
            formatNotice.isVisible=client.selectedItem==McpClient.VSCODE
            if((client.selectedItem as McpClient).globalFile==null)global.isSelected=false
            invalidatePreview()
        }
        global.addActionListener { invalidatePreview() }
        directory.document.addDocumentListener(object:javax.swing.event.DocumentListener {
            override fun insertUpdate(e:javax.swing.event.DocumentEvent)=invalidatePreview()
            override fun removeUpdate(e:javax.swing.event.DocumentEvent)=invalidatePreview()
            override fun changedUpdate(e:javax.swing.event.DocumentEvent)=invalidatePreview()
        })
        previewButton.addActionListener {
            val selected=selection();invalidatePreview()
            work({
                val prepared=prepare(selected)
                val config=McpClientConfigs.preview(prepared.path,prepared.scope) { text -> runReadAction {
                    if(selected.client==McpClient.CODEX) {
                        val editor=tomlEditor() ?: error("TOML support is unavailable. Use Copy config; no file was written.")
                        editor.merge(text,prepared.entry)
                    } else {
                        val editor=jsonEditor() ?: error("JSON support is unavailable. Use Copy command / config; no file was written.")
                        editor.merge(text,selected.client.serversKey!!,prepared.entry)
                    }
                } }
                Connection(prepared,config)
            }) { result ->
                preview=result
                before.text=result.config.before?.toString(Charsets.UTF_8) ?: "(new file)";after.text=result.config.after
                status.text="${if(selected.global) "Global (all projects)" else "Project"}: ${result.config.path} — preview only. Connect creates ${result.prepared.staged.directory} and the Java fallback, then saves this config with a backup."
            }
        }
        connectButton.addActionListener {
            val result=preview ?: return@addActionListener
            preview=null
            work({
                result.prepared.scope.validate(result.config.path)
                publish(result.prepared)
                McpLauncherStaging.rememberConfig(home,result.config.path)
                result.config.confirm()
            }) { backup ->
                status.text="Saved ${result.config.path}. "+(backup?.let { "Backup: $it. " } ?: "New file. ")+"Reconnect the client; approve/trust the project there if requested."
            }
        }
        copyButton.addActionListener {
            val pending=pendingCopy
            if(pending==null) {
                val selected=selection();invalidatePreview()
                work({
                    val prepared=prepare(selected)
                    Copied(prepared,McpClientConfigs.copySetup(selected.client,selected.global,prepared.entry,windows))
                }) { result ->
                    pendingCopy=result;after.text=result.text
                    status.text="Review the copied setup above. Create and copy will create ${result.prepared.staged.directory}, refresh the Java fallback and remember this version for cleanup. No client config is written."
                }
            } else {
                pendingCopy=null
                work({
                    pending.prepared.scope.validate(pending.prepared.path)
                    publish(pending.prepared)
                    McpLauncherStaging.rememberConfig(home,pending.prepared.path,pending.prepared.staged)
                    pending.text
                }) { text -> copy(text);status.text="Copied setup; no client config was written. Its launcher version is protected from cleanup, including before you paste it." }
            }
        }
        cleanupPreviewButton.addActionListener {
            invalidatePreview()
            work({ val current=plan();current to McpLauncherStaging.cleanupCandidates(home,current) }) { (current,candidates) ->
                cleanupCurrent=current;cleanupPreview=candidates
                before.text="The running plugin version, newest three versions, known config references and copied setups are kept. Manually created, unregistered client configs cannot be detected; check them before deleting."
                after.text=candidates.joinToString("\n")
                status.text="Review old staged MCP versions above. Only Delete listed versions removes them."
            }
        }
        cleanupDeleteButton.addActionListener {
            val candidates=cleanupPreview;val current=cleanupCurrent ?: return@addActionListener
            cleanupPreview=emptyList()
            work({ McpLauncherStaging.cleanupConfirmed(home,candidates,current) }) {
                after.text="";status.text="Removed only the listed unreferenced old MCP versions."
            }
        }
        refreshControls()
        work({ McpClient.values().associateWith { detect?.invoke(it) ?: detected(it) } }) { detectedClients=it;client.repaint() }
    }
    private fun selection()=Selection(client.selectedItem as McpClient,global.isSelected,directory.text)
    private fun refreshControls() {
        client.isEnabled=!busy;directory.isEnabled=!busy
        global.isEnabled=!busy && (client.selectedItem as McpClient).globalFile!=null
        previewButton.isEnabled=!busy;copyButton.isEnabled=!busy;cleanupPreviewButton.isEnabled=!busy
        connectButton.isEnabled=!busy && preview!=null
        cleanupDeleteButton.isEnabled=!busy && cleanupPreview.isNotEmpty()
        copyButton.text=if(pendingCopy==null)"Copy command / config" else "Create and copy"
    }
    private fun <T> work(action:()->T, complete:(T)->Unit) {
        if(busy || closed)return
        // Construction runs under Settings before the Connect dialog is shown. Its modality
        // would strand the completion while Connect is open. This callback only updates this
        // panel (or its explicitly requested clipboard); model/IO work stays in the pool.
        val modality=ModalityState.any()
        busy=true;refreshControls()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result=runCatching(action)
            ApplicationManager.getApplication().invokeLater({
                if(!closed && !project.isDisposed) {
                    busy=false
                    result.fold({ value ->
                        try { complete(value) } catch(e:Exception) { status.text=e.message }
                    },{ failure ->
                        preview=null;pendingCopy=null;cleanupPreview=emptyList()
                        status.text=failure.message ?: "Assistant setup could not be completed."
                    })
                    refreshControls()
                }
            },modality)
        }
    }
    internal fun close() { closed=true }
    private fun publish(prepared:Prepared) {
        require(stage(prepared.staged)==prepared.staged) { "Bundled MCP changed; preview again" }
    }
    private fun prepare(selected:Selection):Prepared {
        val root=Path.of(selected.directory);require(root.isAbsolute) { "Choose an absolute project directory" }
        val real=root.toRealPath()
        val base=Path.of(requireNotNull(project.basePath) { "Open an IDE project first" }).toRealPath()
        require(real.startsWith(base)) { "Choose this IDE project's directory or a directory inside it" }
        val sap=runReadAction { HybrisProjectDetector.findHybrisHome(project)?.toRealPath() }
        require(sap==null || sap.startsWith(real)) { "SAP Commerce home is outside the selected project. Choose its containing IDE project." }
        val version=plan()
        val args=mutableListOf("--project-dir",real.toString())
        val configured=runReadAction { ReclazzSettings.getInstance(project).state.portFilePath }
        val port=when {
            configured.isNotBlank() -> real.resolve(configured).normalize()
            sap!=null -> sap.resolve(".reclazz/agent.port")
            else -> null
        }
        if(port!=null) {
            require(port.startsWith(real)) { "Agent port file must be inside the selected project" }
            args.addAll(listOf("--port-file",port.toString()))
        }
        val entry=McpEntry(version.launcher.toString(),args)
        if(windows)entry.validateWindowsLauncher()
        val file=selected.client.path(real,home.toRealPath(),selected.global).toAbsolutePath().normalize()
        val scope=McpClientConfigs.Scope(if(selected.global)home.toRealPath() else real)
        scope.validate(file)
        if(!selected.global && selected.client.globalFile!=null) {
            val globalPath=home.toRealPath().resolve(selected.client.globalFile).normalize()
            require(file!=globalPath) { "This is the global config. Select Global (all projects) explicitly." }
        }
        return Prepared(entry,file,scope,version)
    }
    private fun detected(client:McpClient):Boolean {
        val config=client.path(Path.of(project.basePath ?: "."),home,false)
        if(Files.exists(config) || client.globalFile?.let { Files.exists(home.resolve(it)) }==true)return true
        val executable=when(client) { McpClient.CLAUDE->"claude";McpClient.CURSOR->"cursor";McpClient.VSCODE->"code";McpClient.CODEX->"codex" }
        val paths=System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }
        if(paths.any { Files.isExecutable(Path.of(it).resolve(executable)) || Files.isExecutable(Path.of(it).resolve("$executable.exe")) })return true
        return when(client) {
            McpClient.CLAUDE -> Files.isExecutable(home.resolve(".local/bin/claude"))
            McpClient.CURSOR -> Files.isDirectory(Path.of("/Applications/Cursor.app"))
            McpClient.VSCODE -> Files.isDirectory(Path.of("/Applications/Visual Studio Code.app"))
            McpClient.CODEX -> false
        }
    }
    companion object {
        private fun isWindows()=System.getProperty("os.name").startsWith("Windows")
        private fun bundled(expected:McpLauncherStaging.Staged?):McpLauncherStaging.Staged {
            val plugin=requireNotNull(PluginPresence.enabledDescriptor(AgentJarLocator.RECLAZZ_PLUGIN_ID.idString)) { "Reclazz plugin metadata is unavailable" }
            val path=plugin.pluginPath
            val java=Path.of(System.getProperty("java.home"),"bin",if(isWindows())"java.exe" else "java")
            return if(expected==null) McpLauncherStaging.plan(path.resolve("mcp/reclazz-mcp.jar"),path.resolve("agent/reclazz-agent.jar"),plugin.version,java)
                else McpLauncherStaging.stage(path.resolve("mcp/reclazz-mcp.jar"),path.resolve("agent/reclazz-agent.jar"),plugin.version,java,expected=expected)
        }
        private fun planBundled()=bundled(null)
        private fun stageBundled(expected:McpLauncherStaging.Staged)=bundled(expected)
        fun show(project:Project) {
            object:DialogWrapper(project,false) {
                private val content=ConnectAiAssistantPanel(project)
                init { title="Connect AI assistant";setOKButtonText("Close");init() }
                override fun createCenterPanel()=content
                override fun createActions():Array<Action> = arrayOf(okAction)
                override fun dispose() { content.close();super.dispose() }
            }.show()
        }
    }
}
