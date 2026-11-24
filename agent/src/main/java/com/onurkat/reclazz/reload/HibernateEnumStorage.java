/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.HibernateFactories;
import com.onurkat.reclazz.transform.HibernateFactoryTransformer;
import com.onurkat.reclazz.platform.ApplicationContextHolder;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Stream;

/** Effective Hibernate 6.5.3 metadata, reached without linking the agent to an application's provider. */
final class HibernateEnumStorage {
    private HibernateEnumStorage() { }

    private static final Set<Class<?>> divergent = Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<Class<?>> pending = new HashSet<>();

    private static int destructiveRebuilds;

    static boolean beginDestructiveRebuild() {
        synchronized (divergent) {
            if (!divergent.isEmpty() || !pending.isEmpty()) return false;
            destructiveRebuilds++;
            return true;
        }
    }

    static void finishDestructiveRebuild() {
        synchronized (divergent) { destructiveRebuilds--; }
    }

    static HibernateFactories.Snapshot beginOrderChange(Class<?> type) {
        synchronized (divergent) {
            if (destructiveRebuilds != 0) return null;
            pending.add(type);
        }
        return HibernateFactories.snapshot();
    }

    static void finishOrderChange(Class<?> type, HibernateFactories.Snapshot before) {
        synchronized (divergent) {
            pending.remove(type);
            if (!divergent.contains(type)) return;
        }
        var after = HibernateFactories.snapshot();
        if (before.generation() == after.generation()) return;
        Set<Object> previouslyReady = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var factory : before.factories()) if (factory.initialized()) previouslyReady.add(factory.instance());
        for (var factory : after.factories()) {
            if (factory.initialized() && !previouslyReady.contains(factory.instance()))
                report(factory.instance(), List.of(type));
        }
    }

    static void recordOrder(Class<?> type, boolean differs) {
        synchronized (divergent) {
            if (differs) divergent.add(type);
            else divergent.remove(type);
        }
    }

    /** Controlled swaps check even an in-flight edit before publishing their candidate. */
    static List<String> freshFactoryProblems(Object factory) {
        Set<Class<?>> enums;
        synchronized (divergent) {
            enums = new LinkedHashSet<>(divergent);
            enums.addAll(pending);
        }
        return problems(factory, enums);
    }

    private static List<String> problems(Object factory, Collection<Class<?>> enums) {
        List<String> problems = new ArrayList<>();
        for (Class<?> type : enums) {
            String reason;
            try {
                reason = factory.getClass().getName().equals(HibernateFactoryTransformer.FACTORY)
                        && trackingInstalled(factory.getClass())
                        ? new Mappings(type, factory.getClass().getClassLoader()).inspect(factory, new HashSet<>())
                        : "unknown persistence factory";
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                reason = "factory metadata unavailable (" + unavailable.getClass().getSimpleName() + ")";
            }
            if (reason != null) problems.add(type.getName() + " has different source and running ordinals at " + reason);
        }
        return problems;
    }

    static void factoryInitialized(Object factory) {
        List<Class<?>> enums;
        synchronized (divergent) { enums = List.copyOf(divergent); }
        report(factory, enums);
    }

    private static void report(Object factory, Collection<Class<?>> enums) {
        if (enums.isEmpty()) return;
        try { if (Boolean.TRUE.equals(call(factory, "isClosed"))) return; }
        catch (ReflectiveOperationException | RuntimeException unreadable) { /* report via metadata inspection */ }
        for (String problem : problems(factory, enums)) {
            com.onurkat.reclazz.ui.StatusReporter.warn("Persistence mapping requires restart for this enum order: " + problem);
            com.onurkat.reclazz.ui.RestartLedger.note(HibernateFactoryTransformer.FACTORY, problem);
        }
    }

    static String refusal(Class<?> target, Class<?>[] loaded, Set<String> markers) {
        for (Class<?> type : loaded) {
            if (!type.getName().equals(HibernateFactoryTransformer.FACTORY)) continue;
            if (!"6.5.3.Final".equals(type.getPackage().getImplementationVersion()))
                return "unmeasured Hibernate version in " + type.getName();
            if (!trackingInstalled(type)) return "Hibernate factory loaded before lifecycle tracking";
        }
        HibernateFactories.Snapshot snapshot = HibernateFactories.snapshot();
        Set<ClassLoader> covered = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Class<?>> apis = new ArrayList<>();
        Set<Object> witnessed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var entry : snapshot.factories()) witnessed.add(entry.instance());
        boolean open = false;
        try {
            for (Class<?> type : loaded) {
                if (type.getClassLoader() != null) loaders.add(type.getClassLoader());
                if (type.getName().equals("jakarta.persistence.EntityManagerFactory")
                        || type.getName().equals("javax.persistence.EntityManagerFactory")) apis.add(type);
                if (type.getName().equals(HibernateFactoryTransformer.FACTORY)) {
                    Class<?> registryType = Class.forName("org.hibernate.internal.SessionFactoryRegistry", false, type.getClassLoader());
                    var registrations = registryType.getDeclaredField("sessionFactoryMap");
                    if (!registrations.trySetAccessible()) return "Hibernate factory registry is unreadable";
                    Object registry = registryType.getField("INSTANCE").get(null);
                    Map<?, ?> factories = (Map<?, ?>) registrations.get(registry);
                    for (Object factory : factories.values()) {
                        if (!witnessed.contains(factory)) return "Hibernate registry contains an unwitnessed factory";
                    }
                }
            }
            Set<Class<?>> verifiedProxies = new HashSet<>();
            for (Class<?> api : apis) {
                for (Object context : ApplicationContextHolder.getAllContexts()) {
                    Object beans = call(context, "getBeanFactory");
                    String[] names = (String[]) context.getClass().getMethod("getBeanNamesForType",
                            Class.class, boolean.class, boolean.class).invoke(context, api, true, false);
                    for (String name : names) {
                        Object singleton = beans.getClass().getMethod("getSingleton", String.class).invoke(beans, name);
                        if (singleton == null) continue;
                        for (Class<?> parent = singleton.getClass(); parent != null; parent = parent.getSuperclass()) {
                            if (parent.getName().equals("org.springframework.orm.jpa.AbstractEntityManagerFactoryBean")) {
                                singleton = call(singleton, "getObject");
                                break;
                            }
                        }
                        if (!api.isInstance(singleton) || witnessed.contains(singleton)) continue;
                        if (java.lang.reflect.Proxy.isProxyClass(singleton.getClass())
                                && java.lang.reflect.Proxy.getInvocationHandler(singleton).getClass().getName().equals(
                                        "org.springframework.orm.jpa.AbstractEntityManagerFactoryBean$ManagedEntityManagerFactoryInvocationHandler")
                                && witnessed.contains(call(singleton, "getNativeEntityManagerFactory"))) {
                            verifiedProxies.add(singleton.getClass());
                        } else return "Spring context contains an unwitnessed persistence factory " + name;
                    }
                }
                for (Class<?> type : loaded) {
                    if (!Modifier.isAbstract(type.getModifiers()) && api.isAssignableFrom(type)
                            && !verifiedProxies.contains(type) && !type.getName().equals(HibernateFactoryTransformer.FACTORY))
                        return "untracked persistence factory type " + type.getName();
                }
            }

            for (HibernateFactories.Factory entry : snapshot.factories()) {
                Object factory = entry.instance();
                if (Boolean.TRUE.equals(call(factory, "isClosed"))) continue;
                if (!entry.initialized()) return "incomplete Hibernate factory construction";
                Class<?> type = factory.getClass();
                if (!type.getName().equals(HibernateFactoryTransformer.FACTORY) || !trackingInstalled(type))
                    return "unknown Hibernate factory implementation " + type.getName();
                open = true;
                ClassLoader loader = type.getClassLoader();
                covered.add(loader);
                covered.add(Class.forName("jakarta.persistence.EntityManagerFactory", false, loader).getClassLoader());
                String problem = new Mappings(target, loader).inspect(factory, covered);
                if (problem != null) return problem;
            }

            if (!open) return "JPA has no tracked open Hibernate factory";
            for (Class<?> type : loaded) {
                if (markers.contains(type.getName()) && (!covered.contains(type.getClassLoader())
                        || type.getName().startsWith("javax.persistence.")))
                    return "uncovered JPA marker " + type.getName();
            }
            for (ClassLoader loader : loaders) {
                if (!covered.contains(loader) && (loader.getResource("META-INF/orm.xml") != null
                        || loader.getResource("META-INF/persistence.xml") != null))
                    return "JPA XML mappings in an untracked class loader";
            }
            return HibernateFactories.unchanged(snapshot.generation()) ? null : "Hibernate factory coverage changed during inspection";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unknown) {
            return "Hibernate factory metadata unavailable (" + unknown.getClass().getSimpleName() + ")";
        }
    }

    private static boolean trackingInstalled(Class<?> type) {
        try {
            var failure = type.getDeclaredField(HibernateFactoryTransformer.WITNESS);
            return failure.isSynthetic() && failure.trySetAccessible() && !failure.getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException missing) {
            return false;
        }
    }

    private static Object call(Object receiver, String name) throws ReflectiveOperationException {
        return receiver.getClass().getMethod(name).invoke(receiver);
    }

    private static final class Mappings {
        private final Class<?> target;
        private final ClassLoader loader;
        private final Set<Object> embedded = Collections.newSetFromMap(new IdentityHashMap<>());

        Mappings(Class<?> target, ClassLoader loader) { this.target = target; this.loader = loader; }

        String inspect(Object factory, Set<ClassLoader> covered) throws ReflectiveOperationException {
            Object mappings = call(factory, "getMappingMetamodel");
            Object callbacks = call(call(factory, "getEventEngine"), "getCallbackRegistry");
            try (Stream<?> entities = (Stream<?>) call(mappings, "streamEntityDescriptors")) {
                for (Object entity : entities.toList()) {
                    Class<?> mapped = (Class<?>) call(entity, "getMappedClass");
                    covered.add(mapped.getClassLoader());
                    String owner = (String) call(entity, "getEntityName");
                    String reason = ordinalWrites(mapped);
                    for (Class<?> declaring = mapped; reason == null && declaring != null; declaring = declaring.getSuperclass()) {
                        for (var annotation : declaring.getDeclaredAnnotations()) {
                            if (annotation.annotationType().getName().equals("jakarta.persistence.IdClass")) {
                                reason = ordinalWrites((Class<?>) annotation.annotationType().getMethod("value").invoke(annotation));
                                if (reason != null) break;
                            }
                            if (!annotation.annotationType().getName().equals("jakarta.persistence.EntityListeners")) continue;
                            var listeners = (Class<?>[]) annotation.annotationType().getMethod("value").invoke(annotation);
                            for (Class<?> listener : listeners) {
                                reason = ordinalWrites(listener);
                                if (reason != null) break;
                            }
                        }
                    }
                    if (reason == null) reason = effectiveListeners(callbacks, mapped);
                    if (reason == null) reason = part(call(entity, "getIdentifierMapping"), owner + ".<id>");
                    if (reason == null) reason = attributes(entity, owner);
                    if (reason != null) return reason;
                }
            }
            return null;
        }

        // The provider has already combined annotation listeners and XML persistence-unit defaults.
        private String effectiveListeners(Object registry, Class<?> entity) throws ReflectiveOperationException {
            if (registry.getClass().getName().equals("org.hibernate.jpa.event.internal.EmptyCallbackRegistryImpl")) return null;
            if (!registry.getClass().getName().equals("org.hibernate.jpa.event.internal.CallbackRegistryImpl"))
                return "unknown lifecycle callback registry for " + entity.getName();
            Class<?> mapType = Class.forName("org.hibernate.internal.util.collections.ReadOnlyMap", false, loader);
            var get = mapType.getMethod("get", Object.class);
            for (String key : List.of("preCreates", "postCreates", "preUpdates", "postUpdates",
                    "preRemoves", "postRemoves", "postLoads")) {
                var slot = registry.getClass().getDeclaredField(key);
                if (!slot.trySetAccessible()) return "unreadable lifecycle callbacks for " + entity.getName();
                Object[] listeners = (Object[]) get.invoke(slot.get(registry), entity);
                if (listeners == null) continue;
                for (Object listener : listeners) {
                    var field = listener.getClass().getDeclaredField("callbackMethod");
                    if (!field.trySetAccessible()) return "unreadable lifecycle listener for " + entity.getName();
                    var callback = (java.lang.reflect.Method) field.get(listener);
                    String reason = ordinalWrites(callback.getDeclaringClass());
                    if (reason != null) return reason;
                }
            }
            return null;
        }

        private String attributes(Object managed, String path) throws ReflectiveOperationException {
            int count = (int) call(managed, "getNumberOfAttributeMappings");
            for (int i = 0; i < count; i++) {
                Object attribute = managed.getClass().getMethod("getAttributeMapping", int.class).invoke(managed, i);
                String reason = part(attribute, path + "." + call(attribute, "getAttributeName"));
                if (reason != null) return reason;
            }
            return null;
        }

        private String ordinalWrites(Class<?> mapped) {
            String owner = target.getName().replace('.', '/');
            for (Class<?> type = mapped; type != null && type != Object.class; type = type.getSuperclass()) {
                try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                    if (in == null) return type.getName() + " (entity method bytecode unavailable)";
                    var node = new org.objectweb.asm.tree.ClassNode();
                    new org.objectweb.asm.ClassReader(in).accept(node, org.objectweb.asm.ClassReader.SKIP_DEBUG
                            | org.objectweb.asm.ClassReader.SKIP_FRAMES);
                    for (var method : node.methods) for (var insn : method.instructions) {
                        if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("ordinal")
                                && call.desc.equals("()I") && (call.owner.equals(owner) || call.owner.equals("java/lang/Enum")))
                            return type.getName() + "." + method.name + "() reads ordinal()";
                    }
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }
            return null;
        }

        private boolean canContainEnum(java.lang.reflect.Type declared, Set<java.lang.reflect.Type> visited) {
            if (!visited.add(declared)) return false;
            if (declared instanceof java.lang.reflect.ParameterizedType parameters) {
                for (var arg : parameters.getActualTypeArguments()) if (canContainEnum(arg, visited)) return true;
                declared = parameters.getRawType();
            }
            if (!(declared instanceof Class<?> type)) return true;
            if (type.isAssignableFrom(target)) return true;
            if (type.isArray()) return canContainEnum(type.getComponentType(), visited);
            if (type.isPrimitive() || type.isEnum() || type == String.class || type == Boolean.class
                    || type == Character.class || (Number.class.isAssignableFrom(type)
                    && type.getPackageName().equals("java.lang"))) return false;
            if (!Modifier.isFinal(type.getModifiers())) return true; // runtime subtypes can add serialized state
            for (Class<?> parent = type; parent != null && parent != Object.class; parent = parent.getSuperclass()) {
                for (var field : parent.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())
                        && canContainEnum(field.getGenericType(), visited)) return true;
                for (var method : parent.getDeclaredMethods()) if (!Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 0 && (method.getName().startsWith("get") || method.getName().startsWith("is"))
                        && canContainEnum(method.getGenericReturnType(), visited)) return true;
            }
            return false;
        }

        private boolean is(Object part, String role) throws ClassNotFoundException {
            return Class.forName("org.hibernate.metamodel.mapping." + role, false, loader).isInstance(part);
        }

        private String part(Object part, String path) throws ReflectiveOperationException {
            if (is(part, "EmbeddableValuedModelPart")) {
                Object mapping = call(part, "getEmbeddableTypeDescriptor");
                Object strategy = call(mapping, "getRepresentationStrategy");
                if (call(strategy, "getInstantiator").getClass().getName().equals(
                        "org.hibernate.metamodel.internal.EmbeddableCompositeUserTypeInstantiator"))
                    return path + " (opaque composite user type)";
                if (!embedded.add(mapping)) return null;
                String ordinal = ordinalWrites((Class<?>) call(call(mapping, "getMappedJavaType"), "getJavaTypeClass"));
                return ordinal == null ? attributes(mapping, path) : ordinal;
            }
            if (is(part, "PluralAttributeMapping")) {
                Object key = call(part, "getIndexDescriptor");
                String reason = key == null ? null : part(key, path + "[key]");
                return reason != null ? reason : part(call(part, "getElementDescriptor"), path + "[element]");
            }
            if (is(part, "EntityAssociationMapping") || is(part, "internal.EntityCollectionPart")) return null; // the target entity is scanned in its own right
            if (!is(part, "BasicValuedMapping")) return path + " (unsupported persistence mapping)";

            Object javaType = call(part, "getJavaType");
            Class<?> domain = (Class<?>) call(javaType, "getJavaTypeClass");
            Object jdbc = call(part, "getJdbcMapping");
            Object descriptor = call(jdbc, "getJdbcType");
            if (domain != target) {
                if ((call(jdbc, "getValueConverter") != null || jdbc.getClass().getName().equals("org.hibernate.type.CustomType"))
                        && canContainEnum((java.lang.reflect.Type) call(javaType, "getJavaType"), new HashSet<>()))
                    return path + " (converter or custom storage can contain " + target.getName() + ")";
                int sql = (int) call(descriptor, "getDefaultSqlTypeCode");
                if ((sql == 3001 || sql == java.sql.Types.SQLXML || sql == java.sql.Types.JAVA_OBJECT
                        || Boolean.TRUE.equals(call(descriptor, "isLob"))
                        || Class.forName("org.hibernate.type.descriptor.java.spi.FormatMapperBasedJavaType", false, loader)
                                .isInstance(javaType)) && canContainEnum((java.lang.reflect.Type) call(javaType, "getJavaType"),
                                        new HashSet<>()))
                    return path + " (opaque " + descriptor.getClass().getSimpleName() + " storage can contain " + target.getName() + ")";
                if (domain.isArray() && domain.getComponentType() == target
                        || Collection.class.isAssignableFrom(domain) || Map.class.isAssignableFrom(domain))
                    return path + " (opaque basic collection storage)";
                return null;
            }
            if (call(jdbc, "getJavaTypeDescriptor") != javaType || call(jdbc, "getValueConverter") != null
                    || !jdbc.getClass().getName().equals("org.hibernate.type.internal.BasicTypeImpl")
                    || !javaType.getClass().getName().equals("org.hibernate.type.descriptor.java.EnumJavaType"))
                return path + " (converter or custom enum storage cannot be proven stable)";
            String type = descriptor.getClass().getName();
            // Match concrete provider binders. A user-defined binder can advertise VARCHAR and store ordinal().
            return switch (type) {
                case "org.hibernate.type.descriptor.jdbc.EnumJdbcType", "org.hibernate.type.descriptor.jdbc.VarcharJdbcType",
                     "org.hibernate.type.descriptor.jdbc.NVarcharJdbcType", "org.hibernate.type.descriptor.jdbc.CharJdbcType",
                     "org.hibernate.type.descriptor.jdbc.NCharJdbcType" -> null;
                default -> path + " (ordinal or unverified enum storage: " + descriptor.getClass().getSimpleName() + ")";
            };
        }
    }
}
