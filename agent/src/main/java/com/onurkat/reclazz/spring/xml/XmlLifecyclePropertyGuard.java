/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

/** Ordinary property updates cannot replay initialization or infrastructure registration. */
final class XmlLifecyclePropertyGuard {
    private XmlLifecyclePropertyGuard() { }

    static boolean classify(Object factory, Object parsedFactory, String name, Object previous,
                            Object candidate, BeanDefinitionDiff diff) {
        return classify(factory, parsedFactory, name, previous, candidate, diff, null);
    }

    static boolean classify(Object factory, Object parsedFactory, String name, Object previous,
                            Object candidate, BeanDefinitionDiff diff, SapXmlEventListeners.Admission admission) {
        try {
            Object instance = previous == null ? null : factory.getClass()
                    .getMethod("getSingleton", String.class).invoke(factory, name);
            // Never getBean/getType: even a type query can create or query a factory product.
            // A JDK proxy may hide the target's lifecycle interface, so retain the declared
            // merged type check even when an actual singleton is already available.
            Object definitions = previous == null ? parsedFactory : factory;
            Object merged = definitions.getClass().getMethod("getMergedBeanDefinition", String.class)
                    .invoke(definitions, name);
            Class<?> declared;
            if ((Boolean) merged.getClass().getMethod("hasBeanClass").invoke(merged)) {
                // Native metadata can hold a Class from a different loader than the
                // factory's default. Re-resolving its name would lose that identity.
                declared = (Class<?>) merged.getClass().getMethod("getBeanClass").invoke(merged);
            } else {
                String className = (String) merged.getClass().getMethod("getBeanClassName").invoke(merged);
                ClassLoader applicationLoader = (ClassLoader) factory.getClass().getMethod("getBeanClassLoader").invoke(factory);
                if (className == null) throw new IllegalArgumentException("bean class unavailable");
                declared = Class.forName(className, false, applicationLoader);
            }
            // Spring can live in a parent which cannot see application types. Resolve each
            // marker from the factory's Spring loader, not the watcher TCCL or agent loader.
            ClassLoader springLoader = factory.getClass().getClassLoader();
            String reason = null;
            for (String marker : new String[]{"org.springframework.beans.factory.config.BeanPostProcessor",
                    "org.springframework.beans.factory.config.BeanFactoryPostProcessor",
                    "org.springframework.beans.factory.InitializingBean", "org.springframework.beans.factory.DisposableBean"}) {
                if (marker.equals("org.springframework.beans.factory.InitializingBean") && previous == null
                        && admission != null && admission.factory() == factory
                        && admission.definition() == candidate && admission.type() == declared) continue;
                Class<?> contract = Class.forName(marker, false, springLoader);
                if (contract.isAssignableFrom(declared) || (instance != null && contract.isInstance(instance))) {
                    reason = marker.substring(marker.lastIndexOf('.') + 1);
                    break;
                }
            }
            if (reason == null) return false;
            if (!XmlSafetyClassifier.unchangedUnsupported(factory, parsedFactory, name, previous, candidate))
                diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                        "implements " + reason + "; lifecycle/infrastructure state was not refreshed, "
                                + "restart required, bean and definition were not updated"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                    "cannot verify XML lifecycle type safety; restart required, bean and definition were not updated: "
                            + SpringReflection.rootCause(unavailable)));
        }
        return true;
    }
}
