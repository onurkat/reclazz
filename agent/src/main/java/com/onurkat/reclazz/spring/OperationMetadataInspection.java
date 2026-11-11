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
    /** Same source class can still have custom parsers or global rollback defaults. */
    static String sourceConfiguration(Object source,boolean tx) {
        try {
            Field publicOnly=source.getClass().getDeclaredField("publicMethodsOnly");
            if(publicOnly.getType()!=boolean.class||!publicOnly.trySetAccessible())return "source configuration inspection unavailable";
            if(!publicOnly.getBoolean(source))return "non-public operation resolution";
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
                Object targetSource=advised.getMethod("getTargetSource").invoke(bean);
                if(!targetSource.getClass().getName().equals("org.springframework.aop.target.SingletonTargetSource"))unsupported="dynamic or custom proxy target";
                boolean found=false;
                for(Class<?> c=bean.getClass();c!=Object.class&&c!=null;c=c.getSuperclass()) {
                    for(Field field:c.getDeclaredFields())if(Modifier.isStatic(field.getModifiers())&&field.getType()==Method.class) {
                        if(!field.trySetAccessible())throw new UnsupportedOperationException("retained Method inaccessible");
                        Method held=(Method)field.get(null);
                        if(held==null)continue;
                        Method fresh;
                        try {fresh=owner.getMethod(held.getName(),held.getParameterTypes());}catch(NoSuchMethodException absent){continue;}
                        if(fresh.getReturnType()!=held.getReturnType()||fresh.getDeclaringClass()==Object.class)continue;
                        found=true;methods.add(new Lookup(held,target));methods.add(new Lookup(held,bean.getClass()));
                    }
                }
                if(!found)unsupported="retained proxy Methods unavailable";
            }
        }catch(Throwable failure){unsupported="proxy inspection: "+failure.getClass().getSimpleName();}
        return new Proxies(List.copyOf(methods),unsupported,ownsClass);
    }
}
