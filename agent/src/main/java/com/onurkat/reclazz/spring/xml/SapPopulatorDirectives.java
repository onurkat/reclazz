/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Replays verified eager SAP list directives; ambiguous inverse histories retain restart fallback. */
final class SapPopulatorDirectives {
    private SapPopulatorDirectives() { }

    static final String MODIFY = "de.hybris.platform.converters.impl.ModifyPopulatorList";
    private static final List<String> EDITABLE = List.of("add", "remove");

    /** An accepted edit, applied after new beans of the same save are registered. */
    record Edit(String beanName, Object next) { }

    /** Only verified edits of an exact, eager, created directive are admitted. */
    static boolean supported(Object factory, String name, Object previous, Object next) {
        try {
            if (previous == null || next == null) return false;
            String type=SpringReflection.getBeanClassName(previous);
            if (!type.equals(SpringReflection.getBeanClassName(next))
                    || !(MODIFY.equals(type)||SapListMergeDirectives.TYPE.equals(type))) return false;
            if (SpringReflection.getParentName(previous) != null || SpringReflection.getParentName(next) != null) return false;
            if (Boolean.TRUE.equals(call(previous, "isLazyInit")) || Boolean.TRUE.equals(call(next, "isLazyInit"))) return false;
            Object live = SpringReflection.getExistingSingleton(factory, name);
            if (live == null || !type.equals(live.getClass().getName())) return false;
            if (SapListMergeDirectives.TYPE.equals(type))return SapListMergeDirectives.supported(factory,name,previous,next,live);
            for (String property : EDITABLE) {
                Object value = rawValue(next, property);
                if (value != null && !localReference(value)) return false;
            }
            XmlRawValueComparator comparator = new XmlRawValueComparator(next.getClass().getClassLoader());
            return comparator.verifiedStandardDefinition(withEditable(previous, next))
                    .equals(comparator.verifiedStandardDefinition(withEditable(next,next)))
                    && SapSharedDirectives.refusal(factory,name,previous,live)==null;
        } catch (Exception | LinkageError unverifiable) {
            return false;
        }
    }

    /** Reverses the directive's old effect and applies the new one; all or nothing. */
    static void apply(Object factory, Edit edit) throws Exception {
        Object directive = SpringReflection.getExistingSingleton(factory, edit.beanName());
        if (directive == null) throw new IllegalStateException("directive is not created");
        if (SapSharedDirectives.apply(factory, edit, directive)) return;
        if (SapListMergeDirectives.TYPE.equals(directive.getClass().getName())) {SapListMergeDirectives.apply(factory,edit,directive);return;}
        TargetSnapshot snapshot=new TargetSnapshot(()->call(directive,"getList"),false);
        Object oldAdd=call(directive,"getAdd"),oldRemove=call(directive,"getRemove");
        Object newAdd=resolve(factory,rawValue(edit.next(),"add")),newRemove=resolve(factory,rawValue(edit.next(),"remove"));
        List<Object> applied=snapshot.copy();
        if(oldAdd!=null)applied.remove(oldAdd);
        if(oldRemove!=null&&!applied.contains(oldRemove))applied.add(oldRemove);
        if(newRemove!=null)applied.remove(newRemove);
        if(newAdd!=null)applied.add(newAdd);
        var properties=new LinkedHashMap<String,Object>();properties.put("add",newAdd);properties.put("remove",newRemove);
        publish(factory,edit,directive,properties,snapshot,applied);
    }

    @FunctionalInterface interface TargetLookup { Object get() throws Exception; }

    /** Capture before any candidate bean resolution, and never erase intervening changes. */
    static final class TargetSnapshot {
        private final TargetLookup lookup;
        private final Object owner;
        private final boolean direct;
        private final List<Object> original, before;
        @SuppressWarnings("unchecked") TargetSnapshot(TargetLookup lookup,boolean direct)throws Exception {
            this.lookup=lookup;this.direct=direct;owner=lookup.get();
            original=direct?(List<Object>)owner:populators(owner);
            if(direct)requireWritable(original);
            before=new ArrayList<>(original);
        }
        List<Object> copy(){return new ArrayList<>(before);}
        void verify()throws Exception {
            if(lookup.get()!=owner||(!direct&&populators(owner)!=original)||original.size()!=before.size())changed();
            for(int i=0;i<before.size();i++)if(original.get(i)!=before.get(i))changed();
        }
        private static void changed(){throw new IllegalStateException("live directive target list changed during reload");}
        void publish(List<Object> desired)throws Exception {
            if(direct) {
                if(desired.size()!=before.size())throw new IllegalArgumentException("direct list publication requires unchanged cardinality");
                // No structural change: an existing reader never sees a cleared/empty list.
                for(int i=0;i<desired.size();i++)original.set(i,desired.get(i));
            } else set(owner,"setPopulators",new ArrayList<>(desired));
        }
        void restore()throws Exception {
            if(direct)for(int i=0;i<before.size();i++)original.set(i,before.get(i));
            else set(owner,"setPopulators",original);
        }
    }

    /** Publish the calculated list, directive and raw metadata, restoring on setter/cache failure. */
    static void publish(Object factory,Edit edit,Object directive,Map<String,Object> properties,
                        TargetSnapshot snapshot,List<Object> desired)throws Exception {
        Object definition=SpringReflection.getBeanDefinition(factory,edit.beanName());
        Object backup=call(definition,"cloneBeanDefinition");
        var oldProperties=new LinkedHashMap<String,Object>();
        for(String property:properties.keySet())oldProperties.put(property,call(directive,"get"+capital(property)));
        // This is outside rollback: an external edit belongs to its writer, not this reload.
        snapshot.verify();
        try {
            snapshot.publish(desired);
            for(var entry:properties.entrySet())set(directive,"set"+capital(entry.getKey()),entry.getValue());
            Object values=call(definition,"getPropertyValues");
            for(String property:properties.keySet()) {
                Object value=rawValue(edit.next(),property);
                if(value==null)call(values,"removePropertyValue",property);else call(values,"add",property,value);
            }
            call(factory,"clearMetadataCache");
        } catch(Exception|Error failure) {
            try{snapshot.restore();}catch(Exception|Error rollback){failure.addSuppressed(rollback);}
            for(var entry:oldProperties.entrySet())try{set(directive,"set"+capital(entry.getKey()),entry.getValue());}catch(Exception|Error rollback){failure.addSuppressed(rollback);}
            try{call(definition,"setPropertyValues",call(backup,"getPropertyValues"));}catch(Exception|Error rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
    }
    private static String capital(String name){return Character.toUpperCase(name.charAt(0))+name.substring(1);}
    @SuppressWarnings("unchecked") static List<Object> populators(Object target)throws Exception{return (List<Object>)call(target,"getPopulators");}
    static void requireWritable(List<?> list){if(list==null||list.getClass()!=ArrayList.class)throw new IllegalArgumentException("target list implementation cannot be restored exactly");}
    static int uniqueAddition(List<?> list,Object addition) {
        if(addition==null)throw new IllegalArgumentException("old addition is unavailable");
        int found=-1;
        for(int i=0;i<list.size();i++)if(Objects.equals(list.get(i),addition)) {
            if(found!=-1||list.get(i)!=addition)throw new IllegalArgumentException("old addition is ambiguous");found=i;
        }
        if(found<0)throw new IllegalArgumentException("old addition is missing");return found;
    }

    static boolean localReference(Object reference)throws Exception {
        if(reference==null||!reference.getClass().getName().equals("org.springframework.beans.factory.config.RuntimeBeanReference"))return false;
        Object name=call(reference,"getBeanName");
        return name instanceof String text&&!text.isBlank()&&!text.contains("${")&&!text.contains("#{")
                &&!Boolean.TRUE.equals(call(reference,"isToParent"))&&call(reference,"getBeanType")==null;
    }
    static Object resolve(Object factory, Object reference) throws Exception {
        if (reference == null) return null;
        return call(factory, "getBean", (String) call(reference, "getBeanName"));
    }

    static Object withEditable(Object previous, Object next) throws Exception {
        return withEditable(previous,next,EDITABLE);
    }
    static Object withEditable(Object previous,Object next,List<String> editable)throws Exception {
        Object copy = call(previous, "cloneBeanDefinition");
        Object values = call(copy, "getPropertyValues");
        // These exact SDK setters assign independent fields. Canonicalize only their order;
        // retain PropertyValue metadata/optional flags and every noneditable header/property.
        for(String property:editable)call(values,"removePropertyValue",property);
        Object nextValues=call(next,"getPropertyValues");
        for(String property:editable) {
            Object holder=call(nextValues,"getPropertyValue",property);
            if(holder!=null)call(values,"addPropertyValue",holder);
        }
        return copy;
    }

    static Object rawValue(Object definition, String property) throws Exception {
        Object holder = call(call(definition, "getPropertyValues"), "getPropertyValue", property);
        return holder == null ? null : call(holder, "getValue");
    }

    static void set(Object target, String setter, Object value) throws Exception {
        for (Method method : target.getClass().getMethods())
            if (method.getName().equals(setter) && method.getParameterCount() == 1) { method.invoke(target, value); return; }
        throw new NoSuchMethodException(setter);
    }

    static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean fits = true;
            for (int i = 0; i < args.length && fits; i++) fits = args[i] == null || types[i].isInstance(args[i]);
            if (!fits) continue;
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw failure;
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}
