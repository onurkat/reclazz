/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.EnumSurgery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What Java 21 switches do with an appended constant, held as tests.
 *
 * <p>javac compiles a switch over an enum two ways. Plain constant labels
 * become a lookup through a synthetic {@code $SwitchMap} array, which the
 * append grows. A switch with a guard or a type pattern becomes an
 * {@code invokedynamic} to {@code SwitchBootstraps.enumSwitch}, which the
 * append does not touch, so what that call site does with a constant born
 * after it was linked had to be measured, not assumed.
 *
 * <p>Measured on stock JDK 21 and on JetBrains Runtime 25, with and without
 * enhanced redefinition, all three agreeing, and pinned here:
 *
 * <pre>
 *   pattern switch ending in a total type pattern   matches the new constant
 *   pattern switch exhaustive via constant labels   throws MatchException
 *   arrow switch exhaustive, no default written     throws MatchException
 *   every old constant, every shape                 unchanged
 * </pre>
 *
 * <p>The MatchException rows are javac's design, not damage: an exhaustive
 * switch gets a synthetic default that throws (MatchException from source 21,
 * IncompatibleClassChangeError from older source levels), because the compiler
 * proved every constant it knew was covered and this one is not among them.
 * The failure these tests prevent is the quieter one: an indy call site that
 * cached a mapping sized to the old universe and sent the new constant down a
 * wrong branch instead of throwing. None of the tested JDKs does that, and if
 * one ever starts, this file is what says so.
 *
 * <p>The switch code is compiled at runtime because the agent itself compiles
 * at source 17, where guarded patterns do not parse.
 */
class PatternSwitchAppendTest {

    @TempDir
    static Path dir;

    private static Class<?> status;
    private static Class<?> switches;
    private static Object shipped;

    @BeforeAll
    static void compileAppendAndLinkCallSites() throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "guarded patterns need source 21");
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no compiler in this JRE");

        Path pkg = Files.createDirectories(dir.resolve("spike"));
        Files.writeString(pkg.resolve("PStatus.java"),
                "package spike; public enum PStatus { NEW, PAID }");
        Files.writeString(pkg.resolve("PSwitches.java"), """
                package spike;
                public class PSwitches {
                    public static String patternTotal(PStatus s) {
                        return switch (s) {
                            case NEW -> "new";
                            case PStatus x when x.name().startsWith("P") -> "guard:" + x;
                            case PStatus x -> "other:" + x;
                        };
                    }
                    public static String patternConstants(PStatus s) {
                        return switch (s) {
                            case PStatus x when x.name().isEmpty() -> "impossible";
                            case NEW -> "new";
                            case PAID -> "paid";
                        };
                    }
                    public static String arrow(PStatus s) {
                        return switch (s) { case NEW -> "new"; case PAID -> "paid"; };
                    }
                }
                """);
        int rc = compiler.run(null, null, null,
                pkg.resolve("PStatus.java").toString(), pkg.resolve("PSwitches.java").toString());
        assertEquals(0, rc, "the spike sources have to compile");

        URLClassLoader loader = new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},
                PatternSwitchAppendTest.class.getClassLoader());
        status = Class.forName("spike.PStatus", true, loader);
        switches = Class.forName("spike.PSwitches", true, loader);

        // Link every indy call site against the two-constant universe first:
        // the bug being hunted is a mapping cached at link time, and a call
        // site linked after the append could never show it.
        for (Object constant : status.getEnumConstants()) {
            call("patternTotal", constant);
            call("patternConstants", constant);
            call("arrow", constant);
        }

        var outcome = EnumSurgery.append(status, List.of("SHIPPED"));
        assertTrue(outcome.applied(), "declined: " + outcome.declinedBecause());

        // The arrow switch reads a $SwitchMap in a synthetic sibling class;
        // grow it the way the agent does after a real append.
        Class<?> holder = Class.forName("spike.PSwitches$1", true, loader);
        EnumSurgery.growSwitchTables(new Class<?>[]{switches, holder}, status, 3);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Enum<?> resolved = Enum.valueOf((Class) status, "SHIPPED");
        shipped = resolved;
    }

    @Test
    void aTotalTypePatternMatchesTheAppendedConstant() throws Exception {
        assertEquals("other:SHIPPED", call("patternTotal", shipped),
                "the indy call site was linked before the append and must not "
                + "have cached its way into a wrong branch");
    }

    @Test
    void aPatternSwitchExhaustiveViaConstantsThrowsMatchExceptionOnTheAppendedConstant() {
        InvocationTargetException e = assertThrows(InvocationTargetException.class,
                () -> call("patternConstants", shipped));
        assertEquals("java.lang.MatchException", e.getCause().getClass().getName(),
                "javac's synthetic default for a switch it proved exhaustive; "
                + "anything else would be the call site inventing an answer");
    }

    @Test
    void anExhaustiveArrowSwitchThrowsMatchExceptionOnTheAppendedConstant() {
        InvocationTargetException e = assertThrows(InvocationTargetException.class,
                () -> call("arrow", shipped));
        assertEquals("java.lang.MatchException", e.getCause().getClass().getName(),
                "the grown $SwitchMap sends the new constant to the default, "
                + "and for an exhaustive switch the default is javac's throw");
    }

    @Test
    void everyOldConstantStillTakesItsOldBranchInEveryShape() throws Exception {
        Object[] constants = status.getEnumConstants();
        assertEquals("new", call("patternTotal", constants[0]));
        assertEquals("guard:PAID", call("patternTotal", constants[1]));
        assertEquals("new", call("patternConstants", constants[0]));
        assertEquals("paid", call("patternConstants", constants[1]));
        assertEquals("new", call("arrow", constants[0]));
        assertEquals("paid", call("arrow", constants[1]));
    }

    private static Object call(String method, Object arg) throws Exception {
        Method m = switches.getMethod(method, status);
        return m.invoke(null, arg);
    }
}
