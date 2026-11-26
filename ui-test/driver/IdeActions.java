package com.example.reclazz.uitest;

import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.ExecutionManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.application.ModalityState;
import java.util.concurrent.atomic.AtomicReference;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.task.ProjectTaskListener;
import com.intellij.task.ProjectTaskManager;
import com.intellij.util.messages.MessageBusConnection;
import com.onurkat.reclazz.plugin.settings.ReclazzConfigurable;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

public final class IdeActions {
    private static volatile String buildResult = "idle";
    private static String lastValue = "baseline";
    private static final String SOURCE = "/tmp/fixture/src/main/java/com/example/ui/UiApp.java";

    private static JDialog firstWizard;
    private static boolean startupBarrier;

    public static String perform(String command) {
        var result = new AtomicReference<String>();
        boolean inDialog = command.startsWith("form") || command.equals("setting") || command.equals("override") || command.equals("log");
        ApplicationManager.getApplication().invokeAndWait(
                () -> result.set(performOnEdt(command)), inDialog ? ModalityState.any() : ModalityState.nonModal());
        return result.get();
    }

    private static String performOnEdt(String command) {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        if (projects.length != 1) return "waiting for fixture";
        Project project = projects[0];
        if (command.startsWith("wizard")) {
            var dialogs = java.util.Arrays.stream(Window.getWindows())
                    .filter(w -> w instanceof JDialog d && w.isShowing() && d.getTitle().equals("Set Up Reclazz"))
                    .map(w -> (JDialog) w).toList();
            if (command.equals("wizardCount")) return String.valueOf(dialogs.size());
            if (command.equals("wizardStartup")) {
                if (!com.intellij.openapi.startup.StartupManager.getInstance(project).postStartupActivityPassed()
                        || DumbService.isDumb(project)) return "starting";
                if (!startupBarrier) {
                    DumbService.getInstance(project).smartInvokeLater(() -> SwingUtilities.invokeLater(() -> startupBarrier = true));
                    return "starting";
                }
                return "started";
            }
            if (command.equals("wizardExit")) {
                project.save();
                ApplicationManager.getApplication().saveSettings();
                SwingUtilities.invokeLater(() -> com.intellij.openapi.application.ex.ApplicationManagerEx.getApplicationEx().exit(true, true));
                return "exiting";
            }
            if (command.equals("wizardCompleted")) {
                var state = project.getService(com.onurkat.reclazz.plugin.settings.ReclazzSettings.class).getState();
                return String.valueOf(state.getCompletedSetupGeneration() != null);
            }
            if (command.equals("wizardOpenAgain")) {
                var action = ActionManager.getInstance().getAction("Reclazz.SetupWizard");
                var data = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project);
                action.actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null, "MainMenu", data));
                return "invoked";
            }
            if (dialogs.size() != 1) return "count=" + dialogs.size();
            var dialog = dialogs.get(0);
            var parts = new ArrayList<Component>();
            collect(dialog, parts);
            if (command.equals("wizardControls")) {
                var entries = new java.util.ArrayList<String>();
                for (var part : parts) {
                    if (!part.isShowing()) continue;
                    if (part instanceof JLabel l) entries.add("label=" + l.getText());
                    if (part instanceof AbstractButton b && b.isEnabled()) entries.add("action=" + b.getText());
                    if (part instanceof javax.swing.text.JTextComponent c) entries.add(c.getText());
                }
                return String.join("|", entries);
            }
            if (command.equals("wizardManual")) {
                firstWizard = dialog;
                var action = ActionManager.getInstance().getAction("Reclazz.SetupWizard");
                var data = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project);
                action.actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null, "MainMenu", data));
                return "invoked";
            }
            if (command.equals("wizardSame")) return String.valueOf(dialog == firstWizard && dialog.isActive());
            if (command.equals("wizardStep")) return parts.stream().filter(c -> c instanceof JLabel && c.isShowing())
                    .map(c -> ((JLabel)c).getText()).filter(t -> t != null && t.startsWith("Step ")).findFirst().orElse("loading");
            String label = command.equals("wizardDismiss") ? "Don't show again for this project" : command.substring("wizardClick:".length());
            for (var part : parts) {
                if (part instanceof AbstractButton btn && btn.isShowing() && btn.isEnabled() && label.equals(btn.getText())) {
                    btn.doClick();
                    return "clicked";
                }
            }
            return "unavailable";
        }
        if (command.equals("ready")) return DumbService.isDumb(project) ? "indexing" : project.getName();
        if (command.equals("setup")) {
            var sdk = JavaSdk.getInstance().createJdk("Fixture JDK", System.getenv("JAVA_HOME"), false);
            WriteAction.runAndWait(() -> {
                ProjectJdkTable.getInstance().addJdk(sdk);
                ProjectRootManager.getInstance(project).setProjectSdk(sdk);
            });
            editor(project);
            var tool = ToolWindowManager.getInstance(project).getToolWindow("Reclazz");
            tool.show(() -> tool.getContentManager().setSelectedContent(tool.getContentManager().findContent("Reload Log")));
            return "ready";
        }
        if (command.startsWith("edit:")) {
            String next = command.substring(5);
            var doc = editor(project).getDocument();
            String before = expression(lastValue);
            int start = doc.getText().indexOf(before);
            if (start < 0) throw new IllegalStateException("fixture expression missing");
            WriteCommandAction.runWriteCommandAction(project,
                    () -> doc.replaceString(start, start + before.length(), expression(next)));
            FileDocumentManager.getInstance().saveDocument(doc);
            lastValue = next;
            return "saved";
        }
        if (command.equals("build")) {
            buildResult = "pending";
            MessageBusConnection connection = project.getMessageBus().connect();
            connection.subscribe(ProjectTaskListener.TOPIC, new ProjectTaskListener() {
                @Override public void finished(ProjectTaskManager.Result result) {
                    buildResult = result.isAborted() ? "aborted" : result.hasErrors() ? "errors" : "success";
                    connection.disconnect();
                }
            });
            var actions = ActionManager.getInstance();
            actions.tryToExecute(actions.getAction("CompileDirty"), null, editor(project).getContentComponent(),
                    "ReclazzUiTest", true).doWhenRejected(() -> { buildResult = "action rejected"; connection.disconnect(); });
            return "started";
        }
        if (command.equals("buildResult")) return buildResult;
        if (command.equals("run")) {
            var config = RunManager.getInstance(project).findConfigurationByName("Fixture");
            if (config == null) throw new IllegalStateException("missing Fixture run configuration");
            ProgramRunnerUtil.executeConfiguration(config, DefaultRunExecutor.getRunExecutorInstance());
            return "launched";
        }
        if (command.equals("stop")) {
            var processes = ExecutionManager.getInstance(project).getRunningProcesses();
            if (processes.length != 1) throw new IllegalStateException("expected one running fixture: " + processes.length);
            processes[0].destroyProcess();
            return "stopping";
        }
        if (command.equals("log")) {
            var tool = ToolWindowManager.getInstance(project).getToolWindow("Reclazz");
            var component = tool.getContentManager().findContent("Reload Log").getComponent();
            for (var ed : EditorFactory.getInstance().getAllEditors()) {
                if (SwingUtilities.isDescendingFrom(ed.getContentComponent(), component)) return ed.getDocument().getText();
            }
            throw new IllegalStateException("Reload Log console missing");
        }
        if (command.equals("formSavedVerbose")) return String.valueOf(com.onurkat.reclazz.plugin.settings.ReclazzSettings.Companion.getInstance(project).getState().getVerbose());
        if (command.startsWith("formDecision") || command.equals("formNewWizard") || command.equals("formCloseWizard")) {
            for (var window : Window.getWindows()) {
                if (!window.isShowing()) continue;
                var parts = new ArrayList<Component>();
                collect(window, parts);
                if (command.startsWith("formDecision")) {
                    if (parts.stream().noneMatch(c -> c instanceof AbstractButton b && "Discard".equals(b.getText()))) continue;
                    String choice = command.substring("formDecision".length());
                    for (var c : parts) if (c instanceof AbstractButton b && choice.equals(b.getText()) && b.isShowing()) {
                        b.doClick(); return "chosen";
                    }
                } else if (window instanceof JDialog dialog && "Set Up Reclazz".equals(dialog.getTitle()) &&
                        parts.stream().anyMatch(c -> c instanceof JLabel l && l.getText() != null && l.getText().startsWith("Step "))) {
                    if (command.equals("formNewWizard")) return "visible";
                    for (var c : parts) if (c instanceof JButton b && "Close".equals(b.getText())) {
                        b.doClick(); return "closed";
                    }
                }
            }
            return "waiting";
        }
        if (command.startsWith("form")) {
            for (Window window : Window.getWindows()) {
                if (!window.isShowing()) continue;
                List<Component> parts = new ArrayList<>();
                collect(window, parts);
                if (parts.stream().noneMatch(c -> c instanceof JCheckBox box && box.isShowing() &&
                        "Enable Reclazz in this project".equals(box.getText()))) continue;
                if (command.equals("formLabels")) {
                    var labels = new java.util.LinkedHashSet<String>();
                    for (var c : parts) {
                        if (!c.isShowing()) continue;
                        var a = c.getAccessibleContext();
                        if (a != null && a.getAccessibleName() != null) labels.add(a.getAccessibleName());
                        if (c instanceof JLabel l && l.getText() != null) labels.add(l.getText());
                        if (c instanceof AbstractButton b && b.getText() != null) labels.add(b.getText());
                    }
                    return String.join("|", labels);
                }
                if (command.equals("formChange")) {
                    for (var c : parts) if (c instanceof JCheckBox b && "Verbose logging".equals(b.getText())) {
                        b.doClick();
                        return "changed";
                    }
                }
                if (command.equals("formWizard")) {
                    for (var c : parts) if (c instanceof JButton b && "Set Up Reclazz".equals(b.getText())) {
                        SwingUtilities.invokeLater(b::doClick);
                        return "opening";
                    }
                }
            }
            return "waiting";
        }
        if (command.equals("settings")) {
            SwingUtilities.invokeLater(() -> ShowSettingsUtil.getInstance().editConfigurable(project, new ReclazzConfigurable(project)));
            return "opening";
        }
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            List<Component> components = new ArrayList<>();
            collect(window, components);
            for (Component c : components) {
                if (!(c instanceof JCheckBox fixed) || !fixed.isShowing()
                        || !"Use a fixed delay".equals(fixed.getText())) continue;
                if (command.equals("override") && !fixed.isSelected()) fixed.doClick();
                for (Component candidate : components) {
                    if (!(candidate instanceof JSpinner spinner) || !spinner.isShowing()
                            || Math.abs(fixed.getLocationOnScreen().y - spinner.getLocationOnScreen().y) > 12) continue;
                    if (command.equals("setting")) return fixed.isSelected() + "|" + spinner.isEnabled() + "|" + spinner.getValue();
                    if (command.equals("override")) {
                        spinner.setValue(750);
                        for (Component button : components) {
                            if (button instanceof JButton ok && "OK".equals(ok.getText())) { ok.doClick(); return "applied"; }
                        }
                    }
                }
            }
        }
        if (command.equals("setting")) return "opening";
        throw new IllegalArgumentException(command);
    }

    private static String expression(String value) {
        return value.equals("broken") ? "missingFixtureSymbol()" : "ProcessHandle.current().pid() + \":" + value + "\"";
    }

    private static com.intellij.openapi.editor.Editor editor(Project project) {
        var file = LocalFileSystem.getInstance().refreshAndFindFileByPath(SOURCE);
        return FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file), true);
    }

    private static void collect(Component c, List<Component> children) {
        children.add(c);
        if (c instanceof Container container) for (Component child : container.getComponents()) collect(child, children);
    }
}
