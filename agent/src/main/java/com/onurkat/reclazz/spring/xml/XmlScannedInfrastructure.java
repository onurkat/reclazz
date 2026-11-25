/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import static com.onurkat.reclazz.spring.xml.SapPopulatorDirectives.call;

/** Identifies the two registration artifacts of a native Spring component scan. */
final class XmlScannedInfrastructure {
    private static final String SCANNED = "org.springframework.context.annotation.ScannedGenericBeanDefinition";
    private static final String CONFIGURATION = "org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass";
    private static final String PROCESSOR = "org.springframework.context.annotation.internalCommonAnnotationProcessor";

    private XmlScannedInfrastructure() { }

    static boolean unchanged(Object factory, Object temporary, String name, Object previous, Object candidate, Path xml) {
        if (previous == null || (!PROCESSOR.equals(name) && !SCANNED.equals(candidate.getClass().getName()))) return false;
        try {
            if (!Set.copyOf(Arrays.asList((String[]) call(factory, "getAliases", name)))
                    .equals(Set.copyOf(Arrays.asList((String[]) call(temporary, "getAliases", name))))) return false;
            if (SCANNED.equals(candidate.getClass().getName()) && candidate.getClass() == previous.getClass())
                return sameScanned(previous, candidate);
            if (!PROCESSOR.equals(name) || !candidate.getClass().getName().equals("org.springframework.beans.factory.support.RootBeanDefinition")
                    || call(candidate, "getResource") != null || call(previous, "getResource") == null
                    || call(previous, "getInstanceSupplier") != null || call(candidate, "getInstanceSupplier") != null)
                return false;
            String origin = (String) call(call(previous, "getResource"), "getFilename");
            if (origin == null || origin.equals(xml.getFileName().toString())) return false;
            // The namespace supplies a fallback only when the registry contains no processor.
            // An explicit bean from this edit has its own XML resource; it must still be checked.
            ClassLoader loader = (ClassLoader) call(factory, "getBeanClassLoader");
            Object probe = SpringReflection.newTempBeanFactory(loader);
            Class<?> registry = Class.forName("org.springframework.beans.factory.support.BeanDefinitionRegistry", false, loader);
            var register = Class.forName("org.springframework.context.annotation.AnnotationConfigUtils", false, loader)
                    .getMethod("registerAnnotationConfigProcessors", registry);
            register.invoke(null, probe);
            Object standard = SpringReflection.getBeanDefinition(probe, name);
            if (standard == null) return false;
            var comparator = new XmlRawValueComparator(candidate.getClass().getClassLoader());
            if (!comparator.verifiedStandardDefinition(standard).equals(comparator.verifiedStandardDefinition(candidate))) return false;
            // Verify the live declaration has the expected processor class. Its own external XML
            // may configure additional properties: native fallback registration leaves it alone.
            return SpringReflection.getBeanClassName(standard).equals(SpringReflection.getBeanClassName(previous));
        } catch (Exception | LinkageError unavailable) {
            return false;
        }
    }

    private static boolean sameScanned(Object previous, Object candidate) throws Exception {
        if (call(previous, "getInstanceSupplier") != null || call(candidate, "getInstanceSupplier") != null
                || call(previous, "getFactoryMethodMetadata") != null || call(candidate, "getFactoryMethodMetadata") != null)
            return false;
        Object res = call(previous, "getResource");
        if (res == null || !res.equals(call(candidate, "getResource"))) return false;
        String type = SpringReflection.getBeanClassName(candidate);
        if (type == null || !type.equals(SpringReflection.getBeanClassName(previous))) return false;
        ClassLoader loader = candidate.getClass().getClassLoader();
        Class<?> metadata = Class.forName("org.springframework.core.type.AnnotationMetadata", false, loader);
        var getClassName = Class.forName("org.springframework.core.type.ClassMetadata", false, loader).getMethod("getClassName");
        Object before = call(previous, "getMetadata"), after = call(candidate, "getMetadata");
        if (!metadata.isInstance(before) || before.getClass() != after.getClass()
                || !type.equals(getClassName.invoke(before)) || !type.equals(getClassName.invoke(after))) return false;
        Class<?> annotated = Class.forName("org.springframework.core.type.AnnotatedTypeMetadata", false, loader);
        var attributes = annotated.getMethod("getAllAnnotationAttributes", String.class, boolean.class);
        var types = metadata.getMethod("getAnnotationTypes");
        Set<?> annotations = (Set<?>) types.invoke(before);
        if (!annotations.equals(types.invoke(after))) return false;
        for (Object annotation : annotations) {
            if (!java.util.Objects.deepEquals(attributes.invoke(before, annotation, true),
                    attributes.invoke(after, annotation, true))) return false;
        }
        Class<?> bd = Class.forName("org.springframework.beans.factory.config.BeanDefinition", false, loader);
        var copy = Class.forName("org.springframework.beans.factory.support.GenericBeanDefinition", false, loader).getConstructor(bd);
        Object live = copy.newInstance(previous), parsed = copy.newInstance(candidate);
        if ("lite".equals(call(live, "getAttribute", CONFIGURATION)) && call(parsed, "getAttribute", CONFIGURATION) == null)
            call(live, "removeAttribute", CONFIGURATION);
        var comparator = new XmlRawValueComparator(loader);
        return comparator.verifiedStandardDefinition(live).equals(comparator.verifiedStandardDefinition(parsed));
    }
}
