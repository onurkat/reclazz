/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.ui.StatusReporter;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Reflection-based access to the Spring classes used by the XML reloader.
 *
 * The agent ships without any compile-time Spring dependency — all Spring
 * access goes through these helpers. Every method returns a null / default on
 * failure and logs via {@link StatusReporter}; callers check returns and skip.
 *
 * Target API surface is intentionally limited to parts of Spring that have
 * been stable since 3.0 (DefaultListableBeanFactory, XmlBeanDefinitionReader,
 * BeanDefinitionValueResolver, MutablePropertyValues).
 */
final class SpringReflection {

    private SpringReflection() {}

    private static final String CLS_DLBF = "org.springframework.beans.factory.support.DefaultListableBeanFactory";
    private static final String CLS_XML_READER = "org.springframework.beans.factory.xml.XmlBeanDefinitionReader";
    private static final String CLS_FS_RESOURCE = "org.springframework.core.io.FileSystemResource";
    private static final String CLS_RESOURCE = "org.springframework.core.io.Resource";
    private static final String CLS_REGISTRY = "org.springframework.beans.factory.support.BeanDefinitionRegistry";
    private static final String CLS_BD = "org.springframework.beans.factory.config.BeanDefinition";
    private static final String CLS_AACBF = "org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory";
    private static final String CLS_BD_VALUE_RESOLVER = "org.springframework.beans.factory.support.BeanDefinitionValueResolver";
    private static final String CLS_BPP = "org.springframework.beans.factory.config.BeanPostProcessor";
    private static final String CLS_BFPP = "org.springframework.beans.factory.config.BeanFactoryPostProcessor";

    // ─── Temp factory + parse ─────────────────────────────────────────────────

    /**
     * Construct a throwaway DefaultListableBeanFactory for sandbox parsing.
     *
     * @param springLoader classloader that has Spring classes visible. On
     *                     Hybris Spring lives on the Tomcat/webapp CL, not
     *                     the agent CL, so we must use the live context's
     *                     CL (passed in by the reloader) rather than
     *                     {@code Class.forName} with the default CL.
     */
    static Object newTempBeanFactory(ClassLoader springLoader) {
        try {
            return loadClass(CLS_DLBF, springLoader).getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Parse {@code xmlFile} into {@code tempFactory}. Returns false on parse error. */
    static boolean loadXml(Object tempFactory, File xmlFile, ClassLoader springLoader) {
        // Spring finds the handler for a namespace like context: or util: by
        // reading META-INF/spring.handlers from the classpath, through the
        // reader's classloader and, failing that, the thread's. Both default to
        // the agent's, which contains no Spring at all, so every file using a
        // namespace failed to parse: on a real project that is nearly all of
        // them. The loader that holds the live Spring is the one that can see
        // its handlers.
        Thread current = Thread.currentThread();
        ClassLoader previousContext = current.getContextClassLoader();
        try {
            current.setContextClassLoader(springLoader);
            Class<?> readerCls = loadClass(CLS_XML_READER, springLoader);
            Class<?> registryCls = loadClass(CLS_REGISTRY, springLoader);
            Constructor<?> readerCtor = readerCls.getConstructor(registryCls);
            Object reader = readerCtor.newInstance(tempFactory);

            readerCls.getMethod("setBeanClassLoader", ClassLoader.class)
                    .invoke(reader, springLoader);
            Class<?> patternResolverCls = loadClass(
                    "org.springframework.core.io.support.PathMatchingResourcePatternResolver",
                    springLoader);
            Object resourceLoader = patternResolverCls
                    .getConstructor(ClassLoader.class).newInstance(springLoader);
            Class<?> resourceLoaderIface = loadClass(
                    "org.springframework.core.io.ResourceLoader", springLoader);
            readerCls.getMethod("setResourceLoader", resourceLoaderIface)
                    .invoke(reader, resourceLoader);

            // Disable XML validation — many Hybris / Spring extension XMLs
            // reference schemas that are shipped inside other jars and aren't
            // resolvable from a raw file path without EntityResolver setup.
            readerCls.getMethod("setValidating", boolean.class).invoke(reader, false);
            readerCls.getMethod("setNamespaceAware", boolean.class).invoke(reader, true);

            Class<?> resourceCls = loadClass(CLS_FS_RESOURCE, springLoader);
            Object resource = resourceCls.getConstructor(File.class).newInstance(xmlFile);

            Class<?> resourceIface = loadClass(CLS_RESOURCE, springLoader);
            Method load = readerCls.getMethod("loadBeanDefinitions", resourceIface);
            load.invoke(reader, resource);
            return true;
        } catch (Throwable t) {
            StatusReporter.warn("Failed to parse " + xmlFile.getName() + ": " + rootCause(t));
            return false;
        } finally {
            current.setContextClassLoader(previousContext);
        }
    }

    /**
     * Resolve a class name first via the supplied Spring-aware loader, then
     * via the thread context classloader, then the default. On Hybris the
     * Spring classes live on the Tomcat / webapp classloader which is
     * neither the agent's CL nor (necessarily) the TCCL of the file
     * watcher thread — so we have to be explicit.
     */
    private static Class<?> loadClass(String name, ClassLoader hint) throws ClassNotFoundException {
        if (hint != null) {
            try {
                return Class.forName(name, false, hint);
            } catch (ClassNotFoundException ignored) {}
        }
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null && tccl != hint) {
            try {
                return Class.forName(name, false, tccl);
            } catch (ClassNotFoundException ignored) {}
        }
        return Class.forName(name);
    }

    // ─── Factory queries ──────────────────────────────────────────────────────

    static String[] getBeanDefinitionNames(Object factory) {
        try {
            return (String[]) factory.getClass().getMethod("getBeanDefinitionNames").invoke(factory);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    static boolean containsBeanDefinition(Object factory, String name) {
        try {
            return (Boolean) factory.getClass().getMethod("containsBeanDefinition", String.class).invoke(factory, name);
        } catch (Throwable t) {
            return false;
        }
    }

    static Object getBeanDefinition(Object factory, String name) {
        try {
            return factory.getClass().getMethod("getBeanDefinition", String.class).invoke(factory, name);
        } catch (Throwable t) {
            return null;
        }
    }

    static void registerBeanDefinition(Object factory, String name, Object bd) throws Exception {
        Class<?> bdCls = loadClass(CLS_BD, factory.getClass().getClassLoader());
        Method m = com.onurkat.reclazz.util.Reflect.findMethod(factory.getClass(), "registerBeanDefinition", String.class, bdCls);
        if (m == null) throw new NoSuchMethodException("registerBeanDefinition");
        m.invoke(factory, name, bd);
    }

    static void removeBeanDefinition(Object factory, String name) {
        try {
            factory.getClass().getMethod("removeBeanDefinition", String.class).invoke(factory, name);
        } catch (Throwable ignored) {}
    }

    static void destroySingleton(Object factory, String name) {
        try {
            Method m = com.onurkat.reclazz.util.Reflect.findMethod(factory.getClass(), "destroySingleton", String.class);
            if (m != null) m.invoke(factory, name);
        } catch (Throwable ignored) {}
    }

    /** Returns the already-instantiated singleton, or null if not yet created. */
    static Object getExistingSingleton(Object factory, String name) {
        try {
            Method m = com.onurkat.reclazz.util.Reflect.findMethod(factory.getClass(), "getSingleton", String.class);
            if (m != null) return m.invoke(factory, name);
        } catch (Throwable ignored) {}
        return null;
    }

    // ─── ApplicationContext queries ───────────────────────────────────────────

    static Object getBeanFactory(Object applicationContext) {
        try {
            return applicationContext.getClass().getMethod("getBeanFactory").invoke(applicationContext);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object getBean(Object applicationContext, String name) throws Exception {
        try {
            return applicationContext.getClass().getMethod("getBean", String.class).invoke(applicationContext, name);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException("getBean failed", cause);
        }
    }

    // ─── BeanDefinition introspection ─────────────────────────────────────────

    static String getBeanClassName(Object bd)      { return stringMethod(bd, "getBeanClassName"); }
    static String getScope(Object bd)              { return stringMethod(bd, "getScope"); }
    static String getParentName(Object bd)         { return stringMethod(bd, "getParentName"); }
    static String getFactoryBeanName(Object bd)    { return stringMethod(bd, "getFactoryBeanName"); }
    static String getFactoryMethodName(Object bd)  { return stringMethod(bd, "getFactoryMethodName"); }
    static String getInitMethodName(Object bd)     { return stringMethod(bd, "getInitMethodName"); }
    static String getDestroyMethodName(Object bd)  { return stringMethod(bd, "getDestroyMethodName"); }
    static String getResourceDescription(Object bd) { return stringMethod(bd, "getResourceDescription"); }

    static boolean isAbstract(Object bd) {
        try {
            return (Boolean) bd.getClass().getMethod("isAbstract").invoke(bd);
        } catch (Throwable t) {
            return false;
        }
    }

    static Object getPropertyValues(Object bd) {
        try {
            return bd.getClass().getMethod("getPropertyValues").invoke(bd);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean hasConstructorArgs(Object bd) {
        try {
            Object cavs = bd.getClass().getMethod("getConstructorArgumentValues").invoke(bd);
            if (cavs == null) return false;
            return !(Boolean) cavs.getClass().getMethod("isEmpty").invoke(cavs);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Snapshot the PropertyValues as an array of Spring's PropertyValue objects. */
    static Object[] getPropertyValueArray(Object propertyValues) {
        if (propertyValues == null) return new Object[0];
        try {
            return (Object[]) propertyValues.getClass().getMethod("getPropertyValues").invoke(propertyValues);
        } catch (Throwable t) {
            return new Object[0];
        }
    }

    static String getPropertyName(Object pv) { return stringMethod(pv, "getName"); }

    static Object getPropertyValue(Object pv) {
        try {
            return pv.getClass().getMethod("getValue").invoke(pv);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolve a raw BD property value (may be a {@code RuntimeBeanReference},
     * {@code TypedStringValue}, nested list/map, SpEL expression, placeholder…)
     * into a real Object using Spring's own {@code BeanDefinitionValueResolver}.
     */
    static Object resolveValue(Object beanFactory, String beanName, Object beanDefinition,
                                String propertyName, Object rawValue) throws Exception {
        ClassLoader cl = beanFactory.getClass().getClassLoader();
        Class<?> resolverCls = loadClass(CLS_BD_VALUE_RESOLVER, cl);
        Class<?> aacbfCls = loadClass(CLS_AACBF, cl);
        Class<?> bdCls = loadClass(CLS_BD, cl);

        // Spring 5.3+ uses a 4-arg ctor taking a TypeConverter; older Spring
        // versions (3.x–5.2) have a 3-arg ctor without. Probe both so the
        // agent works on either.
        Object resolver;
        Constructor<?> ctor3 = findConstructor(resolverCls, aacbfCls, String.class, bdCls);
        if (ctor3 != null) {
            ctor3.setAccessible(true);
            resolver = ctor3.newInstance(beanFactory, beanName, beanDefinition);
        } else {
            Class<?> typeConverterCls = loadClass("org.springframework.beans.TypeConverter", cl);
            Constructor<?> ctor4 = findConstructor(resolverCls, aacbfCls, String.class, bdCls, typeConverterCls);
            if (ctor4 == null) {
                throw new NoSuchMethodException("BeanDefinitionValueResolver constructor not found");
            }
            ctor4.setAccessible(true);
            Object typeConverter = beanFactory.getClass().getMethod("getTypeConverter").invoke(beanFactory);
            resolver = ctor4.newInstance(beanFactory, beanName, beanDefinition, typeConverter);
        }

        Method resolve = resolverCls.getMethod("resolveValueIfNecessary", Object.class, Object.class);
        resolve.setAccessible(true);
        Object result;
        try {
            result = resolve.invoke(resolver, propertyName, rawValue);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException("resolveValueIfNecessary failed", cause);
        }

        // BeanDefinitionValueResolver.evaluate() only handles SpEL (#{...}).
        // Placeholder resolution (${...}) is done by StringValueResolvers that
        // PropertySourcesPlaceholderConfigurer registers via
        // ConfigurableBeanFactory.addEmbeddedValueResolver at refresh time.
        // For beans loaded at startup, Spring also rewrites BD strings via
        // BeanDefinitionVisitor at that moment — but our reloader sees the
        // XML AFTER refresh, so the raw ${...} survives. Run the registered
        // resolvers manually on any String result that still contains ${.
        if (result instanceof String) {
            String s = (String) result;
            if (s.contains("${")) {
                try {
                    Method rev = beanFactory.getClass().getMethod("resolveEmbeddedValue", String.class);
                    Object resolved = rev.invoke(beanFactory, s);
                    if (resolved instanceof String) return resolved;
                } catch (Throwable ignored) {
                    // No placeholder resolver registered — return the raw string
                    // and let the setter receive the literal ${...}.
                }
            }
        }
        return result;
    }

    /** getConstructor that scans all declared constructors (including package-private). */
    private static Constructor<?> findConstructor(Class<?> cls, Class<?>... paramTypes) {
        outer:
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            Class<?>[] actual = c.getParameterTypes();
            if (actual.length != paramTypes.length) continue;
            for (int i = 0; i < actual.length; i++) {
                if (!actual[i].equals(paramTypes[i])) continue outer;
            }
            return c;
        }
        return null;
    }

    // ─── Type checks ──────────────────────────────────────────────────────────

    /** True if {@code className} is (or extends) a BeanPostProcessor / BeanFactoryPostProcessor. */
    static boolean isPostProcessor(String className) {
        return isAssignableTo(className, CLS_BPP) || isAssignableTo(className, CLS_BFPP);
    }

    static boolean isInitializingOrDisposable(String className) {
        return isAssignableTo(className, "org.springframework.beans.factory.InitializingBean")
                || isAssignableTo(className, "org.springframework.beans.factory.DisposableBean");
    }

    private static boolean isAssignableTo(String implClass, String iface) {
        if (implClass == null) return false;
        // Try TCCL first, then agent CL. On Hybris user code may live in the
        // webapp CL rather than the TCCL of the watcher thread, so we also
        // fall back to Class.forName without a CL hint.
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                SpringReflection.class.getClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                Class<?> impl = Class.forName(implClass, false, cl);
                Class<?> i = Class.forName(iface, false, cl);
                return i.isAssignableFrom(impl);
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    private static String stringMethod(Object target, String methodName) {
        try {
            return (String) target.getClass().getMethod(methodName).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}
