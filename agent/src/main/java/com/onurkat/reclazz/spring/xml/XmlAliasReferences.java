/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import static com.onurkat.reclazz.spring.xml.XmlAliasPublication.call;
import static com.onurkat.reclazz.spring.xml.XmlAliasPublication.require;

import com.onurkat.reclazz.util.Reflect;
import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

/** Inspect unresolved metadata without initializing lazy beans or invoking application setters. */
final class XmlAliasReferences {
    private static final String SUPPORT = "org.springframework.beans.factory.support.";
    private static final String ANNOTATION = "org.springframework.beans.factory.annotation.";
    private static final Set<String> RESOLVERS = Set.of(SUPPORT + "SimpleAutowireCandidateResolver",
            SUPPORT + "GenericTypeAwareAutowireCandidateResolver", ANNOTATION + "QualifierAnnotationAutowireCandidateResolver",
            "org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver");

    private static final Set<String> LOOKUPS = Set.of("getBean", "containsBean", "containsBeanDefinition",
            "isTypeMatch", "getType", "getAliases", "getBeansOfType", "getBeanNamesForType",
            "getBeanProvider", "isSingleton", "isPrototype");
    // Spring Integration 6.4.4 uses this fixed bean lookup for channel, scheduler and header defaults.
    private static final String INTEGRATION_CONTEXT = "T(org.springframework.integration.context.IntegrationContextUtils)";
    private final Set<String> checkedExpressions = new HashSet<>();
    private Object parser;
    private Object template;
    private Method parseTemplate;

    private final Object factory;
    private final String alias;
    private final ClassLoader loader;
    private final Set<Class<?>> inspected = new HashSet<>();
    private final boolean visible;
    private String bean;

    private XmlAliasReferences(Object factory, String alias, boolean visible) {
        this.factory = factory;
        this.alias = alias;
        this.visible = visible;
        this.loader = factory.getClass().getClassLoader();
    }

    static void check(Object factory, String alias) throws Exception {
        check(factory, alias, true);
    }

    static void check(Object factory, String alias, boolean visible) throws Exception {
        new XmlAliasReferences(factory, alias, visible).check();
    }

    private void check() throws Exception {
        require(RESOLVERS.contains(call(factory, "getAutowireCandidateResolver").getClass().getName()),
                "custom autowire candidate resolver");
        for (String name : (String[])call(factory, "getBeanDefinitionNames")) {
            bean = name;
            Object definition = call(factory, "getMergedBeanDefinition", name);
            definition(definition, Collections.newSetFromMap(new IdentityHashMap<>()), name);
        }
        for (String name : (String[])call(factory, "getSingletonNames")) {
            bean = name;
            Object singleton = call(factory, "getSingleton", name);
            require(singleton != null, "singleton changed while inspecting " + name);
            inspectInstance(singleton);
            if (is(singleton, "org.springframework.beans.factory.FactoryBean")) {
                Object cached = call(factory, "getCachedObjectForFactoryBean", name);
                if (cached != null) inspectInstance(cached);
            }
        }
    }

    private void inspectInstance(Object instance) throws Exception {
        Class<?> type = instance.getClass();
        try {
            Class<?> aop = Class.forName("org.springframework.aop.support.AopUtils", false, loader);
            type = (Class<?>)aop.getMethod("getTargetClass", Object.class).invoke(null, instance);
        } catch (ClassNotFoundException standaloneFactory) { /* No Spring AOP on this classpath. */ }
        inspect(type);
    }

    private void definition(Object def, Set<Object> seen, String registeredName) throws Exception {
        referenced(call(def, "getFactoryBeanName"));
        String[] depends = (String[])call(def, "getDependsOn");
        if (depends != null) for (String dependency : depends) referenced(dependency);
        Object props = call(def, "getPropertyValues");
        for (Object entry : (Object[])call(props, "getPropertyValues")) value(call(entry, "getValue"), seen);
        Object args = call(def, "getConstructorArgumentValues");
        for (Object holder : ((Map<?, ?>)call(args, "getIndexedArgumentValues")).values()) value(call(holder, "getValue"), seen);
        for (Object holder : (Collection<?>)call(args, "getGenericArgumentValues")) value(call(holder, "getValue"), seen);
        Object overrides = call(def, "getMethodOverrides");
        for (Object override : (Set<?>)call(overrides, "getOverrides")) {
            if (is(override, SUPPORT + "LookupOverride")) referenced(call(override, "getBeanName"));
            else if (is(override, SUPPORT + "ReplaceOverride")) referenced(call(override, "getMethodReplacerBeanName"));
            else throw new IllegalStateException("unknown method override on " + bean);
        }
        if (Integer.valueOf(1).equals(call(def, "getResolvedAutowireMode"))) {
            Class<?> type;
            if (registeredName != null) {
                Method getType = Reflect.findMethod(factory.getClass(), "getType", String.class, boolean.class);
                require(getType != null, "bean type inspection unavailable");
                type = (Class<?>)getType.invoke(factory, registeredName, false);
                require(type != null, "by-name property type unavailable for " + bean);
            } else if (call(def, "getFactoryMethodName") != null) {
                throw new IllegalStateException("nested by-name factory product type unavailable for " + bean);
            } else if (Boolean.TRUE.equals(call(def, "hasBeanClass"))) type = (Class<?>)call(def, "getBeanClass");
            else type = Class.forName((String)call(def, "getBeanClassName"), false,
                    (ClassLoader)call(factory, "getBeanClassLoader"));
            for (var property : Introspector.getBeanInfo(type).getPropertyDescriptors())
                if (property.getWriteMethod() != null) referenced(property.getName());
        }
        if (Reflect.findMethod(def.getClass(), "getResolvedFactoryMethod") != null) {
            Method method = (Method)call(def, "getResolvedFactoryMethod");
            if (method != null) executable(method, true);
            else if (registeredName != null && call(def, "getFactoryMethodName") != null
                    && Boolean.TRUE.equals(call(factory, "containsSingleton", registeredName)))
                throw new IllegalStateException("factory method metadata unavailable for " + bean);
        }
    }

    private void value(Object v, Set<Object> seen) throws Exception {
        if (v == null || !seen.add(v)) return;
        String config = "org.springframework.beans.factory.config.";
        if (v instanceof String s) expression(s);
        else if (is(v, config + "RuntimeBeanReference") || is(v, config + "RuntimeBeanNameReference")) {
            Object name = call(v, "getBeanName");
            if (name instanceof String s) {
                expression(s);
                String resolved = (String)call(factory, "resolveEmbeddedValue", s);
                require(resolved != null, "unresolved reference on " + bean);
                expression(resolved);
                referenced(resolved.startsWith("&") ? resolved.substring(1) : resolved);
            }
        } else if (is(v, config + "TypedStringValue")) value(call(v, "getValue"), seen);
        else if (is(v, config + "BeanDefinitionHolder")) value(call(v, "getBeanDefinition"), seen);
        else if (is(v, config + "BeanDefinition")) definition(v, seen, null);
        else if (v instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) { value(entry.getKey(), seen); value(entry.getValue(), seen); }
        } else if (v instanceof Iterable<?> items) {
            for (Object item : items) value(item, seen);
        } else if (v instanceof Object[] items) {
            for (Object item : items) value(item, seen);
        }
    }

    private boolean is(Object object, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader).isInstance(object);
    }

    private void referenced(Object name) {
        require(!alias.equals(name), "name referenced by bean " + bean);
    }

    private void expression(String text) throws Exception {
        if (!text.contains("#{") || !visible && !text.contains(alias) || !checkedExpressions.add(text)) return;
        try {
            if (parser == null) {
                Class<?> context = Class.forName("org.springframework.expression.ParserContext", false, loader);
                template = context.getField("TEMPLATE_EXPRESSION").get(null);
                parser = Class.forName("org.springframework.expression.spel.standard.SpelExpressionParser", true, loader)
                        .getConstructor().newInstance();
                parseTemplate = parser.getClass().getMethod("parseExpression", String.class, context);
            }
            inspectPart(parseTemplate.invoke(parser, text, template));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("unreadable bean expression on " + bean, failure);
        }
    }

    private void inspectPart(Object part) throws Exception {
        switch (part.getClass().getName()) {
            case "org.springframework.expression.common.CompositeStringExpression" -> {
                for (Object segment : (Object[]) call(part, "getExpressions")) inspectPart(segment);
            }
            case "org.springframework.expression.common.LiteralExpression" -> { }
            case "org.springframework.expression.spel.standard.SpelExpression" -> inspectNode(call(part, "getAST"), true);
            default -> throw new IllegalStateException("unknown bean expression on " + bean);
        }
    }

    private void inspectNode(Object node, boolean atRoot) throws Exception {
        String kind = node.getClass().getSimpleName();
        int count = (int) call(node, "getChildCount");
        if (kind.equals("CompoundExpression") && count == 4 && integrationProperty(node)) {
            referenced("integrationGlobalProperties");
            referenced("beanFactory");
            return;
        }
        boolean sensitive = switch (kind) {
            case "BeanReference" -> {
                Object name = Reflect.readField(node, "beanName");
                yield count != 0 || !(name instanceof String literal)
                        || alias.equals(literal) || ("&" + alias).equals(literal) || contextName(literal);
            }
            case "PropertyOrFieldReference" -> {
                String name = (String) call(node, "getName");
                yield contextName(name) || atRoot && alias.equals(name);
            }
            case "VariableReference" -> {
                Object name = Reflect.readField(node, "name");
                yield !(name instanceof String variable) || contextName(variable)
                        || variable.equals("root") || atRoot && variable.equals("this");
            }
            case "MethodReference" -> LOOKUPS.contains(call(node, "getName"));
            case "Indexer" -> atRoot;
            default -> false;
        };
        require(!sensitive, "bean expression on " + bean + " (" + kind + ")");
        for (int i = 0; i < count; i++) {
            Object child = node.getClass().getMethod("getChild", int.class).invoke(node, i);
            // Sections following the head use its target. Method arguments are separate expressions.
            boolean childRoot = kind.equals("CompoundExpression") ? atRoot && i == 0 : true;
            inspectNode(child, childRoot);
        }
    }

    private static boolean integrationProperty(Object chain) throws Exception {
        Method child = chain.getClass().getMethod("getChild", int.class);
        Object type = child.invoke(chain, 0);
        if (!type.getClass().getSimpleName().equals("TypeReference")
                || !INTEGRATION_CONTEXT.equals(call(type, "toStringAST"))) return false;
        Object factoryLookup = child.invoke(chain, 1);
        Object properties = child.invoke(chain, 2);
        Object read = child.invoke(chain, 3);
        if (!factoryLookup.getClass().getSimpleName().equals("MethodReference")
                || !"getIntegrationProperties(beanFactory)".equals(call(factoryLookup, "toStringAST"))
                || !properties.getClass().getSimpleName().equals("MethodReference")
                || !"toProperties()".equals(call(properties, "toStringAST"))
                || !read.getClass().getSimpleName().equals("MethodReference")
                || !"getProperty".equals(call(read, "getName")) || (int) call(read, "getChildCount") != 1) return false;
        Object key = read.getClass().getMethod("getChild", int.class).invoke(read, 0);
        return key.getClass().getSimpleName().equals("StringLiteral");
    }

    private static boolean contextName(String name) {
        return name.equals("beanFactory") || name.equals("applicationContext") || name.equals("__beanFactory");
    }

    private void inspect(Class<?> type) throws Exception {
        if (type == null || !inspected.add(type)) return;
        if (type.getClassLoader() == null) return;
        for (Field f : type.getDeclaredFields()) {
            if (annotations(f, f.getName())) referenced(f.getName());
        }
        for (Method m : type.getDeclaredMethods()) {
            String implicit = m.getName().startsWith("set") && m.getName().length() > 3
                    ? Introspector.decapitalize(m.getName().substring(3)) : m.getName();
            boolean injected = annotations(m, implicit);
            executable(m, injected || merged(m, "org.springframework.context.annotation.Bean") != null);
        }
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        for (var ctor : constructors) executable(ctor, annotations(ctor, "") || constructors.length == 1);
        inspect(type.getSuperclass());
        for (Class<?> face : type.getInterfaces()) inspect(face);
    }

    private void executable(Executable method, boolean nameSensitive) throws Exception {
        for (Parameter p : method.getParameters()) {
            annotations(p, p.getName());
            if (nameSensitive && p.isNamePresent()) referenced(p.getName());
        }
        if (!nameSensitive || method.getParameterCount() == 0) return;
        Object discoverer = call(factory, "getParameterNameDiscoverer");
        if (discoverer == null) return; // This factory cannot use discovered parameter names either.
        Class<?> kind = method instanceof Constructor ? Constructor.class : Method.class;
        Method discover = Reflect.findMethod(discoverer.getClass(), "getParameterNames", kind);
        require(discover != null, "parameter name discovery unavailable");
        String[] names = (String[])discover.invoke(discoverer, method);
        if (names != null) for (String name : names) referenced(name);
    }

    private boolean annotations(AnnotatedElement element, String implicit) throws Exception {
        if (element.getDeclaredAnnotations().length == 0) return false;
        boolean autowired = merged(element, ANNOTATION + "Autowired") != null;
        Annotation value = merged(element, ANNOTATION + "Value");
        if (value != null) expression(attribute(value, "value"));
        Annotation qualifier = merged(element, ANNOTATION + "Qualifier");
        if (qualifier != null) referenced(attribute(qualifier, "value"));
        for (String namespace : List.of("javax", "jakarta")) {
            Annotation resource = merged(element, namespace + ".annotation.Resource");
            if (resource != null) {
                String name = attribute(resource, "name");
                referenced(name.isEmpty() ? implicit : name);
            }
            Annotation named = merged(element, namespace + ".inject.Named");
            if (named != null) referenced(attribute(named, "value"));
            autowired |= merged(element, namespace + ".inject.Inject") != null;
        }
        return autowired;
    }

    private Annotation merged(AnnotatedElement element, String annotation) throws Exception {
        Class<?> annotationType;
        try { annotationType = Class.forName(annotation, false, loader); }
        catch (ClassNotFoundException absent) { return null; }
        Class<?> utils = Class.forName("org.springframework.core.annotation.AnnotatedElementUtils", false, loader);
        return (Annotation)utils.getMethod("findMergedAnnotation", AnnotatedElement.class, Class.class)
                .invoke(null, element, annotationType);
    }

    private static String attribute(Annotation annotation, String name) throws Exception {
        return (String)annotation.annotationType().getMethod(name).invoke(annotation);
    }
}
