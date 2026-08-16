package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adding a helper method and calling it from an existing method of the same
 * class is how anyone writes code, and it was the one shape of structural
 * reload that did not work.
 *
 * The JVM will not accept a redefinition that adds a member, so an added
 * method exists only in the companion, as a static method with the receiver as
 * its first parameter. After a structural reload the original bodies are
 * trampolines and the real code runs in the companion, so the call to the new
 * helper is resolved by {@link ProtectedCallResolver}, which looked only at
 * the original class, found nothing, and failed the call with a
 * BootstrapMethodError.
 *
 * Found on a live SAP Commerce server: adding one method to a servlet filter
 * and calling it turned every request into an HTTP 500, while the agent
 * reported the reload as successful.
 *
 * This test stands in for the companion with an ordinary class, because what
 * matters is the resolution rule and not how the class was defined.
 */
class AddedMethodCallResolutionTest {

    /** The class being reloaded. It has no notion of the added method. */
    public static class Host {
        public String existing() {
            return "existing";
        }
    }

    /**
     * Stands in for the companion: method bodies as static methods, receiver
     * first, including the one this reload added.
     */
    public static class CompanionStandIn {
        /** The companion is always the class making the call. */
        static MethodHandles.Lookup lookup() {
            return MethodHandles.lookup();
        }

        public static String addedHelper(Host receiver) {
            return "added helper ran on " + receiver.existing();
        }

        public static String addedStatic() {
            return "added static ran";
        }
    }

    private static final int KIND_VIRTUAL = ProtectedCallResolver.KIND_VIRTUAL;
    private static final int KIND_STATIC = ProtectedCallResolver.KIND_STATIC;

    private static String internal(Class<?> c) {
        return c.getName().replace('.', '/');
    }

    @Test
    void aMethodAddedByTheReloadResolvesToTheCompanion() throws Throwable {
        MethodType callSiteType = MethodType.methodType(String.class, Host.class);

        CallSite site = ProtectedCallResolver.protectedCall(
                CompanionStandIn.lookup(), "addedHelper", callSiteType,
                internal(Host.class), internal(Host.class), KIND_VIRTUAL);

        assertEquals("added helper ran on existing",
                site.getTarget().invoke(new Host()),
                "the method exists only in the companion; resolving against the "
                + "original class is what failed the call");
    }

    @Test
    void anAddedStaticMethodResolvesTheSameWay() throws Throwable {
        CallSite site = ProtectedCallResolver.protectedCall(
                CompanionStandIn.lookup(), "addedStatic",
                MethodType.methodType(String.class),
                internal(Host.class), internal(Host.class), KIND_STATIC);

        assertEquals("added static ran", site.getTarget().invoke());
    }

    /**
     * A method that exists on the class still resolves there. The fallback is
     * for members the class cannot have, not a second guess at every call.
     */
    @Test
    void anOrdinaryCallStillResolvesAgainstTheClass() throws Throwable {
        CallSite site = ProtectedCallResolver.protectedCall(
                CompanionStandIn.lookup(), "existing",
                MethodType.methodType(String.class, Host.class),
                internal(Host.class), internal(Host.class), KIND_VIRTUAL);

        assertEquals("existing", site.getTarget().invoke(new Host()));
    }

    /**
     * When the method is in neither place the original failure is what
     * describes the code, so that is what the developer should see.
     */
    @Test
    void aCallToNothingReportsTheFailureAboutTheClass() {
        Throwable t = assertThrows(Throwable.class, () ->
                ProtectedCallResolver.protectedCall(
                        CompanionStandIn.lookup(), "neitherPlace",
                        MethodType.methodType(String.class, Host.class),
                        internal(Host.class), internal(Host.class), KIND_VIRTUAL));

        assertTrue(t.toString().contains("neitherPlace"),
                "the message must name the method that could not be found: " + t);
    }
}
