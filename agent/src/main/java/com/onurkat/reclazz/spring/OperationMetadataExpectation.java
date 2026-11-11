/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.*;

/** An independent, deliberately bounded JDK-reflection oracle. Never calls a Spring parser. */
final class OperationMetadataExpectation {
    static final String CACHE = "org.springframework.cache.annotation.";
    static final String TX = "org.springframework.transaction.annotation.Transactional";
    record Expected(List<Map<String,Object>> operations, String unsupported) { }
    private OperationMetadataExpectation() { }

    static boolean relevant(Set<String> descriptors, ClassLoader loader) throws Exception {
        if (descriptors == null) return false;
        Set<String> seen = new HashSet<>();
        for (String descriptor : descriptors) {
            String name = descriptor.substring(1, descriptor.length()-1).replace('/', '.');
            if (relevantType(name, loader, seen, 0)) return true;
        }
        return false;
    }
    static boolean hasComposed(Set<String> descriptors, ClassLoader loader) throws Exception {
        if(descriptors==null)return false;
        for(String descriptor:descriptors) {
            String name=descriptor.substring(1,descriptor.length()-1).replace('/','.');
            if(!name.equals(TX)&&!name.startsWith(CACHE)&&relevantType(name,loader,new HashSet<>(),0))return true;
        }
        return false;
    }
    private static boolean relevantType(String name, ClassLoader loader, Set<String> seen, int depth) throws Exception {
        return relevantKind(name,loader,seen,depth,null);
    }
    private static boolean relevantKind(String name, ClassLoader loader, Set<String> seen, int depth, Boolean tx) throws Exception {
        boolean directTx=name.equals(TX);
        boolean directCache=Set.of(CACHE+"Cacheable",CACHE+"CachePut",CACHE+"CacheEvict",CACHE+"Caching",CACHE+"CacheConfig").contains(name);
        if(directTx||directCache)return tx==null||(tx?directTx:directCache);
        if(name.startsWith("java.lang.annotation.")||!seen.add(name))return false;
        if(depth>=16)throw new IllegalArgumentException("annotation hierarchy exceeds inspection bound");
        for(Annotation a:Class.forName(name,false,loader).getDeclaredAnnotations())
            if(relevantKind(a.annotationType().getName(),loader,seen,depth+1,tx))return true;
        return false;
    }
    static Expected read(Class<?> owner, Method method, boolean tx) {
        try {
            if (method.isBridge() || method.isSynthetic() || owner.getInterfaces().length != 0
                    || (owner.getSuperclass()!=Object.class && owner.getSuperclass()!=null))
                return new Expected(List.of(),"interface, inherited or bridge metadata");
            for (AnnotatedElement e : List.of(owner, method)) for (Annotation a : e.getDeclaredAnnotations()) {
                String n=a.annotationType().getName();
                if(tx&&(n.startsWith("javax.transaction.")||n.startsWith("jakarta.transaction.")
                        ||n.startsWith("javax.ejb.")||n.startsWith("jakarta.ejb.")))
                    return new Expected(List.of(),"alternative transaction declaration");
                if (!n.equals(TX) && !n.startsWith(CACHE)
                        && relevantKind(n,owner.getClassLoader(),new HashSet<>(),0,tx))
                    return new Expected(List.of(),"composed metadata");
            }
            if(tx) {
                Annotation a=find(method,TX); if(a==null)a=find(owner,TX);
                return new Expected(a==null?List.of():List.of(transaction(a,owner.getClassLoader())),null);
            }
            List<Annotation> annotations = caches(method);
            if(annotations.isEmpty()) annotations=caches(owner);
            Annotation defaults=find(owner,CACHE+"CacheConfig");
            List<Map<String,Object>> out=new ArrayList<>();
            for(Annotation a:annotations) out.add(cache(a,defaults));
            return new Expected(List.copyOf(out),null);
        } catch(Throwable failure) { return new Expected(List.of(),"annotation inspection: "+failure.getClass().getSimpleName()); }
    }
    static Annotation find(AnnotatedElement element,String name) {
        for(Annotation a:element.getDeclaredAnnotations())if(a.annotationType().getName().equals(name))return a;
        return null;
    }
    private static List<Annotation> caches(AnnotatedElement e) throws Exception {
        List<Annotation> out=new ArrayList<>();
        for(String kind:List.of("Cacheable","CacheEvict","CachePut")) {
            Annotation a=find(e,CACHE+kind);if(a!=null)out.add(a);
        }
        Annotation grouped=find(e,CACHE+"Caching");
        if(grouped!=null) for(String group:List.of("cacheable","evict","put"))
            out.addAll(Arrays.asList((Annotation[])value(grouped,group)));
        return out;
    }
    static Object value(Annotation a,String member) throws Exception {
        return a.annotationType().getMethod(member).invoke(a);
    }
    private static String alias(Annotation a,String first,String second) throws Exception {
        String x=(String)value(a,first), y=(String)value(a,second);
        if(!x.isEmpty()&&!y.isEmpty()&&!x.equals(y))throw new IllegalArgumentException("conflicting aliases");
        return x.isEmpty()?y:x;
    }
    private static Map<String,Object> cache(Annotation a,Annotation defaults) throws Exception {
        String kind=a.annotationType().getSimpleName();
        Set<String> allowed=new HashSet<>(List.of("value","cacheNames","key","keyGenerator","cacheManager","cacheResolver","condition"));
        allowed.addAll(kind.equals("CacheEvict")?List.of("allEntries","beforeInvocation"):List.of("unless"));
        if(kind.equals("Cacheable"))allowed.add("sync");
        rejectUnknown(a,allowed);
        Map<String,Object> map=new LinkedHashMap<>();map.put("kind",kind);
        String[] names=(String[])value(a,"cacheNames"), alias=(String[])value(a,"value");
        if(names.length>0&&alias.length>0&&!Arrays.equals(names,alias))throw new IllegalArgumentException("conflicting cache names");
        if(names.length==0)names=alias;
        if(names.length==0&&defaults!=null)names=(String[])value(defaults,"cacheNames");
        map.put("names",new TreeSet<>(Arrays.asList(names)));
        for(String member:List.of("key","keyGenerator","cacheManager","cacheResolver","condition"))map.put(member,value(a,member));
        if(defaults!=null) {
            rejectUnknown(defaults,Set.of("cacheNames","keyGenerator","cacheManager","cacheResolver"));
            if(((String)map.get("key")).isBlank()&&((String)map.get("keyGenerator")).isBlank())map.put("keyGenerator",value(defaults,"keyGenerator"));
            if(((String)map.get("cacheManager")).isBlank()&&((String)map.get("cacheResolver")).isBlank()) {
                String resolver=(String)value(defaults,"cacheResolver");
                if(!resolver.isBlank())map.put("cacheResolver",resolver);else map.put("cacheManager",value(defaults,"cacheManager"));
            }
        }
        if(kind.equals("CacheEvict")) {map.put("allEntries",value(a,"allEntries"));map.put("beforeInvocation",value(a,"beforeInvocation"));}
        else map.put("unless",value(a,"unless"));
        if(kind.equals("Cacheable"))map.put("sync",value(a,"sync"));
        return Map.copyOf(map);
    }
    private static Map<String,Object> transaction(Annotation a,ClassLoader loader) throws Exception {
        rejectUnknown(a,Set.of("value","transactionManager","label","propagation","isolation","timeout","timeoutString","readOnly","rollbackFor","rollbackForClassName","noRollbackFor","noRollbackForClassName"));
        String timeoutString=(String)value(a,"timeoutString");
        if(!timeoutString.isBlank())throw new IllegalArgumentException("resolved timeoutString");
        Map<String,Object> map=new LinkedHashMap<>();map.put("kind","Transactional");
        String qualifier=alias(a,"value","transactionManager");
        if(qualifier.contains("${")||qualifier.contains("#{"))throw new IllegalArgumentException("resolved qualifier");
        for(String label:(String[])value(a,"label"))if(label.contains("${")||label.contains("#{"))throw new IllegalArgumentException("resolved label");
        map.put("qualifier",qualifier);
        map.put("labels",new TreeSet<>(Arrays.asList((String[])value(a,"label"))));
        map.put("readOnly",value(a,"readOnly"));map.put("timeout",value(a,"timeout"));
        for(String member:List.of("propagation","isolation")) {
            Object e=value(a,member);map.put(member,e.getClass().getMethod("value").invoke(e));
        }
        List<String> rules=new ArrayList<>();
        for(String member:List.of("rollbackFor","rollbackForClassName","noRollbackFor","noRollbackForClassName")) {
            Object[] values=(Object[])value(a,member);
            for(Object v:values)rules.add((member.startsWith("no")?"-":"+")
                    +(v instanceof Class<?> && typedRules(loader)?"type:":"pattern:")+(v instanceof Class<?> c?c.getName():v));
        }
        map.put("rules",List.copyOf(rules));return Map.copyOf(map);
    }
    static boolean typedRules(ClassLoader loader) throws Exception {
        try {
            java.lang.reflect.Field field=Class.forName("org.springframework.transaction.interceptor.RollbackRuleAttribute",false,loader).getDeclaredField("exceptionType");
            if(field.getType()!=Class.class)throw new UnsupportedOperationException("rollback rule type layout");
            return true;
        }catch(NoSuchFieldException legacy){return false;}
    }
    private static void rejectUnknown(Annotation a,Set<String> allowed) throws Exception {
        for(Method m:a.annotationType().getDeclaredMethods()) if(!allowed.contains(m.getName())
                && !Objects.deepEquals(m.invoke(a),m.getDefaultValue()))throw new IllegalArgumentException("unknown attribute "+m.getName());
    }
}
