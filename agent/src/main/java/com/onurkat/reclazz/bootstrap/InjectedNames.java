/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

/**
 * The names Reclazz writes into somebody else's class, in one place.
 *
 * <p>Two naming schemes hold the engine together. A method whose body moved
 * out is renamed to {@code __reclazz$v0$<name>$<descHash>}, and a call site
 * finds its current implementation by a key of {@code <name>:<descHash>}, or
 * {@code static:<name>:<descHash>}. The transformer writes the names; the
 * bootstrap classes read them back at dispatch time, from the bootstrap
 * classloader, hours later, with nothing checking that the two agree. When
 * they agree the reload works. When one of them drifts, a call goes to a
 * method that is not there, and the developer sees a NoSuchMethodError from
 * inside their own code.
 *
 * <p>They were spelled out by hand in five places for the renamed method and
 * six for the site key, across three packages, and the prefix itself as a
 * string literal in nineteen files. This is what they are now, and the test
 * beside it is what keeps a twentieth from appearing.
 *
 * <p>It lives in {@code bootstrap} because that is the only package both sides
 * can see: these classes are packaged into their own jar and appended to the
 * bootstrap classloader, so a bootstrap class cannot reference anything
 * outside its own package, and everything else can reference it.
 *
 * <p>The prefix is not decoration. A member without it is visible to every
 * framework that walks declared members, and one of them dragged
 * {@code MethodHandles$Lookup} into a JAXB context and turned every OCC
 * response into an empty 400 for as long as the agent was attached.
 */
public final class InjectedNames {

    /** What marks a member as ours, and therefore hidden from reflection. */
    public static final String PREFIX = "__reclazz$";

    /** Holds the per-object state added members live in. */
    public static final String EXT_FIELD = PREFIX + "ext";

    /** Holds the class's own {@code Lookup}, for defining its companion. */
    public static final String LOOKUP_FIELD = PREFIX + "lookup";

    /** The companion's copy of the static initialiser, when there is one. */
    public static final String INIT_METHOD = PREFIX + "initStatics";

    /** The opening of every renamed method body: {@code __reclazz$v0$}. */
    public static final String RENAMED_PREFIX = PREFIX + "v0$";

    private InjectedNames() {
    }

    /** Where the original body of {@code name} went. */
    public static String renamed(String name, String descHash) {
        return RENAMED_PREFIX + name + "$" + descHash;
    }

    /** How an instance call site asks for its current implementation. */
    public static String siteKey(String name, String descHash) {
        return name + ":" + descHash;
    }

    /** The same, for a static call site. */
    public static String staticSiteKey(String name, String descHash) {
        return "static:" + name + ":" + descHash;
    }

    /** Either key, chosen by the call. */
    public static String siteKey(String name, String descHash, boolean isStatic) {
        return isStatic ? staticSiteKey(name, descHash) : siteKey(name, descHash);
    }

    /** Whether a member name is one Reclazz wrote. */
    public static boolean isInjected(String memberName) {
        return memberName.startsWith(PREFIX);
    }
}
