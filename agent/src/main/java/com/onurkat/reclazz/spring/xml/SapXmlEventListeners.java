/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Narrow new SAP listener creation; native Spring remains the only publisher. */
final class SapXmlEventListeners {
    private static final String BASE = "de.hybris.platform.servicelayer.event.impl.AbstractEventListener";
    private static final String DETECTOR = "org.springframework.context.support.ApplicationListenerDetector";
    private SapXmlEventListeners() { }

    record Admission(Object owner, Object factory, Object definition, Class<?> type) { }

    static Map<String, Admission> inspect(Object owner, Object factory, Object parsed, Path path) {
        Map<String, Admission> result = new HashMap<>();
        for (String name : SpringReflection.getBeanDefinitionNames(parsed)) {
            try {
                if (SpringReflection.containsBeanDefinition(factory, name) || singleton(factory, name) != null) continue;
                Object bd = SpringReflection.getBeanDefinition(parsed, name);
                if (!XmlBeanRecreator.owns(bd, path)) continue;
                if (!Set.of("org.springframework.beans.factory.support.GenericBeanDefinition",
                        "org.springframework.beans.factory.support.RootBeanDefinition").contains(bd.getClass().getName())) continue;
                if (!(Boolean) call(bd, "isSingleton") || (Boolean) call(bd, "isAbstract")
                        || call(bd, "getParentName") != null || (Integer) call(bd, "getRole") != 0
                        || (Boolean) call(bd, "isSynthetic") || (Integer) call(bd, "getAutowireMode") != 0
                        || call(bd, "getDependsOn") != null || call(bd, "getInstanceSupplier") != null
                        || !(Boolean) call(call(bd, "getMethodOverrides"), "isEmpty")
                        || XmlBeanRecreator.handles(null, bd)) continue;
                ClassLoader loader = (ClassLoader) call(factory, "getBeanClassLoader");
                String className = SpringReflection.getBeanClassName(bd);
                Class<?> type = Class.forName(className, false, loader);
                Class<?> nativeBase = Class.forName(BASE, false, loader);
                if (type.getSuperclass() != nativeBase || !Modifier.isPublic(type.getModifiers())
                        || Modifier.isAbstract(type.getModifiers()) || type.getInterfaces().length != 0
                        || !plainConstructor(type)) continue;
                // Inherited callbacks only; extra methods/annotations/field initialization
                // are rejected by the bytecode check rather than guessed safe.
                if (type.getMethod("afterPropertiesSet").getDeclaringClass() != nativeBase) continue;
                Set<String> properties = new HashSet<>();
                boolean services = true;
                for (Object pv : SpringReflection.getPropertyValueArray(SpringReflection.getPropertyValues(bd))) {
                    String property = SpringReflection.getPropertyName(pv);
                    if (!Set.of("tenantService", "clusterService").contains(property)) { services = false; break; }
                    Object value = SpringReflection.getPropertyValue(pv);
                    if (!value.getClass().getName().equals("org.springframework.beans.factory.config.RuntimeBeanReference")
                            || (Boolean) call(value, "isToParent")) { services = false; break; }
                    String reference = (String) call(value, "getBeanName");
                    if (!reference.equals(property)) { services = false; break; }
                    Object service = singleton(factory, reference);
                    String contract = property.equals("tenantService")
                            ? "de.hybris.platform.servicelayer.tenant.TenantService"
                            : "de.hybris.platform.servicelayer.cluster.ClusterService";
                    if (service == null || !Class.forName(contract, false, loader).isInstance(service)) { services = false; break; }
                    properties.add(property);
                }
                if (!services || !properties.equals(Set.of("tenantService", "clusterService"))) continue;
                // Verify rollback/publication APIs without resolving any listener bean.
                processors(factory);
                Class<?> listener = Class.forName("org.springframework.context.ApplicationListener", false, factory.getClass().getClassLoader());
                owner.getClass().getMethod("removeApplicationListener", listener);
                result.put(name, new Admission(owner, factory, bd, type));
            } catch (Exception | LinkageError unavailable) {
                // Keep the original lifecycle refusal when any evidence is unavailable.
            }
        }
        return result;
    }

    private static Object singleton(Object factory, String name) throws Exception {
        String canonical = (String) factory.getClass().getMethod("canonicalName", String.class).invoke(factory, name);
        return factory.getClass().getMethod("getSingleton", String.class).invoke(factory, canonical);
    }

    private static boolean plainConstructor(Class<?> type) throws Exception {
        type.getConstructor();
        try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (stream == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!node.name.equals(type.getName().replace('.', '/')) || !node.fields.isEmpty()
                    || (node.visibleAnnotations != null && !node.visibleAnnotations.isEmpty())) return false;
            int constructors = 0;
            for (MethodNode method : node.methods) {
                if (method.visibleAnnotations != null && !method.visibleAnnotations.isEmpty()) return false;
                if (method.name.equals("<init>")) {
                    if (!method.desc.equals("()V") || method.tryCatchBlocks.size() != 0) return false;
                    List<AbstractInsnNode> code = new ArrayList<>();
                    for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
                    if (code.size() != 3 || !(code.get(0) instanceof VarInsnNode load)
                            || load.getOpcode() != Opcodes.ALOAD || load.var != 0
                            || !(code.get(1) instanceof MethodInsnNode init)
                            || init.getOpcode() != Opcodes.INVOKESPECIAL || !init.owner.equals(node.superName)
                            || !init.name.equals("<init>") || !init.desc.equals("()V")
                            || code.get(2).getOpcode() != Opcodes.RETURN) return false;
                    constructors++;
                } else if (!method.name.equals("onEvent") || (method.access & Opcodes.ACC_STATIC) != 0) return false;
            }
            return constructors == 1;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> processors(Object factory) throws Exception {
        Object value = call(factory, "getBeanPostProcessors");
        if (!(value instanceof List<?> list) || list.stream().filter(p -> p.getClass().getName().equals(DETECTOR)).count() != 1)
            throw new IllegalStateException("native listener detector unavailable");
        return (List<Object>) list;
    }

    static void create(String name, Admission admission, com.onurkat.reclazz.platform.XmlBeanLifecycle lifecycle) throws Exception {
        Object factory = admission.factory(), owner = admission.owner();
        if (SpringReflection.containsBeanDefinition(factory, name) || singleton(factory, name) != null)
            throw new IllegalStateException("new listener name is no longer absent");
        List<Object> processors = processors(factory);
        ClassLoader springLoader = factory.getClass().getClassLoader();
        Class<?> processorType = Class.forName("org.springframework.beans.factory.config.BeanPostProcessor", false, springLoader);
        Class<?> listenerType = Class.forName("org.springframework.context.ApplicationListener", false, springLoader);
        AtomicReference<Object> captured = new AtomicReference<>();
        Object capture = Proxy.newProxyInstance(springLoader, new Class<?>[]{processorType}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "postProcessAfterInitialization" -> {
                    if (name.equals(args[1])) {
                        if (!listenerType.isInstance(args[0]) || !captured.compareAndSet(null, args[0]))
                            throw new IllegalStateException("listener publication shape changed");
                    }
                    yield args[0];
                }
                case "postProcessBeforeInitialization" -> args[0];
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Reclazz new XML listener capture";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        int position = 0;
        while (!processors.get(position).getClass().getName().equals(DETECTOR)) position++;
        boolean introduced = false;
        try {
            processors.add(position, capture);
            if (processors.get(position) != capture || !processors.get(position + 1).getClass().getName().equals(DETECTOR))
                throw new IllegalStateException("native listener publication boundary changed");
            SpringReflection.registerBeanDefinition(factory, name, admission.definition());
            introduced = true;
            var pending = lifecycle.before(owner, name, null);
            Object created = null;
            try {
                created = SpringReflection.getBean(owner, name);
                if (created == null || created.getClass() != admission.type() || captured.get() != created)
                    throw new IllegalStateException("proxied or changed listener construction requires restart");
            } catch (Exception | LinkageError failure) {
                pending.complete(null, false);
                throw failure;
            }
            pending.complete(created, true);
        } catch (Exception | LinkageError failure) {
            if (introduced) {
                Object published = captured.get();
                if (published != null) {
                    try { owner.getClass().getMethod("removeApplicationListener", listenerType).invoke(owner, published); }
                    catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                }
                SpringReflection.destroySingleton(factory, name);
                SpringReflection.removeBeanDefinition(factory, name);
            }
            throw failure;
        } finally {
            processors.remove(capture);
        }
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }
}
