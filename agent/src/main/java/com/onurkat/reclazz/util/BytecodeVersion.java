/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

/**
 * The Java version a class file was compiled for, against the one this JVM can
 * load.
 *
 * A build that targets a newer release than the server runs is an ordinary
 * mismatch, not an exotic one: the IDE and the server are configured
 * separately, and a module left on a newer toolchain still compiles cleanly.
 * The class file is only rejected later, by the JVM, at the moment of the
 * redefinition.
 *
 * That rejection used to arrive as a passing remark in the middle of a
 * successful-looking reload, and the reload was then reported as done. The
 * developer watched the log say it worked and the application behave as though
 * nothing had happened, which is the worst thing this tool can do: silence
 * would at least have sent them looking.
 */
public final class BytecodeVersion {

    /** Java 1.0 is class file major 45, and every release since adds one. */
    private static final int MAJOR_OF_JAVA_1 = 45;

    private BytecodeVersion() {
    }

    /** @return the class file major version, or -1 when these are not class bytes */
    public static int majorOf(byte[] bytecode) {
        if (bytecode == null || bytecode.length < 8) return -1;
        boolean isClassFile = (bytecode[0] & 0xFF) == 0xCA && (bytecode[1] & 0xFF) == 0xFE
                && (bytecode[2] & 0xFF) == 0xBA && (bytecode[3] & 0xFF) == 0xBE;
        if (!isClassFile) return -1;
        return ((bytecode[6] & 0xFF) << 8) | (bytecode[7] & 0xFF);
    }

    /** The highest class file version this JVM will load. */
    public static int maxSupportedMajor() {
        return Runtime.version().feature() + MAJOR_OF_JAVA_1 - 1;
    }

    public static int javaReleaseOf(int major) {
        return major - MAJOR_OF_JAVA_1 + 1;
    }

    /**
     * @return an explanation when this JVM cannot load these bytes at all, or
     *         null when it can. Named for what the developer has to change,
     *         because the version numbers alone leave them guessing which end
     *         is wrong.
     */
    public static String rejectionReason(byte[] bytecode) {
        int major = majorOf(bytecode);
        if (major < 0) return null;

        int max = maxSupportedMajor();
        if (major <= max) return null;

        return "compiled for Java " + javaReleaseOf(major)
                + ", but this JVM runs Java " + javaReleaseOf(max)
                + ". Nothing was applied. Build this module for Java "
                + javaReleaseOf(max) + ", or run the application on Java "
                + javaReleaseOf(major) + ".";
    }
}
