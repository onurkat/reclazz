/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Verifies transformed bytecode using ASM's CheckClassAdapter.
 * Used in debug/development mode to catch transformation bugs early.
 */
public class TransformVerifier {

    /**
     * Verify bytecode and return any errors found.
     *
     * @param className    internal class name for reporting
     * @param bytecode     bytecode to verify
     * @return null if valid, error message if invalid
     */
    public static String verify(String className, byte[] bytecode) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            ClassReader reader = new ClassReader(bytecode);
            CheckClassAdapter.verify(reader, false, pw);

            String result = sw.toString();
            if (!result.isEmpty()) {
                return "Verification errors in " + className + ":\n" + result;
            }
            return null;
        } catch (Exception e) {
            return "Verification failed for " + className + ": " + e.getMessage();
        }
    }

    /**
     * Verify and log warnings if issues found.
     */
    public static void verifyAndLog(String className, byte[] bytecode) {
        String error = verify(className, bytecode);
        if (error != null) {
            StatusReporter.warn(error);
        }
    }
}
