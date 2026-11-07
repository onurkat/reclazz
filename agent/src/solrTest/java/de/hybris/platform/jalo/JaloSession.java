/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package de.hybris.platform.jalo;
/** Isolated synthetic native session binding. */
public final class JaloSession {
    public static void deactivate() { com.onurkat.reclazz.hybris.SolrNativeFixture.SESSION.remove(); }
}
