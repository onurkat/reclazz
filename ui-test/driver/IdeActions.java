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

    public static String perform(String command) {
        var result = new AtomicReference<String>();
        boolean inDialog = command.equals("setting") || command.equals("override") || command.equals("log");
        ApplicationManager.getApplication().invokeAndWait(
                () -> result.set(performOnEdt(command)), inDialog ? ModalityState.any() : ModalityState.nonModal());
        return result.get();
    }

    private static String performOnEdt(String command) {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        if (projects.length != 1) return "waiting for fixture";
        Project project = projects[0];
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
                        || !"Override adaptive delay".equals(fixed.getText())) continue;
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
