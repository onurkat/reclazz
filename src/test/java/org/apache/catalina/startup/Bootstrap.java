/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package org.apache.catalina.startup;

/** Stand-in child process for the setup wizard's server identity check. */
public final class Bootstrap {
    public static void main(String[] args) throws java.io.IOException {
        System.in.read();
    }
}
