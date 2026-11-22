/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import java.lang.reflect.*;
import java.util.*;

/** Reflective operation/proxy snapshots; all references are local to one repair pass. */
final class OperationMetadataInspection {
    record Lookup(Method method, Class<?> target) { }
    record Proxies(List<Lookup> lookups, String unsupported, boolean ownsClass) { }
    private OperationMetadataInspection() { }
    static Object call(Object receiver,String name) throws Exception { return receiver.getClass().getMethod(name).invoke(receiver); }
    static List<Map<String,Object>> answer(Object value,boolean tx) throws Exception {
        if(value==null)return List.of();
        if(tx) {
            if(!value.getClass().getName().equals("org.springframework.transaction.interceptor.RuleBasedTransactionAttribute"))
                throw new UnsupportedOperationException("custom transaction attribute");
            Map<String,Object> map=new LinkedHashMap<>();map.put("kind","Transactional");
            map.put("qualifier",Objects.toString(call(value,"getQualifier"),""));
            map.put("labels",new TreeSet<>((Collection<String>)call(value,"getLabels")));
            map.put("readOnly",call(value,"isReadOnly"));map.put("timeout",call(value,"getTimeout"));
            map.put("propagation",call(value,"getPropagationBehavior"));map.put("isolation",call(value,"getIsolationLevel"));
            List<String> rules=new ArrayList<>();
            for(Object rule:(List<?>)call(value,"getRollbackRules")) {
                String n=rule.getClass().getName();
                if(!n.equals("org.springframework.transaction.interceptor.RollbackRuleAttribute")&&!n.equals("org.springframework.transaction.interceptor.NoRollbackRuleAttribute"))
                    throw new UnsupportedOperationException("custom rollback rule");
                Object exceptionType=null;
                if(OperationMetadataExpectation.typedRules(rule.getClass().getClassLoader())) {
                    Field field=Class.forName("org.springframework.transaction.interceptor.RollbackRuleAttribute",false,rule.getClass().getClassLoader()).getDeclaredField("exceptionType");
                    if(!field.trySetAccessible())throw new UnsupportedOperationException("rollback rule type inaccessible");
                    exceptionType=field.get(rule);
                }
                rules.add((n.endsWith(".NoRollbackRuleAttribute")?"-":"+")+(exceptionType==null?"pattern:":"type:")+call(rule,"getExceptionName"));
            }
            map.put("rules",List.copyOf(rules));return List.of(Map.copyOf(map));
        }
        List<Map<String,Object>> out=new ArrayList<>();
        for(Object operation:(Collection<?>)value) {
            String kind=operation.getClass().getSimpleName();
            if(!Set.of("CacheableOperation","CachePutOperation","CacheEvictOperation").contains(kind)
                    ||!operation.getClass().getName().equals("org.springframework.cache.interceptor."+kind))
                throw new UnsupportedOperationException("custom cache operation");
            Map<String,Object> map=new LinkedHashMap<>();map.put("kind",kind.substring(0,kind.length()-9));
            map.put("names",new TreeSet<>((Collection<String>)call(operation,"getCacheNames")));
            for(String key:List.of("key","keyGenerator","cacheManager","cacheResolver","condition"))
                map.put(key,call(operation,"get"+Character.toUpperCase(key.charAt(0))+key.substring(1)));
            if(kind.equals("CacheEvictOperation")) {
                map.put("allEntries",call(operation,"isCacheWide"));map.put("beforeInvocation",call(operation,"isBeforeInvocation"));
            } else map.put("unless",call(operation,"getUnless"));
            if(kind.equals("CacheableOperation"))map.put("sync",call(operation,"isSync"));
            out.add(Map.copyOf(map));
        }
        return List.copyOf(out);
    }
    static Map<Object,Boolean> operationSources(Object[] advisors,ClassLoader loader)throws Exception {
        return operationSources(advisors,loader,false);
    }
    /** Creation preflight may inspect native pointcuts without resolving their lazy advice bean. */
    static Map<Object,Boolean> operationSources(Object[] advisors,ClassLoader loader,boolean creationPreflight)throws Exception {
        var sources=new IdentityHashMap<Object,Boolean>();Class<?> contract=Class.forName("org.springframework.aop.Advisor",false,loader);
        Class<?> tx=null,cache=null;
        try{tx=Class.forName("org.springframework.transaction.interceptor.TransactionInterceptor",false,loader);}catch(ClassNotFoundException absent){}
        try{cache=Class.forName("org.springframework.cache.interceptor.CacheInterceptor",false,loader);}catch(ClassNotFoundException absent){}
        for(Object item:advisors) {
            Object advice=item;
            if(contract.isInstance(item)) {
                advice=com.onurkat.reclazz.util.Reflect.readField(item,"advice");
                if(advice==null) {
                    String kind=item.getClass().getName();
                    boolean nativeTransaction=kind.equals("org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor");
                    boolean nativeCache=kind.equals("org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor");
                    if(creationPreflight&&nativeCache)throw new UnsupportedOperationException("cold cache advice initialization is not verified: "+kind);
                    if(creationPreflight&&nativeTransaction) {
                        Object pointcut=item.getClass().getMethod("getPointcut").invoke(item);
                        Object source=com.onurkat.reclazz.util.Reflect.readField(pointcut,nativeTransaction?"transactionAttributeSource":"cacheOperationSource");
                        if(source!=null){sources.put(source,nativeTransaction);continue;}
                    }
                    if(item.getClass().getName().startsWith("org.springframework.aop.aspectj."))continue; // Aspect descriptors contain no native transaction/cache interceptor.
                    throw new UnsupportedOperationException("configured advisor advice is unavailable without creating a cold bean: "+item.getClass().getName());
                }
            }
            if(tx!=null&&tx.isInstance(advice))sources.put(advice.getClass().getMethod("getTransactionAttributeSource").invoke(advice),true);
            if(cache!=null&&cache.isInstance(advice))sources.put(advice.getClass().getMethod("getCacheOperationSource").invoke(advice),false);
        }
        return sources;
    }
    /** Same source class can still have custom parsers or global rollback defaults. */
    static String sourceConfiguration(Object source,boolean tx) {return sourceConfiguration(source,tx,false);}
    /** Only a verified newly-created proxy graph can admit native non-public source resolution. */
    static String sourceConfiguration(Object source,boolean tx,boolean nativeRecreated) {
        try {
            Field publicOnly=source.getClass().getDeclaredField("publicMethodsOnly");
            if(publicOnly.getType()!=boolean.class||!publicOnly.trySetAccessible())return "source configuration inspection unavailable";
            if(!publicOnly.getBoolean(source)&&!nativeRecreated)return "non-public operation resolution";
            Field parsers=source.getClass().getDeclaredField("annotationParsers");
            if(!parsers.trySetAccessible()||!(parsers.get(source) instanceof Set<?> configured))return "parser configuration inspection unavailable";
            Set<String> supported=tx?Set.of("org.springframework.transaction.annotation.SpringTransactionAnnotationParser",
                    "org.springframework.transaction.annotation.JtaTransactionAnnotationParser","org.springframework.transaction.annotation.Ejb3TransactionAnnotationParser")
                    :Set.of("org.springframework.cache.annotation.SpringCacheAnnotationParser");
            for(Object parser:configured)if(parser==null||!supported.contains(parser.getClass().getName()))return "custom annotation parser";
            if(tx)try {
                Field rules=source.getClass().getDeclaredField("defaultRollbackRules");
                if(!rules.trySetAccessible())return "default rollback inspection unavailable";
                Object value=rules.get(source);
                if(value!=null&&(!(value instanceof Set<?> set)||!set.isEmpty()))return "configured default rollback rules";
            }catch(NoSuchFieldException legacy){/* Spring5 has no global default rules. */}
            return null;
        }catch(Throwable failure){return "source configuration inspection unavailable";}
    }
    static Proxies methods(Object context,Class<?> owner) {
        List<Lookup> methods=new ArrayList<>();String unsupported=null;boolean ownsClass=false;
        for(Method method:owner.getDeclaredMethods())if(Modifier.isPublic(method.getModifiers())&&!method.isSynthetic())
            methods.add(new Lookup(method,owner));
        try {
            Object factory=SpringBeans.getBeanFactory(context);
            if(factory==null)throw new UnsupportedOperationException("singleton factory unavailable");
            ClassLoader loader=owner.getClassLoader();
            Class<?> advised=Class.forName("org.springframework.aop.framework.Advised",false,loader);
            for(String name:(String[])call(factory,"getSingletonNames")) {
                Object bean=factory.getClass().getMethod("getSingleton",String.class).invoke(factory,name);
                if(bean==null)continue;
                if(!advised.isInstance(bean)) {
                    if(owner.isInstance(bean))ownsClass=true;
                    if(owner.isInstance(bean)&&bean.getClass()!=owner)unsupported="opaque or subclass proxy";
                    continue;
                }
                Class<?> target=(Class<?>)advised.getMethod("getTargetClass").invoke(bean);
                if(target==null||!owner.isAssignableFrom(target))continue;
                ownsClass=true;
                var chain=SpringProxyReferences.chain(bean);
                if(chain.target()==null)unsupported="dynamic or custom proxy target";
                for(Object layer:chain.proxies()) {
                Class<?> layerTarget=(Class<?>)advised.getMethod("getTargetClass").invoke(layer);
                boolean found=false;
                for(Class<?> c=layer.getClass();c!=Object.class&&c!=null;c=c.getSuperclass()) {
                    for(Field field:c.getDeclaredFields())if(Modifier.isStatic(field.getModifiers())&&field.getType()==Method.class) {
                        if(!field.trySetAccessible())throw new UnsupportedOperationException("retained Method inaccessible");
                        Method held=(Method)field.get(null);
                        if(held==null)continue;
                        Method fresh;
                        try {fresh=owner.getMethod(held.getName(),held.getParameterTypes());}catch(NoSuchMethodException absent){continue;}
                        if(fresh.getReturnType()!=held.getReturnType()||fresh.getDeclaringClass()==Object.class)continue;
                        found=true;methods.add(new Lookup(held,layerTarget));methods.add(new Lookup(held,layer.getClass()));
                    }
                }
                if(!found)unsupported="retained proxy Methods unavailable";
                }
            }
        }catch(Throwable failure){unsupported="proxy inspection: "+failure.getClass().getSimpleName();}
        return new Proxies(List.copyOf(methods),unsupported,ownsClass);
    }
}
