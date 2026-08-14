package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reclazz writes members into the classes it transforms: an
 * {@code __reclazz$ext} array for fields added after startup, an
 * {@code __reclazz$lookup} handle, and {@code __reclazz$v0$...} copies of
 * methods it renamed. None of that is the user's code, and reflection must
 * not report it.
 *
 * This is not a tidiness rule. SAP Commerce's OCC layer builds its JAXB
 * context by walking every declared field of every DTO, with no filter on
 * synthetic or static, and adding each field's type to the set of classes it
 * hands to MOXy. With the agent attached, {@code __reclazz$lookup} put
 * {@code MethodHandles$Lookup} into that set, MOXy followed it into JDK
 * internals, and the context failed to build. Every OCC response became an
 * empty 400 for as long as the agent was attached, which on a live server
 * meant 210 failures in one day and a storefront that did not work.
 *
 * A framework cannot be expected to know to skip our members, so we do not
 * show them.
 */
class ReflectionBridgeHidesInternalsTest {

    /**
     * Field and method names here are the real ones. `$` is a legal Java
     * identifier character, so the shape the agent injects can be written
     * directly rather than simulated.
     */
    @SuppressWarnings("unused")
    static class Transformed {
        private String message;
        public Object[] __reclazz$ext;
        public static java.lang.invoke.MethodHandles.Lookup __reclazz$lookup;

        public String getMessage() { return message; }
        public void setMessage(String m) { this.message = m; }
        public String __reclazz$v0$getMessage$1a2b() { return message; }
    }

    /** A class the agent never touched: nothing to strip, nothing to change. */
    @SuppressWarnings("unused")
    static class Untouched {
        private String name;
        public String getName() { return name; }
    }

    private static java.util.List<String> names(Field[] fields) {
        return Arrays.stream(fields).map(Field::getName).toList();
    }

    private static java.util.List<String> names(Method[] methods) {
        return Arrays.stream(methods).map(Method::getName).toList();
    }

    // ── The members that broke OCC ────────────────────────────────────────

    @Test
    void declaredFieldsDoNotLeakTheInjectedOnes() {
        var visible = names(ReflectionBridge.getDeclaredFields(Transformed.class));

        assertTrue(visible.contains("message"), "the user's own field must survive");
        assertFalse(visible.contains("__reclazz$ext"),
                "__reclazz$ext is an Object[] the agent adds; a field walker maps its type");
        assertFalse(visible.contains("__reclazz$lookup"),
                "__reclazz$lookup is what dragged MethodHandles$Lookup into the JAXB "
                + "class set and broke every OCC response");
    }

    @Test
    void declaredMethodsDoNotLeakRenamedOriginals() {
        var visible = names(ReflectionBridge.getDeclaredMethods(Transformed.class));

        assertTrue(visible.contains("getMessage"), "the user's own method must survive");
        assertFalse(visible.stream().anyMatch(n -> n.startsWith("__reclazz$")),
                "renamed originals are an implementation detail of the trampoline; "
                + "a framework scanning methods would treat one as a property");
    }

    @Test
    void thePublicVariantsHideThemToo() {
        assertFalse(names(ReflectionBridge.getFields(Transformed.class)).stream()
                        .anyMatch(n -> n.startsWith("__reclazz$")),
                "getFields is the variant Jackson and bean introspectors favour");
        assertFalse(names(ReflectionBridge.getMethods(Transformed.class)).stream()
                        .anyMatch(n -> n.startsWith("__reclazz$")),
                "getMethods reaches the same members by another door");
    }

    /**
     * Hiding a member from enumeration but handing it over when asked by name
     * would leave the same hole open, only one call further along.
     */
    @Test
    void lookupByNameBehavesAsIfTheyDoNotExist() {
        assertThrows(NoSuchFieldException.class,
                () -> ReflectionBridge.getDeclaredField(Transformed.class, "__reclazz$ext"));
        assertThrows(NoSuchFieldException.class,
                () -> ReflectionBridge.getField(Transformed.class, "__reclazz$lookup"));
        assertThrows(NoSuchMethodException.class,
                () -> ReflectionBridge.getDeclaredMethod(
                        Transformed.class, "__reclazz$v0$getMessage$1a2b"));
        assertThrows(NoSuchMethodException.class,
                () -> ReflectionBridge.getMethod(
                        Transformed.class, "__reclazz$v0$getMessage$1a2b"));
    }

    // ── What must not change ──────────────────────────────────────────────

    @Test
    void aClassWithNothingToStripIsUntouched() {
        Field[] direct = Untouched.class.getDeclaredFields();
        Field[] viaBridge = ReflectionBridge.getDeclaredFields(Untouched.class);

        assertEquals(names(direct), names(viaBridge),
                "the bridge must be transparent for classes the agent never wrote to");
    }

    /**
     * Reflection over members is hot. Copying an array on every call, for the
     * many classes that have nothing to hide, would be a real cost for no
     * benefit, so the unchanged case returns the array it was given.
     */
    @Test
    void theUnchangedCaseDoesNotCopy() {
        Field[] source = Untouched.class.getDeclaredFields();
        assertSame(source, callHide(source),
                "nothing to strip should mean no allocation");
    }

    private static Field[] callHide(Field[] fields) {
        try {
            var m = ReflectionBridge.class.getDeclaredMethod("hideInternal", Field[].class);
            m.setAccessible(true);
            return (Field[]) m.invoke(null, (Object) fields);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("hideInternal(Field[]) is the filter under test", e);
        }
    }

    @Test
    void theNameCheckIsAnchoredNotASubstring() {
        assertTrue(ReflectionBridge.isInternal("__reclazz$ext"));
        assertFalse(ReflectionBridge.isInternal("myField__reclazz$ext"),
                "a user field that merely contains the prefix is the user's field");
        assertFalse(ReflectionBridge.isInternal("reclazz$ext"));
        assertFalse(ReflectionBridge.isInternal("message"));
    }
}
