/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A breakpoint in an edited method has to hit after the edit is reloaded.
 *
 * <p>On a stock JDK the edited body runs in a companion class, a hidden
 * class named {@code app.Greeter$$Reclazz$v1/0x...}. What a debugger needs
 * from it is what it needs from any class: the source file name, so the
 * editor's line maps to it, and a line number table for the new body. The
 * IDE finds it the way it finds anonymous classes and lambdas, by the
 * {@code Greeter$*} pattern, so this does what the IDE does over JDI: after
 * the reload, find the companion, ask for the locations of a line that
 * exists only in the new body, set a breakpoint there, and wait for it.
 */
class BreakpointsBindToReloadedCodeTest {

    @TempDir
    Path tmp;

    @Test
    void aBreakpointOnALineOfTheNewBodyHits() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0")
                .with("Greeter", greeter("v1"))
                .with("App", driver())
                .start()) {

            app.awaitOrFail("Listening for transport dt_socket", "the JVM did not open a debug port");
            app.awaitOrFail("GREET=v1", "the first version never served");
            app.awaitOrFail("Content-hash baseline", "the watcher never took its baseline");
            int port = debugPort(app.output());

            app.rewrite("Greeter", greeter("v2"));
            app.awaitOrFail("GREET=v2", "the reload never reached the app");

            VirtualMachine vm = attach(port);
            try {
                // Line 6 is `String value = "v2";`, which the old body did not have.
                List<ReferenceType> companions = vm.allClasses().stream()
                        .filter(t -> t.name().startsWith("app.Greeter$$Reclazz"))
                        .toList();
                assertFalse(companions.isEmpty(), () -> "no companion class visible to the debugger among "
                        + vm.allClasses().stream().map(ReferenceType::name).filter(n -> n.startsWith("app.")).toList());
                ReferenceType companion = companions.get(companions.size() - 1);
                assertEquals("Greeter.java", companion.sourceName(), "the source file the editor knows");
                List<Location> atNewLine = companion.locationsOfLine(6);
                assertFalse(atNewLine.isEmpty(), "line 6 of the new body has code in the companion");

                BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(atNewLine.get(0));
                request.setSuspendPolicy(BreakpointRequest.SUSPEND_EVENT_THREAD);
                request.enable();
                try {
                    BreakpointEvent hit = awaitBreakpoint(vm, 20_000);
                    assertNotNull(hit, "the breakpoint on the reloaded line was never hit while the app kept calling greet()");
                    assertEquals(6, hit.location().lineNumber());
                    assertEquals("Greeter.java", hit.location().sourceName());
                    assertEquals("greet", hit.location().method().name(), "the frame still reads as the method the developer wrote");
                    assertFalse(hit.location().method().isSynthetic(),
                            "a synthetic method is one the IDE's default step filter skips");
                    hit.thread().resume();
                } finally {
                    request.disable();
                }
            } finally {
                vm.dispose();
            }
            assertTrue(app.awaits("GREET=v2", 10), "and the app runs on after the debugger lets go");
        }
    }

    private static String greeter(String version) {
        if ("v1".equals(version)) {
            return """
                    package app;
                    public class Greeter {
                        public String greet() { return "v1"; }
                    }
                    """;
        }
        return """
                package app;
                public class Greeter {
                    public String greet() {
                        // the new body, two statements so a line exists that the old one lacked
                        String value = "v2";
                        return value;
                    }
                }
                """;
    }

    private static String driver() {
        return """
                package app;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Greeter g = new Greeter();
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(200);
                            System.out.println("GREET=" + g.greet());
                        }
                    }
                }
                """;
    }

    private static int debugPort(List<String> output) {
        Pattern p = Pattern.compile("Listening for transport dt_socket at address: (\\d+)");
        for (String line : output) {
            Matcher m = p.matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        throw new AssertionError("no debug port in the output");
    }

    private static VirtualMachine attach(int port) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst().orElseThrow();
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue("127.0.0.1");
        args.get("port").setValue(String.valueOf(port));
        return connector.attach(args);
    }

    private static BreakpointEvent awaitBreakpoint(VirtualMachine vm, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            EventSet set = vm.eventQueue().remove(Math.max(1, deadline - System.currentTimeMillis()));
            if (set == null) return null;
            for (Event event : set) {
                if (event instanceof BreakpointEvent bp) return bp;
            }
            set.resume();
        }
        return null;
    }
}
