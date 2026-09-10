/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

/** Native recreation of a deliberately bounded, resource-owned XML singleton graph. */
final class XmlBeanRecreator {
    private XmlBeanRecreator() { }
    private static final Map<Object,List<java.lang.ref.WeakReference<Object>>> retiredHolders = new WeakHashMap<>();

    record Replacement(String name, Object previous, Object candidate) { }
    private record Link(Object holder, Field field, Object previous) { }

    static boolean handles(Object old, Object next) {
        return rich(old) || rich(next);
    }
    private static boolean rich(Object bd) {
        return bd != null && (SpringReflection.hasConstructorArgs(bd)
                || nonempty(SpringReflection.getFactoryMethodName(bd))
                || nonempty(SpringReflection.getFactoryBeanName(bd))
                || nonempty(SpringReflection.getInitMethodName(bd))
                || nonempty(SpringReflection.getDestroyMethodName(bd)));
    }

    static Replacement prepare(Object factory, Object parsedFactory, String name, Object old, Object next, Path path) throws Exception {
        require(old != null, "new constructor/factory/lifecycle definitions require restart");
        require(owns(old,path) && owns(next,path), "definition is not owned by this exact XML resource");
        require(Arrays.equals(sorted((String[])call(factory,"getAliases",String.class,name)),
                sorted((String[])call(parsedFactory,"getAliases",String.class,name))), "alias changes require restart");
        Object candidate=call(next,"cloneBeanDefinition");
        // Spring's definition clone shares raw TypedStringValue payloads. Detach
        // them before the visitor mutates placeholders for a particular context.
        detachValues(candidate);
        call(candidate,"setBeanClassName",String.class,SpringReflection.getBeanClassName(candidate));
        resolvePlaceholders(factory,candidate);
        shape(old); shape(candidate);
        require(Objects.equals(SpringReflection.getBeanClassName(old),SpringReflection.getBeanClassName(candidate)), "class changed");
        require(Objects.equals(SpringReflection.getFactoryBeanName(old),SpringReflection.getFactoryBeanName(candidate)), "factory-bean changed");
        require(normalized(old,old).equals(normalized(candidate,old)), "metadata outside constructor/property/factory-method/init/destroy changed");
        Class<?> previousType=productType(factory,old), nextType=productType(factory,candidate);
        require(previousType==nextType, "factory product type changed");
        safeType(previousType,false);
        Object instance=SpringReflection.getExistingSingleton(factory,name);
        require(instance==null || instance.getClass()==previousType, "proxy or replaced singleton instance");
        if (values(old).equals(values(candidate))) return null;
        require((Boolean)call(factory,"isAllowBeanDefinitionOverriding"), "bean definition overriding is disabled");
        return new Replacement(name,old,candidate);
    }

    static boolean owns(Object definition, Path path) throws Exception {
        Object resource=call(definition,"getResource");
        return resource!=null && ((File)call(resource,"getFile")).toPath().toRealPath().equals(path.toRealPath());
    }

    private static void shape(Object bd) throws Exception {
        require(!(Boolean)call(bd,"isAbstract") && (Boolean)call(bd,"isSingleton"), "only concrete singleton definitions are supported");
        require(call(bd,"getParentName")==null, "parent definitions require restart");
        require((Integer)call(bd,"getRole")==0 && !(Boolean)call(bd,"isSynthetic"), "infrastructure definition");
        require((Integer)call(bd,"getAutowireMode")==0 && call(bd,"getDependsOn")==null, "autowire/depends-on metadata requires restart");
        require(call(bd,"getInstanceSupplier")==null && (Boolean)call(call(bd,"getMethodOverrides"),"isEmpty"), "custom construction or method overrides");
    }

    // Spring may replace a bean class name with its resolved Class at startup.
    private static Object normalized(Object bd,Object reference) throws Exception {
        Object copy=call(bd,"cloneBeanDefinition");
        call(copy,"setBeanClassName",String.class,SpringReflection.getBeanClassName(bd));
        for(String property:List.of("FactoryMethodName","InitMethodName","DestroyMethodName"))
            call(copy,"set"+property,String.class,call(reference,"get"+property));
        setSpring(copy,"setConstructorArgumentValues","org.springframework.beans.factory.config.ConstructorArgumentValues",call(reference,"getConstructorArgumentValues"));
        setSpring(copy,"setPropertyValues","org.springframework.beans.MutablePropertyValues",call(reference,"getPropertyValues"));
        return copy;
    }

    private static List<Object> values(Object bd) throws Exception {
        List<Object> out=new ArrayList<>();
        out.add(call(bd,"getFactoryMethodName")); out.add(call(bd,"getInitMethodName")); out.add(call(bd,"getDestroyMethodName"));
        Object arguments=call(bd,"getConstructorArgumentValues");
        Map<?,?> indexed=(Map<?,?>)call(arguments,"getIndexedArgumentValues");
        Map<Object,Object> canonical=new LinkedHashMap<>();
        for(var entry:indexed.entrySet()) canonical.put(entry.getKey(),argument(entry.getValue()));
        out.add(canonical);
        List<Object> generic=new ArrayList<>();
        for(Object value:(List<?>)call(arguments,"getGenericArgumentValues")) generic.add(argument(value));
        out.add(generic);
        Map<String,Object> properties=new LinkedHashMap<>();
        for(Object pv:SpringReflection.getPropertyValueArray(call(bd,"getPropertyValues")))
            properties.put((String)call(pv,"getName"),Arrays.asList(raw(call(pv,"getValue")),call(pv,"isOptional")));
        out.add(properties); return out;
    }
    private static Object argument(Object holder) throws Exception {
        return Arrays.asList(call(holder,"getName"),call(holder,"getType"),raw(call(holder,"getValue")));
    }
    private static Object raw(Object value) throws Exception {
        if(value==null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if(value.getClass().getName().equals("org.springframework.beans.factory.config.TypedStringValue"))
            return Arrays.asList("typed",call(value,"getValue"),call(value,"getTargetTypeName"));
        if(value.getClass().getName().equals("org.springframework.beans.factory.config.RuntimeBeanReference"))
            return Arrays.asList("ref",call(value,"getBeanName"),call(value,"isToParent"));
        throw new IllegalArgumentException("only scalar values and bean references are supported in recreated XML definitions");
    }

    private static void detachValues(Object bd) throws Exception {
        Object arguments=call(bd,"getConstructorArgumentValues");
        List<Object> holders=new ArrayList<>(((Map<?,?>)call(arguments,"getIndexedArgumentValues")).values());
        holders.addAll((List<?>)call(arguments,"getGenericArgumentValues"));
        for(Object holder:holders) call(holder,"setValue",Object.class,detached(call(holder,"getValue")));
        Object properties=call(bd,"getPropertyValues");
        for(Object pv:SpringReflection.getPropertyValueArray(properties)) {
            Object copy=pv.getClass().getConstructor(pv.getClass(),Object.class).newInstance(pv,detached(call(pv,"getValue")));
            call(properties,"addPropertyValue",pv.getClass(),copy);
        }
    }
    private static Object detached(Object value) throws Exception {
        raw(value); // refuse unsupported nested/managed values before mutating anything
        if(value!=null && value.getClass().getName().equals("org.springframework.beans.factory.config.TypedStringValue")) {
            String type=(String)call(value,"getTargetTypeName");
            return type==null ? value.getClass().getConstructor(String.class).newInstance(call(value,"getValue"))
                    : value.getClass().getConstructor(String.class,String.class).newInstance(call(value,"getValue"),type);
        }
        return value;
    }

    private static Class<?> productType(Object factory,Object bd) throws Exception {
        ClassLoader loader=(ClassLoader)call(factory,"getBeanClassLoader");
        String factoryName=SpringReflection.getFactoryBeanName(bd), method=SpringReflection.getFactoryMethodName(bd);
        Class<?> owner;
        if(nonempty(factoryName)) {
            Object producer=SpringReflection.getExistingSingleton(factory,factoryName);
            require(producer!=null, "factory-bean must already be a local singleton");
            Object producerDefinition=SpringReflection.getBeanDefinition(factory,factoryName);
            require(producerDefinition!=null, "factory-bean definition missing");
            shape(producerDefinition); owner=producer.getClass(); safeType(owner,true);
            require(owner.getName().equals(SpringReflection.getBeanClassName(producerDefinition)), "factory-bean proxy or indirect construction");
        } else owner=Class.forName(Objects.requireNonNull(SpringReflection.getBeanClassName(bd),"class missing"),false,loader);
        if(!nonempty(method)) return owner;
        safeType(owner,false);
        List<Method> methods=Arrays.stream(owner.getMethods()).filter(m->m.getName().equals(method)
                && Modifier.isStatic(m.getModifiers())==!nonempty(factoryName)).toList();
        require(methods.size()==1, "factory method must be public and unambiguous");
        Class<?> type=methods.get(0).getReturnType();
        require(type!=Object.class && !type.isInterface() && !type.isPrimitive() && !type.isArray(), "factory needs a concrete product return type");
        return type;
    }

    private static void safeType(Class<?> type,boolean dependent) throws Exception {
        require(!Proxy.isProxyClass(type) && !type.getName().contains("$$"), "proxy type");
        require(!type.getName().startsWith("java.") && !type.getName().startsWith("org.springframework."), "non-application type");
        for(String name:List.of("java.lang.AutoCloseable","java.lang.Thread","java.util.concurrent.Executor",
                "javax.sql.DataSource","org.springframework.beans.factory.FactoryBean",
                "org.springframework.beans.factory.config.BeanPostProcessor","org.springframework.beans.factory.config.BeanFactoryPostProcessor",
                "org.springframework.context.ApplicationContext","org.springframework.context.Lifecycle",
                "org.springframework.beans.factory.InitializingBean","org.springframework.beans.factory.DisposableBean")) {
            Class<?> forbidden=Class.forName(name,false,type.getClassLoader());
            require(!forbidden.isAssignableFrom(type), "resource/infrastructure/lifecycle interface: "+name);
        }
        if(dependent) for(Class<?> c=type;c!=null && c!=Object.class;c=c.getSuperclass())
            for(Method method:c.getDeclaredMethods()) for(var annotation:method.getDeclaredAnnotations())
                require(!Set.of("javax.annotation.PostConstruct","javax.annotation.PreDestroy","jakarta.annotation.PostConstruct",
                        "jakarta.annotation.PreDestroy").contains(annotation.annotationType().getName()), "dependent has lifecycle annotations");
    }

    static int apply(Object context,Object factory,List<Replacement> changes) throws Exception {
        if(changes.isEmpty()) return 0;
        Map<String,Object> oldInstances=new LinkedHashMap<>();
        Map<String,Object> oldDefinitions=new LinkedHashMap<>();
        Set<String> roots=new LinkedHashSet<>(); for(var r:changes) roots.add(r.name);
        for(var r:changes) {
            require(SpringReflection.getBeanDefinition(factory,r.name)==r.previous, "definition changed during reload");
            collect(factory,r.name,roots,oldInstances,oldDefinitions,new HashSet<>());
        }
        for(String name:SpringReflection.getBeanDefinitionNames(factory)) {
            String parent=SpringReflection.getParentName(SpringReflection.getBeanDefinition(factory,name));
            require(parent==null || !oldDefinitions.containsKey(parent), "derived bean definitions require restart");
        }
        List<Link> links=links(factory,oldInstances,roots);
        synchronized(retiredHolders) {
            var retired=retiredHolders.computeIfAbsent(factory,key->new ArrayList<>());
            retired.removeIf(ref->ref.get()==null);
            for(var entry:oldInstances.entrySet()) if(!roots.contains(entry.getKey()) && entry.getValue()!=null)
                retired.add(new java.lang.ref.WeakReference<>(entry.getValue()));
        }
        for(var r:changes) require(SpringReflection.getBeanDefinition(factory,r.name)==r.previous, "definition ownership changed");
        try {
            for(var r:changes) SpringReflection.registerBeanDefinition(factory,r.name,r.candidate);
            recreate(context,factory,oldInstances,links);
        } catch(Exception failure) {
            try {
                for(var r:changes) SpringReflection.registerBeanDefinition(factory,r.name,r.previous);
                recreate(context,factory,oldInstances,links);
            } catch(Exception restoration) {
                failure.addSuppressed(restoration);
                throw new IllegalStateException("XML recreation failed and restoration failed; restart required: "
                        +SpringReflection.rootCause(failure)+"; restoration: "+SpringReflection.rootCause(restoration),failure);
            }
            throw new IllegalStateException("XML recreation failed; previous definitions restored and instances recreated (callback effects are not rolled back): "
                    +SpringReflection.rootCause(failure),failure);
        }
        return changes.size();
    }

    private static void collect(Object factory,String name,Set<String> roots,Map<String,Object> instances,
                                Map<String,Object> definitions,Set<String> visiting) throws Exception {
        require(visiting.add(name), "cyclic dependencies require restart");
        if(definitions.containsKey(name)) { visiting.remove(name); return; }
        Object bd=SpringReflection.getBeanDefinition(factory,name);
        require(bd!=null, "dependent lacks a local definition: "+name); shape(bd);
        if(!roots.contains(name)) require(!rich(bd), "dependent constructor/factory/lifecycle requires restart: "+name);
        Object instance=SpringReflection.getExistingSingleton(factory,name);
        if(instance!=null) {
            safeType(instance.getClass(),!roots.contains(name));
            require(instance.getClass()==productType(factory,bd), "dependent proxy: "+name);
        }
        for(String dependent:(String[])call(factory,"getDependentBeans",String.class,name))
            collect(factory,dependent,roots,instances,definitions,visiting);
        definitions.put(name,bd); instances.put(name,instance); visiting.remove(name);
    }

    private static List<Link> links(Object factory,Map<String,Object> instances,Set<String> roots) throws Exception {
        Set<Object> previous=Collections.newSetFromMap(new IdentityHashMap<>());
        previous.addAll(instances.values()); previous.remove(null);
        Set<Object> oldRoots=Collections.newSetFromMap(new IdentityHashMap<>());
        for(String root:roots) oldRoots.add(instances.get(root));
        List<Link> result=new ArrayList<>();
        Set<Object> holders=Collections.newSetFromMap(new IdentityHashMap<>());
        for(String name:(String[])call(factory,"getSingletonNames")) {
            Object holder=SpringReflection.getExistingSingleton(factory,name);
            if(holder!=null) holders.add(holder);
        }
        synchronized(retiredHolders) {
            for(var ref:retiredHolders.getOrDefault(factory,List.of())) if(ref.get()!=null) holders.add(ref.get());
        }
        for(Object holder:holders) {
            if(holder==null || oldRoots.contains(holder) || holder.getClass().getName().startsWith("org.springframework.")) continue;
            for(Class<?> c=holder.getClass();c!=null && c!=Object.class;c=c.getSuperclass()) for(Field field:c.getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                if(!field.trySetAccessible()) continue;
                Object value=field.get(holder);
                if(value instanceof Collection<?> collection)
                    require(collection.stream().noneMatch(previous::contains), "collection holder requires restart: "+field.getName());
                if(value instanceof Map<?,?> map)
                    require(map.keySet().stream().noneMatch(previous::contains) && map.values().stream().noneMatch(previous::contains),
                            "map holder requires restart: "+field.getName());
                if(value instanceof Object[] array)
                    require(Arrays.stream(array).noneMatch(previous::contains), "array holder requires restart: "+field.getName());
                if(value==null || !previous.contains(value)) continue;
                require(!Modifier.isFinal(field.getModifiers()), "final holder field requires restart: "+holder.getClass().getName()+"."+field.getName());
                result.add(new Link(holder,field,value));
            }
        }
        return result;
    }

    private static void recreate(Object context,Object factory,Map<String,Object> instances,List<Link> links) throws Exception {
        IdentityHashMap<Object,Object> replacements=new IdentityHashMap<>();
        for(var entry:instances.entrySet()) if(entry.getValue()!=null) {
            Object current=SpringReflection.getBean(context,entry.getKey());
            require(current.getClass()==entry.getValue().getClass(), "replacement type/proxy changed: "+entry.getKey());
            replacements.put(entry.getValue(),current);
        }
        for(var link:links) if(link.field.get(link.holder)==link.previous) link.field.set(link.holder,replacements.get(link.previous));
    }

    private static void resolvePlaceholders(Object factory,Object bd) throws Exception {
        ClassLoader loader=factory.getClass().getClassLoader();
        Class<?> resolver=Class.forName("org.springframework.util.StringValueResolver",false,loader);
        Object proxy=Proxy.newProxyInstance(loader,new Class<?>[]{resolver},(p,m,a)->{
            if(m.getName().equals("resolveStringValue")) return call(factory,"resolveEmbeddedValue",String.class,a[0]);
            if(m.getName().equals("toString")) return "Reclazz XML values";
            if(m.getName().equals("hashCode")) return System.identityHashCode(p);
            if(m.getName().equals("equals")) return p==a[0];
            throw new UnsupportedOperationException(m.getName());
        });
        Class<?> visitor=Class.forName("org.springframework.beans.factory.config.BeanDefinitionVisitor",false,loader);
        Object instance=visitor.getConstructor(resolver).newInstance(proxy);
        setSpring(instance,"visitBeanDefinition","org.springframework.beans.factory.config.BeanDefinition",bd);
    }
    private static void setSpring(Object object,String method,String type,Object value) throws Exception {
        call(object,method,Class.forName(type,false,object.getClass().getClassLoader()),value);
    }
    private static Object call(Object object,String method) throws Exception { return object.getClass().getMethod(method).invoke(object); }
    private static Object call(Object object,String method,Class<?> type,Object value) throws Exception { return object.getClass().getMethod(method,type).invoke(object,value); }
    private static boolean nonempty(String value) { return value!=null && !value.isEmpty(); }
    private static String[] sorted(String[] names) { Arrays.sort(names); return names; }
    private static void require(boolean condition,String reason) { if(!condition) throw new IllegalArgumentException(reason); }
}
