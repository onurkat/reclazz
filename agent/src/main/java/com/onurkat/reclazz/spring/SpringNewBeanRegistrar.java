/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.util.List;

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
 * <p>Scope, stated: classes carrying one of Spring's own stereotype
 * annotations, by descriptor, from the bytecode. A custom meta-annotated
 * stereotype is not resolved (that needs the full scanner) and is reported as
 * skipped when it at least looks like one.
 */
public final class SpringNewBeanRegistrar {

    /** What was done with the new class. */
    public enum Outcome { REGISTERED, NOT_A_COMPONENT, DECLINED }

    private static final String[] STEREOTYPES = {
            "Lorg/springframework/stereotype/Component;",
            "Lorg/springframework/stereotype/Service;",
            "Lorg/springframework/stereotype/Repository;",
            "Lorg/springframework/stereotype/Controller;",
            "Lorg/springframework/web/bind/annotation/RestController;",
    };

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
        Stereotype stereotype = findStereotype(bytecode);
        if (stereotype == null) return Outcome.NOT_A_COMPONENT;

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

            String beanName = stereotype.beanName != null && !stereotype.beanName.isEmpty()
                    ? stereotype.beanName
                    : java.beans.Introspector.decapitalize(
                            clazz.getSimpleName());

            Object beanFactory = SpringBeans.getBeanFactory(home);
            Method containsBeanDefinition = com.onurkat.reclazz.util.Reflect.findMethod(
                    beanFactory.getClass(), "containsBeanDefinition", String.class);
            if (containsBeanDefinition != null
                    && Boolean.TRUE.equals(containsBeanDefinition.invoke(beanFactory, beanName))) {
                StatusReporter.info("New class " + className + " matches existing bean '"
                        + beanName + "'; nothing registered, the existing bean's own "
                        + "reloader owns it.");
                return Outcome.DECLINED;
            }

            // new RootBeanDefinition(clazz), through the context's own Spring.
            ClassLoader springLoader = beanFactory.getClass().getClassLoader();
            Class<?> definitionClass = Class.forName(
                    "org.springframework.beans.factory.support.RootBeanDefinition",
                    true, springLoader);
            Object definition = definitionClass.getConstructor(Class.class).newInstance(clazz);

            Method register = com.onurkat.reclazz.util.Reflect.findMethod(beanFactory.getClass(),
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
                Method remove = com.onurkat.reclazz.util.Reflect.findMethod(beanFactory.getClass(),
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

    // ── stereotype detection, from bytecode ───────────────────────────────

    private record Stereotype(String beanName, boolean controller) {
    }

    private static Stereotype findStereotype(byte[] bytecode) {
        final Stereotype[] found = new Stereotype[1];
        try {
            new org.objectweb.asm.ClassReader(bytecode).accept(
                    new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                String descriptor, boolean visible) {
                            for (String stereotype : STEREOTYPES) {
                                if (!descriptor.equals(stereotype)) continue;
                                boolean controller = descriptor.contains("Controller");
                                found[0] = new Stereotype(null, controller);
                                return new org.objectweb.asm.AnnotationVisitor(
                                        org.objectweb.asm.Opcodes.ASM9) {
                                    @Override
                                    public void visit(String name, Object value) {
                                        if ("value".equals(name) && value instanceof String s) {
                                            found[0] = new Stereotype(s, controller);
                                        }
                                    }
                                };
                            }
                            return null;
                        }
                    },
                    org.objectweb.asm.ClassReader.SKIP_CODE);
        } catch (Throwable unreadable) {
            return null;
        }
        return found[0];
    }
}
