import com.intellij.remoterobot.RemoteRobot;
import com.intellij.remoterobot.fixtures.ComponentFixture;
import static com.intellij.remoterobot.search.locators.Locators.byXpath;
import java.awt.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.function.Predicate;
import javax.imageio.ImageIO;

public class UiClient {
    static final RemoteRobot robot = new RemoteRobot("http://127.0.0.1:8580");
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    static final Path output = Path.of("/out");
    static ComponentFixture frame;
    static final Duration timeout = Duration.ofSeconds(150);
    static String step;

    public static void main(String[] args) throws Exception {
        try {
            step = "open fixture";
            frame = robot.find(ComponentFixture.class, byXpath("//div[@class='IdeFrameImpl']"), timeout);
            String proof = frame.callJs("const id=Packages.com.intellij.openapi.extensions.PluginId.getId('com.onurkat.reclazz');"
                    + "const p=Packages.com.intellij.ide.plugins.PluginManagerCore.getPlugin(id);"
                    + "String(Packages.com.intellij.openapi.application.ApplicationInfo.getInstance().getBuild().asString())"
                    + "+'|'+String(p.isEnabled())+'|'+String(component.isShowing());");
            check(proof.equals("IC-233.11799.241|true|true"), proof);
            System.out.println("Real plugin frame " + proof);
            await("ready", "fixture"::equals);
            if (args[0].startsWith("wizard")) {
                await("wizardStartup", "started"::equals);
                if (args[0].equals("wizard-restart")) {
                    check(act("wizardCount").equals("0"), "wizard reopened on unchanged restart");
                    System.out.println("PASS restart with persisted settings and same installation");
                } else {
                    await("wizardCount", "1"::equals);
                    await("wizardStep", "Step 1 of 3"::equals);
                    check(act("wizardManual").equals("invoked"), "Tools action failed");
                    await("wizardSame", "true"::equals);
                    wizardPages(args[0]);
                    await("wizardDismiss", "clicked"::equals);
                    await("wizardClick:Close", "clicked"::equals);
                    await("wizardCount", "0"::equals);
                    System.out.println("PASS " + args[0] + " automatic single dialog, startup complete, Tools focus, navigation and dismissal");
                }
                act("wizardExit");
                return;
            }
            await("wizardStartup", "started"::equals);
            if (act("wizardCount").equals("1")) await("wizardClick:Close", "clicked"::equals);
            act("setup");
            if (args[0].equals("settings-setup")) {
                settingsSetup();
                return;
            }
            if (!args[0].startsWith("settings")) reloadFlow();
            if (!args[0].equals("reload")) settingsFlow(args[0]);
        } catch (Throwable failure) {
            System.err.println("FAIL " + step + ": " + failure);
            if (frame != null) {
                try { Files.writeString(output.resolve("failure-reload.log"), act("log")); }
                catch (RuntimeException ignored) { /* The IDE might have already exited. */ }
            }
            var screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            ImageIO.write(new Robot().createScreenCapture(screen), "png", output.resolve("failure.png").toFile());
            throw failure;
        }
    }

    static void reloadFlow() throws Exception {
        build("success");
        act("run");
        String initial = await("http", s -> s.matches("[0-9]+:baseline"));
        String pid = initial.split(":")[0];
        change("A", pid);
        change("B", pid);

        step = "unchanged build";
        long count = receipts();
        build("success");
        stable(pid + ":B", count);
        step = "compile error";
        act("edit:broken");
        build("errors");
        stable(pid + ":B", count);
        change("fixed", pid);

        step = "stop and reconnect";
        long connections = act("log").lines().filter(line -> line.contains("[CONNECTED]")).count();
        act("stop");
        await("http", String::isEmpty);
        act("run");
        String restarted = await("http", s -> s.matches("[0-9]+:fixed") && !s.equals(pid + ":fixed"));
        change("reconnected", restarted.split(":")[0]);
        String reconnected = act("log");
        check(reconnected.contains("[DISCONNECTED]") && reconnected.lines()
                .filter(line -> line.contains("[CONNECTED]")).count() > connections,
                "the Reload Log did not record a new connection");
    }

    static void wizardPages(String scenario) throws Exception {
        boolean sap = scenario.equals("wizard-sap");
        boolean disabled = scenario.equals("wizard-java-off");
        String kind = sap ? "sap" : "java";
        step = kind + " agent setup";
        if (sap) {
            await("wizardControls", s -> s.contains("action=Install Reclazz") && s.contains("tomcat.javaoptions=") && s.contains("tomcat.debugjavaoptions="));
            capture("sap-1-install");
            await("wizardClick:Install Reclazz", "clicked"::equals);
        } else if (disabled) {
            await("wizardControls", s -> s.contains("action=Turn on Reclazz"));
            capture("java-1-off");
            await("wizardClick:Turn on Reclazz", "clicked"::equals);
        }
        await("wizardControls", s -> s.contains("action=Next") && s.contains("label=Done") && !s.contains(" of 2 done"));
        if (!sap) {
            await("wizardControls", s -> s.contains("Reclazz is on for this project"));
            capture("java-1-enabled");
        }
        await("wizardClick:Next", "clicked"::equals);
        await("wizardStep", "Step 2 of 3"::equals);
        if (sap) {
            step = "SAP ant server output";
            await("wizardControls", s -> s.contains("action=Run ant server"));
            capture("sap-2-run");
            await("wizardClick:Run ant server", "clicked"::equals);
            await("wizardControls", s -> s.contains("action=Stop ant server") && s.contains("Generating the fixture server configuration"));
            capture("sap-2-live-output");
            Files.createFile(Path.of("/tmp/continue-fixture-ant"));
            await("wizardClick:Next", "clicked"::equals);
            await("wizardStep", "Step 3 of 3"::equals);
        }
        step = kind + " assistant";
        if (!scenario.equals("wizard-reinstall")) {
            await("wizardControls", s -> s.contains("action=Connect ") && s.contains("action=Skip"));
            capture(kind + "-assistant");
            await("wizardClick:Skip", "clicked"::equals);
        }
        await("wizardControls", s -> s.contains("label=Skipped"));
        await("wizardClick:" + (sap ? "Finish" : "Next"), "clicked"::equals);
        await("wizardControls", s -> s.contains("label=All set") && s.contains("action=Close"));
        capture(kind + "-all-set");
        await("wizardClick:Back", "clicked"::equals);
    }

    static void capture(String name) throws Exception {
        ImageIO.write(new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())),
                "png", output.resolve(name + ".png").toFile());
    }

    static void settingsSetup() throws Exception {
        step = "pending edits from Settings to the setup wizard";
        for (String choice : new String[]{"Discard", "Apply"}) {
            openSettings();
            check(act("formChange").equals("changed"), "could not modify verbose logging");
            check(act("formWizard").equals("opening"), "Set Up Reclazz button missing");
            if (choice.equals("Discard")) {
                await("formDecisionCancel", "chosen"::equals);
                check(act("formLabels").contains("Verbose logging"), "Cancel closed Settings");
                check(act("formSavedVerbose").equals("false"), "Cancel committed an edit");
                check(act("formWizard").equals("opening"), "could not retry setup");
            }
            await("formDecision" + choice, "chosen"::equals);
            await("formNewWizard", "visible"::equals);
            capture("settings-to-wizard-" + choice.toLowerCase(java.util.Locale.ROOT));
            check(act("formLabels").equals("waiting"), "Settings stayed open");
            check(act("formSavedVerbose").equals(String.valueOf(choice.equals("Apply"))), "wrong persisted value after " + choice);
            check(act("formCloseWizard").equals("closed"), "could not close wizard");
        }
        System.out.println("PASS Settings to real wizard, Cancel / Discard / Apply and persisted changes");
    }

    static void settingsFlow(String scenario) throws Exception {
        step = "adaptive delay setting";
        act("settings");
        String labels = await("formLabels", value -> value.contains("Connect AI assistant"));
        for (String expected : new String[]{"General", "Spring and JPA", "Tests", "Advanced (troubleshooting)",
                "Set Up Reclazz", "Test connection", "Enable Reclazz in this project", "Apply reloads",
                "Refresh JPA mappings", "Attach Reclazz to IDE test runs"}) check(labels.contains(expected), "Missing: " + expected);
        check(!labels.contains("Use a fixed delay"), "Advanced was expanded");
        boolean sap = scenario.startsWith("settings-sap");
        for (String field : new String[]{"SAP Commerce", "Data on save", "Check setup", "Watched extensions", "Choose...",
                "Reclazz compiles saved Java files", "Import ImpEx", "Allow REMOVE", "Update Solr documents",
                "Update the running system", "Reset Backoffice configuration"}) {
            check(labels.contains(field) == sap, "Wrong project visibility: " + field);
        }
        boolean installed = scenario.equals("settings-sap-installed");
        check(labels.contains("Install Java Agent") == (sap && !installed), "Install visibility");
        check(labels.contains("Reinstall") == installed, "Reinstall visibility");
        check(java.util.Arrays.asList(labels.split("\\|")).contains("Remove") == installed, "Remove visibility");
        ImageIO.write(new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())),
                "png", output.resolve(scenario + ".png").toFile());
        expandAdvanced();
        String advanced = await("formLabels", value -> value.contains("Use a fixed delay"));
        for (String expected : new String[]{"Reload added or removed", "Re-run changed static", "Set JVM options",
                "Verbose logging", "Exclude classes", "Ignore files", "Startup delay", "Java agent port", "Port file",
                "Attach to Running Server"}) check(advanced.contains(expected), "Advanced missing: " + expected);
        await("setting", s -> s.startsWith("false|false|"));
        check("applied".equals(act("override")), "settings did not apply");
        openSettings();
        await("setting", "true|true|750"::equals);
        System.out.println("PASS settings adaptive default and 750 ms override");
    }

    static void openSettings() throws Exception {
        act("settings");
        expandAdvanced();
    }

    static void expandAdvanced() throws Exception {
        var advanced = robot.find(ComponentFixture.class,
                byXpath("//div[@class='CollapsibleTitledSeparatorImpl' and @accessiblename='Advanced (troubleshooting)']"), timeout);
        if (act("setting").equals("opening")) advanced.click();
    }

    static String act(String command) {
        return frame.callJs("const id=Packages.com.intellij.openapi.extensions.PluginId.getId('com.example.reclazz.ui.driver');"
                + "const loader=Packages.com.intellij.ide.plugins.PluginManagerCore.getPlugin(id).getPluginClassLoader();"
                + "const driver=loader.loadClass('com.example.reclazz.uitest.IdeActions');"
                + "String(driver.getMethod('perform',java.lang.Class.forName('java.lang.String')).invoke(null,'" + command + "'));", false);
    }

    static String fetch() throws InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18882/value")).timeout(Duration.ofSeconds(2)).GET().build();
        try {
            var reply = http.send(request, HttpResponse.BodyHandlers.ofString());
            return reply.statusCode() == 200 ? reply.body() : "";
        } catch (java.io.IOException unavailable) { return ""; }
    }

    static String await(String query, Predicate<String> condition) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        String result = "";
        while (System.nanoTime() < end) {
            result = query.equals("http") ? fetch() : act(query);
            if (condition.test(result)) return result;
            Thread.sleep(250);
        }
        throw new AssertionError("timeout for " + query + ": " + result);
    }

    static void build(String expected) throws Exception {
        System.out.println("Build Project: " + step);
        act("build");
        String result = await("buildResult", s -> !s.equals("pending"));
        check(expected.equals(result), "IDE Build Project returned " + result + ", expected " + expected);
    }

    static long count(String text) {
        return text.lines().filter(line -> line.contains("[SWAP]") && line.contains("Reloaded com.example.ui.UiApp")).count();
    }

    static long receipts() { return count(act("log")); }

    static void change(String value, String pid) throws Exception {
        step = "edit and build " + value;
        long before = receipts();
        act("edit:" + value);
        build("success");
        await("http", (pid + ":" + value)::equals);
        await("log", s -> count(s) > before);
        stable(pid + ":" + value, before + 1);
    }

    static void stable(String body, long expected) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        do {
            check(fetch().equals(body), "app response or PID changed");
            String log = act("log");
            Files.writeString(output.resolve(step.replace(' ', '-') + ".log"), log);
            check(count(log) == expected, "expected " + expected + " applied receipts, got " + count(log));
            Thread.sleep(250);
        } while (System.nanoTime() < end);
        System.out.println("PASS " + step + " HTTP " + body + ", receipts " + expected);
    }

    static void check(boolean ok, String detail) {
        if (!ok) throw new AssertionError(detail);
    }
}
