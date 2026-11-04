/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The "Localize types" contract as SAP Commerce 2211's JaloBasedTypeLocalization
 * implements it, for the keys of one file: lower-cased keys, per language the
 * merged value when it is not blank, added to the names already stored, inside
 * one transaction. The stubs carry the platform members the writer calls.
 */
class HybrisStoredTypeNamesTest {

    @TempDir Path dir;
    private URLClassLoader loader;

    @BeforeEach
    void build() throws Exception {
        stub("de.hybris.platform.core.Registry", """
                package de.hybris.platform.core;
                public class Registry {
                    public static boolean hasCurrentTenant() { return true; }
                    public static void activateMasterTenant() { }
                }
                """);
        stub("de.hybris.bootstrap.util.LocaleHelper", """
                package de.hybris.bootstrap.util;
                public class LocaleHelper { public static java.util.Locale getPersistenceLocale() { return java.util.Locale.ENGLISH; } }
                """);
        stub("de.hybris.platform.jalo.c2l.Language", """
                package de.hybris.platform.jalo.c2l;
                public class Language {
                    private final String iso;
                    public Language(String iso) { this.iso = iso; }
                    public String getIsoCode() { return iso; }
                    @Override public String toString() { return iso; }
                }
                """);
        stub("de.hybris.platform.util.localization.TypeLocalization", """
                package de.hybris.platform.util.localization;
                public final class TypeLocalization {
                    public static final java.util.Map<Object, java.util.Properties> MERGED = new java.util.LinkedHashMap<>();
                    public static int fullRuns;
                    private static final TypeLocalization INSTANCE = new TypeLocalization();
                    public static TypeLocalization getInstance() { return INSTANCE; }
                    public java.util.Map<Object, java.util.Properties> getLocalizations() { return MERGED; }
                    public void localizeTypes() { fullRuns++; }
                    public void clearLocalizationCache() { }
                }
                """);
        stub("de.hybris.platform.jalo.JaloItemNotFoundException", """
                package de.hybris.platform.jalo;
                public class JaloItemNotFoundException extends RuntimeException {
                    public JaloItemNotFoundException(String message) { super(message); }
                }
                """);
        stub("de.hybris.platform.jalo.SessionContext", "package de.hybris.platform.jalo; public class SessionContext {}");
        stub("de.hybris.platform.jalo.JaloSession", """
                package de.hybris.platform.jalo;
                public class JaloSession {
                    private static final JaloSession CURRENT = new JaloSession();
                    private final SessionContext context = new SessionContext();
                    public static JaloSession getCurrentSession() { return CURRENT; }
                    public SessionContext getSessionContext() { return context; }
                }
                """);
        stub("de.hybris.platform.tx.Transaction", """
                package de.hybris.platform.tx;
                public class Transaction {
                    public static final Transaction CURRENT = new Transaction();
                    public int begun, committed, rolledBack;
                    public static Transaction current() { return CURRENT; }
                    public void begin() { begun++; }
                    public void commit() { committed++; }
                    public void rollback() { rolledBack++; }
                }
                """);
        stub("de.hybris.platform.jalo.type.Type", """
                package de.hybris.platform.jalo.type;
                public class Type {
                    private final String code;
                    private java.util.Map names = new java.util.HashMap(), descriptions = new java.util.HashMap();
                    public int writes;
                    public Type(String code) { this.code = code; }
                    public String getCode() { return code; }
                    public java.util.Map getAllNames() { return new java.util.HashMap(names); }
                    public void setAllNames(java.util.Map value) { names = new java.util.HashMap(value); writes++; }
                    public java.util.Map getAllDescriptions() { return new java.util.HashMap(descriptions); }
                    public void setAllDescriptions(java.util.Map value) { descriptions = new java.util.HashMap(value); writes++; }
                }
                """);
        stub("de.hybris.platform.jalo.type.AttributeDescriptor", """
                package de.hybris.platform.jalo.type;
                public class AttributeDescriptor extends Type {
                    public AttributeDescriptor(String qualifier) { super(qualifier); }
                }
                """);
        stub("de.hybris.platform.jalo.type.ComposedType", """
                package de.hybris.platform.jalo.type;
                public class ComposedType extends Type {
                    public final java.util.Map<String, AttributeDescriptor> attributes = new java.util.HashMap<>();
                    public ComposedType(String code) { super(code); }
                    public AttributeDescriptor getAttributeDescriptorIncludingPrivate(String qualifier) {
                        AttributeDescriptor found = attributes.get(qualifier.toLowerCase());
                        if (found == null) throw new de.hybris.platform.jalo.JaloItemNotFoundException(qualifier);
                        return found;
                    }
                }
                """);
        stub("de.hybris.platform.jalo.enumeration.EnumerationValue", """
                package de.hybris.platform.jalo.enumeration;
                public class EnumerationValue {
                    private final String code;
                    private java.util.Map names = new java.util.HashMap();
                    public EnumerationValue(String code) { this.code = code; }
                    public String getCode() { return code; }
                    public java.util.Map getAllNames(de.hybris.platform.jalo.SessionContext context) {
                        if (context == null) throw new IllegalArgumentException("session context");
                        return new java.util.HashMap(names);
                    }
                    public void setAllNames(de.hybris.platform.jalo.SessionContext context, java.util.Map value) {
                        if (context == null) throw new IllegalArgumentException("session context");
                        names = new java.util.HashMap(value);
                    }
                }
                """);
        stub("de.hybris.platform.jalo.enumeration.EnumerationType", """
                package de.hybris.platform.jalo.enumeration;
                public class EnumerationType extends de.hybris.platform.jalo.type.ComposedType {
                    public final java.util.List<EnumerationValue> values = new java.util.ArrayList<>();
                    public EnumerationType(String code) { super(code); }
                    public java.util.List<EnumerationValue> getValues() { return values; }
                }
                """);
        stub("de.hybris.platform.jalo.type.TypeManager", """
                package de.hybris.platform.jalo.type;
                public class TypeManager {
                    public static final java.util.Map<String, Type> TYPES = new java.util.HashMap<>();
                    public static RuntimeException failure;
                    private static final TypeManager INSTANCE = new TypeManager();
                    public static TypeManager getInstance() { return INSTANCE; }
                    public Type getType(String code) {
                        if (failure != null) throw failure;
                        Type found = TYPES.get(code.toLowerCase());
                        if (found == null) throw new de.hybris.platform.jalo.JaloItemNotFoundException(code);
                        return found;
                    }
                }
                """);
        compile();
        loader = new URLClassLoader(new java.net.URL[]{dir.resolve("classes").toUri().toURL()}, getClass().getClassLoader());
    }

    @AfterEach
    void close() throws Exception {
        if (loader != null) loader.close();
    }

    @Test
    void onlyTheKeysInTheSavedFileAreWrittenAddedToTheStoredNames() throws Exception {
        Object en = language("en"), de = language("de"), fr = language("fr");
        Object title = composed("Title");
        Object code = attribute(title, "code");
        Object status = enumeration("CronJobStatus");
        Object paused = value(status, "PAUSED");
        Object untouched = composed("Product");
        call(title, "setAllNames", Map.class, Map.of(en, "Title", fr, "Titre"));
        merged(en, "type.title.name", "Title Reclazz", "type.title.code.name", "Token Reclazz",
                "type.cronjobstatus.paused.name", "PAUSED Reclazz", "type.title.description", " ",
                "type.product.name", "Product");
        merged(de, "type.title.name", "Titel Reclazz");
        Path file = file("type.Title.name=Title Reclazz", "type.Title.code.name=Token Reclazz",
                "type.CronJobStatus.PAUSED.name=PAUSED Reclazz", "type.Title.description=",
                "type.NoSuchType.name=ignored", "not.a.type.key=x");

        assertEquals(3, reloader().localizeStoredTypeNames(file));

        assertEquals(Map.of(en, "Title Reclazz", de, "Titel Reclazz", fr, "Titre"), get(title, "getAllNames"),
                "per language, added to what is stored; a language the files do not name keeps its value");
        assertEquals(Map.of(en, "Token Reclazz"), get(code, "getAllNames"));
        assertEquals(Map.of(en, "PAUSED Reclazz"), enumNames(paused));
        assertEquals(Map.of(), get(title, "getAllDescriptions"), "a blank value is not written");
        assertEquals(0, field(untouched, "writes"), "a type keyed only in another file is left alone");
        assertEquals(0, staticField("de.hybris.platform.util.localization.TypeLocalization", "fullRuns"));
        Object tx = staticField("de.hybris.platform.tx.Transaction", "CURRENT");
        assertEquals(1, field(tx, "begun"));
        assertEquals(1, field(tx, "committed"));

        assertEquals(0, reloader().localizeStoredTypeNames(file), "nothing differs on a second save");
    }

    @Test
    void aFailureRollsBackAndAsksForTheFullStep() throws Exception {
        Object en = language("en");
        composed("Title");
        merged(en, "type.title.name", "Title Reclazz");
        loader.loadClass("de.hybris.platform.jalo.type.TypeManager").getField("failure")
                .set(null, new IllegalStateException("type system unavailable"));

        assertEquals(-1, reloader().localizeStoredTypeNames(file("type.Title.name=Title Reclazz")));

        Object tx = staticField("de.hybris.platform.tx.Transaction", "CURRENT");
        assertEquals(1, field(tx, "rolledBack"));
        assertEquals(0, field(tx, "committed"));
    }

    private HybrisLocalizationReloader reloader() {
        return new HybrisLocalizationReloader(loader, null);
    }

    private Path file(String... lines) throws Exception {
        Path file = dir.resolve("reclazztest-locales_en.properties");
        Files.write(file, List.of(lines));
        return file;
    }

    private Object language(String iso) throws Exception {
        return loader.loadClass("de.hybris.platform.jalo.c2l.Language").getConstructor(String.class).newInstance(iso);
    }

    @SuppressWarnings("unchecked")
    private void merged(Object language, String... pairs) throws Exception {
        var merged = (Map<Object, Properties>) staticField("de.hybris.platform.util.localization.TypeLocalization", "MERGED");
        Properties properties = merged.computeIfAbsent(language, ignored -> new Properties());
        for (int i = 0; i < pairs.length; i += 2) properties.setProperty(pairs[i], pairs[i + 1]);
    }

    @SuppressWarnings("unchecked")
    private Object composed(String code) throws Exception {
        return register(code, loader.loadClass("de.hybris.platform.jalo.type.ComposedType")
                .getConstructor(String.class).newInstance(code));
    }

    private Object enumeration(String code) throws Exception {
        return register(code, loader.loadClass("de.hybris.platform.jalo.enumeration.EnumerationType")
                .getConstructor(String.class).newInstance(code));
    }

    @SuppressWarnings("unchecked")
    private Object register(String code, Object type) throws Exception {
        ((Map<String, Object>) staticField("de.hybris.platform.jalo.type.TypeManager", "TYPES")).put(code.toLowerCase(), type);
        return type;
    }

    @SuppressWarnings("unchecked")
    private Object attribute(Object type, String qualifier) throws Exception {
        Object descriptor = loader.loadClass("de.hybris.platform.jalo.type.AttributeDescriptor")
                .getConstructor(String.class).newInstance(qualifier);
        ((Map<String, Object>) type.getClass().getField("attributes").get(type)).put(qualifier.toLowerCase(), descriptor);
        return descriptor;
    }

    @SuppressWarnings("unchecked")
    private Object value(Object enumeration, String code) throws Exception {
        Object value = loader.loadClass("de.hybris.platform.jalo.enumeration.EnumerationValue")
                .getConstructor(String.class).newInstance(code);
        ((List<Object>) enumeration.getClass().getField("values").get(enumeration)).add(value);
        return value;
    }

    private Object enumNames(Object value) throws Exception {
        Class<?> contextType = loader.loadClass("de.hybris.platform.jalo.SessionContext");
        Object session = loader.loadClass("de.hybris.platform.jalo.JaloSession").getMethod("getCurrentSession").invoke(null);
        Object context = session.getClass().getMethod("getSessionContext").invoke(session);
        return value.getClass().getMethod("getAllNames", contextType).invoke(value, context);
    }

    private static Object get(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static void call(Object target, String method, Class<?> type, Object value) throws Exception {
        target.getClass().getMethod(method, type).invoke(target, value);
    }

    private static Object field(Object target, String name) throws Exception {
        return target.getClass().getField(name).get(target);
    }

    private Object staticField(String className, String name) throws Exception {
        return loader.loadClass(className).getField(name).get(null);
    }

    private final List<Path> sources = new ArrayList<>();

    private void stub(String className, String source) throws Exception {
        Path file = dir.resolve("src").resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        sources.add(file);
    }

    private void compile() {
        List<String> args = new ArrayList<>(List.of("-proc:none", "-nowarn", "-Xlint:none", "-d", dir.resolve("classes").toString()));
        sources.forEach(source -> args.add(source.toString()));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)),
                "SDK contract stubs compile");
    }
}
