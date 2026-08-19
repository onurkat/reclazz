/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * A persistent field added to or removed from a JPA entity by a reload.
 *
 * <p>Reloading the entity class works, and that is the trap. The class gains
 * the field, so the source and the debugger agree it is there, and everything
 * downstream disagrees silently: Hibernate builds its metamodel and its entity
 * persisters once, when the SessionFactory is created, and nothing about a
 * class redefinition tells it to look again. Measured on Spring Boot 3.3 with
 * Hibernate, on JetBrains Runtime with enhanced class redefinition, which is
 * the most capable configuration there is:
 *
 * <pre>
 *   [SWAP] Reloaded demo.Order (79ms)
 *   hibernate metamodel = [code, id]        class fields = [code, currency, id]
 *   ORDERS columns      = [ID, CODE]
 *   write "EUR", flush, clear, read back    -&gt;  null
 * </pre>
 *
 * <p>So the value is not saved and not loaded, and the reload said it worked.
 * The developer goes looking for the bug in their own mapping.
 *
 * <p>Reclazz does not try to fix this, on purpose. Refreshing the mapping means
 * rebuilding the SessionFactory, which takes the persistence context, the open
 * transactions and every repository proxy with it, and that is a restart of the
 * persistence layer wearing a different name. Worse, it would only be half a
 * fix: the database column does not exist either, and on a project with
 * {@code ddl-auto} set to validate or none it never will. A refreshed mapping
 * over a table without the column turns a working application into one that
 * fails on the next query. Not applying it and saying so leaves the developer
 * with a running server and an accurate picture, which is the same trade
 * Reclazz already makes on SAP Commerce, where a new items.xml attribute
 * reloads the model class and prints a reminder to run Update Running System
 * rather than writing to the database itself.
 */
public final class JpaEntityChange {

    private static final Set<String> MAPPED_TYPE_ANNOTATIONS = Set.of(
            "Ljakarta/persistence/Entity;", "Ljavax/persistence/Entity;",
            "Ljakarta/persistence/Embeddable;", "Ljavax/persistence/Embeddable;",
            "Ljakarta/persistence/MappedSuperclass;", "Ljavax/persistence/MappedSuperclass;");

    private static final Set<String> TRANSIENT_ANNOTATIONS = Set.of(
            "Ljakarta/persistence/Transient;", "Ljavax/persistence/Transient;");

    /** Members Reclazz injects. They are ours, and no mapping should see them. */
    private static final String INJECTED_PREFIX = "__reclazz$";

    private JpaEntityChange() {
    }

    /** What a reload did to a mapped class, or null when it did nothing of note. */
    public record Change(List<String> added, List<String> removed) {

        /** Reads as the sentence it becomes, so the caller does not assemble one. */
        public String describe() {
            StringBuilder out = new StringBuilder();
            if (!added.isEmpty()) out.append("gained ").append(String.join(", ", added));
            if (!removed.isEmpty()) {
                if (out.length() > 0) out.append(" and ");
                out.append("lost ").append(String.join(", ", removed));
            }
            return out.toString();
        }
    }

    /**
     * Check and say so, which is all any caller wants.
     *
     * <p>Both reload engines call this: the companion engine on a stock JVM and
     * the plain redefinition used on JetBrains Runtime. The gap is the same on
     * either, because it is Hibernate's, not the JVM's.
     */
    public static void reportIfChanged(String className, Class<?> loaded, byte[] newBytecode) {
        report(className, loaded, check(loaded, newBytecode));
    }

    /**
     * Say what a previously taken {@link #check} found.
     *
     * <p>Split from the check because of where each half has to happen. On an
     * enhanced-redefinition VM the redefinition puts the field on the loaded
     * class, so a comparison made afterwards finds the two sides already in
     * agreement and reports nothing: exactly the configuration where the
     * warning matters most would be the one that stayed silent. So the
     * comparison is taken before the redefinition and the sentence is said
     * after it succeeds.
     */
    public static void report(String className, Class<?> entityClass, Change change) {
        if (change == null) return;

        // The one configuration where the gap can be closed instead of
        // described: opted in, enhanced redefinition, a schema action that
        // creates the column, and a Spring factory bean to swap under.
        JpaMappingRefresh.Result refresh = JpaMappingRefresh.apply(className, entityClass, change);
        if (refresh.refreshed()) return;

        // What to do next is not the same for everyone, and saying one thing to
        // all of them was wrong twice over: at hbm2ddl.auto=update a restart is
        // the whole fix, and at validate a restart stops the application from
        // starting at all until the column exists.
        com.onurkat.reclazz.ui.StatusReporter.warn(className + " " + change.describe()
                + ", and the persistence mapping still has the old shape. Hibernate builds it "
                + "once at startup, so the field is neither saved nor loaded, and the database "
                + "has no column for it. The class itself reloaded."
                + JpaSchemaAdvice.forEntity(entityClass)
                + refresh.appendix());
        com.onurkat.reclazz.agent.RestartLedger.note(className,
                change.describe() + " as a mapped field, which the persistence mapping has not picked up");
    }

    /**
     * Compare what the loaded class has against what the new bytecode declares.
     *
     * <p>The loaded class is the honest baseline on either engine. On a stock
     * JVM a field a reload added never enters it, and on an enhanced VM it
     * does; both are exactly what the mapping was built from, which is the
     * question being asked.
     *
     * @return the change, or null when this is not a mapped class or nothing
     *         persistent moved
     */
    public static Change check(Class<?> loaded, byte[] newBytecode) {
        if (loaded == null || newBytecode == null) return null;

        Scan scan;
        try {
            scan = scan(newBytecode);
        } catch (RuntimeException e) {
            return null;                       // unreadable bytes fail louder elsewhere
        }
        if (!scan.mapped) return null;

        Set<String> before = new LinkedHashSet<>();
        try {
            for (Field f : loaded.getDeclaredFields()) {
                if (isPersistent(f.getModifiers(), f.getName(), f.isSynthetic())) {
                    before.add(f.getName());
                }
            }
        } catch (Throwable t) {
            return null;                       // no baseline, nothing trustworthy to say
        }

        List<String> added = new ArrayList<>(scan.persistentFields);
        added.removeAll(before);

        List<String> removed = new ArrayList<>(before);
        removed.removeAll(scan.persistentFields);

        if (added.isEmpty() && removed.isEmpty()) return null;
        return new Change(added, removed);
    }

    /**
     * Whether a field is one the mapping would care about.
     *
     * <p>Static and {@code transient} fields are outside the mapping by the
     * specification, an explicit {@code @Transient} says so directly, and the
     * injected members are not the application's at all. Reporting any of them
     * would be a warning about a change that changes nothing, which costs more
     * trust than it saves.
     */
    private static boolean isPersistent(int modifiers, String name, boolean synthetic) {
        return !Modifier.isStatic(modifiers)
                && !Modifier.isTransient(modifiers)
                && !synthetic
                && !name.startsWith(INJECTED_PREFIX);
    }

    private record Scan(boolean mapped, List<String> persistentFields) { }

    private static Scan scan(byte[] bytecode) {
        boolean[] mapped = {false};
        List<String> fields = new ArrayList<>();

        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (MAPPED_TYPE_ANNOTATIONS.contains(descriptor)) mapped[0] = true;
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                            String signature, Object value) {
                boolean synthetic = (access & Opcodes.ACC_SYNTHETIC) != 0;
                if (!isPersistent(access, name, synthetic)) return null;

                // @Transient is only visible once the field's own annotations
                // are read, so the field is provisionally kept and withdrawn.
                fields.add(name);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDesc, boolean visible) {
                        if (TRANSIENT_ANNOTATIONS.contains(annotationDesc)) fields.remove(name);
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE);

        return new Scan(mapped[0], fields);
    }
}
