/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.util.List;
import com.onurkat.reclazz.util.Reflect;

/**
 * Turns a brand-new component class into a live Spring bean.
 *
 * <p>Component scanning runs once, at startup. A class created after that
 * loads perfectly well from the classpath, but no scan ever sees it, so the
 * bean it declares simply never exists and the developer restarts for the one
 * thing a fresh class file should never need. And a fresh class is the one
 * place the stock-JDK wall does not stand: nothing about it lives in a
 * companion, reflection sees all of it, so the whole Spring lifecycle,
 * autowired constructors and post-processors included, just works once
 * somebody registers the definition. That is all this does.
 *
 * <p>The class is registered in the context whose classloader defines it, so
 * a web extension's controller lands in its web context and a core service in
 * the global one. A name collision is left alone and said: redefining an
 * existing bean is a different operation with different blast radius, and it
 * already has a reloader. Failures unregister the definition again, so a
 * constructor that throws leaves the context exactly as it was.
 *
 * <p>Scope, stated: any class Spring's own component scan would accept,
 * resolved once the class is loaded through the context's Spring. A direct
 * stereotype, a custom meta-annotated stereotype, and an {@code @AliasFor} bean
 * name are all read the way scanning reads them at startup. Generic type
 * resolution, inherited callbacks and non-component adapters stay with their
 * own reloaders.
 */
public final class SpringNewBeanRegistrar {

    /** What was done with the new class. */
    public enum Outcome { REGISTERED, NOT_A_COMPONENT, DECLINED }

    private final PlatformContext platformContext;
    private final SpringMvcReloader mvcReloader;

    public SpringNewBeanRegistrar(PlatformContext platformContext, SpringMvcReloader mvcReloader) {
        this.platformContext = platformContext;
        this.mvcReloader = mvcReloader;
    }

    /**
     * Register the class as a bean if its bytecode carries a stereotype.
     *
     * @param className binary name of the never-loaded class
     * @param bytecode  the compiled class, where the stereotype is read from
     */
    public Outcome registerIfComponent(String className, byte[] bytecode) {
        if (className == null || bytecode == null || className.contains("$")) {
            return Outcome.NOT_A_COMPONENT;
        }
        // Cheap gate: only a class carrying at least one class annotation can be
        // a component. The stereotype itself is resolved through Spring below,
        // once a context that can load the class is known, so a custom
        // meta-annotated stereotype is recognised too.
        if (!hasClassAnnotation(bytecode)) return Outcome.NOT_A_COMPONENT;

        List<Object> contexts = platformContext.getAllApplicationContexts();
        if (contexts.isEmpty()) return Outcome.DECLINED;

        try {
            // Resolve once through any context that can see it; the class's
            // own defining loader then picks the most specific context.
            Class<?> clazz = null;
            for (Object context : contexts) {
                try {
                    ClassLoader loader = contextClassLoader(context);
                    if (loader == null) continue;
                    clazz = Class.forName(className, false, loader);
                    break;
                } catch (Throwable notHere) {
                    // the next context may see it
                }
            }
            if (clazz == null) {
                StatusReporter.warn("New component " + className + " is not visible to any "
                        + "application context's classloader; it stays unregistered.");
                return Outcome.DECLINED;
            }

            Object home = null;
            for (Object context : contexts) {
                if (contextClassLoader(context) == clazz.getClassLoader()) {
                    home = context;
                    break;
                }
            }
            if (home == null) {
                for (Object context : contexts) {
                    try {
                        ClassLoader loader = contextClassLoader(context);
                        if (loader != null && Class.forName(className, false, loader) == clazz) {
                            home = context;
                            break;
                        }
                    } catch (Throwable notHere) {
                        // keep looking
                    }
                }
            }
            if (home == null) return Outcome.DECLINED;

            Object beanFactory = SpringBeans.getBeanFactory(home);
            ClassLoader springLoader = beanFactory.getClass().getClassLoader();
            // Resolve the stereotype and bean name through Spring's own merged
            // annotations, so a custom stereotype and an @AliasFor name are read
            // exactly as component scanning would at startup.
            Stereotype stereotype = resolveStereotype(clazz, beanFactory, springLoader);
            if (stereotype == null) return Outcome.NOT_A_COMPONENT;
            String beanName = stereotype.beanName;

            Method containsBeanDefinition = Reflect.findMethod(
                    beanFactory.getClass(), "containsBeanDefinition", String.class);
            if (containsBeanDefinition != null
                    && Boolean.TRUE.equals(containsBeanDefinition.invoke(beanFactory, beanName))) {
                StatusReporter.info("New class " + className + " matches existing bean '"
                        + beanName + "'; nothing registered, the existing bean's own "
                        + "reloader owns it.");
                return Outcome.DECLINED;
            }

            // The annotated definition Spring's own scanner would register,
            // carrying the class's merged metadata.
            Object definition = stereotype.definition;

            Method register = Reflect.findMethod(beanFactory.getClass(),
                    "registerBeanDefinition", String.class,
                    Class.forName("org.springframework.beans.factory.config.BeanDefinition",
                            true, springLoader));
            if (register == null) {
                StatusReporter.warn("New component " + className + " found, but this "
                        + "context's bean factory does not take definitions at runtime.");
                return Outcome.DECLINED;
            }
            register.invoke(beanFactory, beanName, definition);

            try {
                Object bean = home.getClass().getMethod("getBean", String.class)
                        .invoke(home, beanName);
                StatusReporter.success("New bean registered: '" + beanName + "' ("
                        + clazz.getName() + "), dependencies injected, ready to serve.");

                if (stereotype.controller) {
                    boolean mapped = mvcReloader.reloadMappings(clazz);
                    if (mapped) {
                        StatusReporter.success("Spring MVC mappings registered for the new "
                                + "controller " + clazz.getSimpleName() + ".");
                    }
                }
                return bean != null ? Outcome.REGISTERED : Outcome.DECLINED;
            } catch (Throwable creation) {
                // The context goes back to exactly what it was.
                Method remove = Reflect.findMethod(beanFactory.getClass(),
                        "removeBeanDefinition", String.class);
                if (remove != null) {
                    try {
                        remove.invoke(beanFactory, beanName);
                    } catch (Throwable ignored) {
                    }
                }
                StatusReporter.warn("New bean '" + beanName + "' could not be created ("
                        + rootCause(creation) + "); the definition was removed again and "
                        + "the context is unchanged.");
                return Outcome.DECLINED;
            }
        } catch (Throwable t) {
            StatusReporter.warn("New component " + className + " could not be registered: "
                    + rootCause(t));
            return Outcome.DECLINED;
        }
    }

    private static ClassLoader contextClassLoader(Object context) {
        try {
            return (ClassLoader) context.getClass().getMethod("getClassLoader").invoke(context);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String message = c.getMessage();
        return c.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // ── stereotype detection, through Spring's merged annotations ──────────

    private record Stereotype(String beanName, boolean controller, Object definition) {
    }

    /** Any class-level annotation makes the class worth resolving through Spring. */
    private static boolean hasClassAnnotation(byte[] bytecode) {
        final boolean[] found = {false};
        try {
            new org.objectweb.asm.ClassReader(bytecode).accept(
                    new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            found[0] = true;
                            return null;
                        }
                    },
                    org.objectweb.asm.ClassReader.SKIP_CODE);
        } catch (Throwable unreadable) {
            return false;
        }
        return found[0];
    }

    /**
     * Ask the context's own Spring whether the class is a component and, if so,
     * build the annotated definition and bean name its scanner would. This
     * resolves custom meta-annotated stereotypes and {@code @AliasFor} names
     * rather than matching a fixed list of descriptors.
     */
    private static Stereotype resolveStereotype(Class<?> clazz, Object beanFactory, ClassLoader springLoader) {
        try {
            Class<?> merged = Class.forName("org.springframework.core.annotation.AnnotatedElementUtils", false, springLoader);
            Method hasAnnotation = merged.getMethod("hasAnnotation", java.lang.reflect.AnnotatedElement.class, Class.class);
            Class<?> component = Class.forName("org.springframework.stereotype.Component", false, springLoader);
            if (!Boolean.TRUE.equals(hasAnnotation.invoke(null, clazz, component))) return null;
            Class<?> controllerType = Class.forName("org.springframework.stereotype.Controller", false, springLoader);
            boolean controller = Boolean.TRUE.equals(hasAnnotation.invoke(null, clazz, controllerType));

            Class<?> annotatedDefinition = Class.forName("org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition", true, springLoader);
            Object definition = annotatedDefinition.getConstructor(Class.class).newInstance(clazz);
            // Honour @Scope, @Lazy, @Primary, @DependsOn on the class, as the scanner would.
            Class<?> annotatedBeanDefinition = Class.forName("org.springframework.beans.factory.annotation.AnnotatedBeanDefinition", false, springLoader);
            Class<?> configUtils = Class.forName("org.springframework.context.annotation.AnnotationConfigUtils", true, springLoader);
            configUtils.getMethod("processCommonDefinitionAnnotations", annotatedBeanDefinition).invoke(null, definition);

            Class<?> registryType = Class.forName("org.springframework.beans.factory.support.BeanDefinitionRegistry", false, springLoader);
            Class<?> beanDefinitionType = Class.forName("org.springframework.beans.factory.config.BeanDefinition", false, springLoader);
            Object generator = Class.forName("org.springframework.context.annotation.AnnotationBeanNameGenerator", true, springLoader)
                    .getConstructor().newInstance();
            String name = (String) generator.getClass().getMethod("generateBeanName", beanDefinitionType, registryType)
                    .invoke(generator, definition, beanFactory);
            return new Stereotype(name, controller, definition);
        } catch (ReflectiveOperationException unavailable) {
            return null;
        }
    }
}
