/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package de.hybris.platform.jalo;
/** Isolated process-test session cleanup. */
public final class JaloSession {
    public static void deactivate() {
        com.onurkat.reclazz.hybris.ProcessTenantFixture.SESSIONS.get().remove(
                com.onurkat.reclazz.hybris.ProcessTenantFixture.CURRENT.get());
    }
}
