package org.zkoss.util;

import java.util.Locale;

/** Test stand-in for ZK's per-thread locale, which the label reload sets before each lookup. */
public final class Locales {
    private static final ThreadLocal<Locale> CURRENT = new ThreadLocal<>();

    private Locales() { }

    public static Locale setThreadLocal(Locale locale) {
        Locale previous = CURRENT.get();
        CURRENT.set(locale);
        return previous;
    }

    public static Locale getThreadLocal() {
        return CURRENT.get();
    }
}
