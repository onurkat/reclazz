/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.reflect.Field;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/** Compatible physical values survive logical record generations; other descriptors use virtual storage. */
final class RecordStorage {
    private static final ClassValue<Map<String,Field>> physical=new ClassValue<>() {
        @Override protected Map<String,Field> computeValue(Class<?> owner) {
            var result=new HashMap<String,Field>();
            for(Field field:ReflectionBridge.physicalDeclaredFields(owner)) {
                if(InjectedNames.isInjected(field.getName()))continue;
                if(!field.trySetAccessible())throw new IllegalStateException("Record field cannot be read: "+field.getName());
                result.put(key(field.getName(),field.getType()),field);
            }
            return Map.copyOf(result);
        }
    };
    private RecordStorage() { }
    private static String key(String name,Class<?> type){return name+":"+type.descriptorString();}
    static void prepare(Class<?> owner){physical.get(owner);FieldStore.captureExtField(owner);}
    static void put(Field logical,Object receiver,Object value) {
        if(Modifier.isStatic(logical.getModifiers()))throw new IllegalArgumentException("Record construction cannot write a static field");
        Class<?> owner=logical.getDeclaringClass();Object checked=JacksonBridge.checkedValue(logical.getType(),value);
        Field field=physical.get(owner).get(key(logical.getName(),logical.getType()));
        if(field!=null&&!Modifier.isStatic(field.getModifiers()))UnsafeAccess.putValue(receiver,field,checked);
        else FieldStore.putExtField(receiver,checked,owner.getName(),logical.getName(),logical.getType().descriptorString());
    }
    static MethodHandle getter(Field logical) {
        Class<?> owner=logical.getDeclaringClass();Field field=physical.get(owner).get(key(logical.getName(),logical.getType()));
        try {
            if(field!=null&&!Modifier.isStatic(field.getModifiers()))return MethodHandles.lookup().unreflectGetter(field);
            var virtual=MethodHandles.lookup().findStatic(FieldStore.class,"getExtField",
                    MethodType.methodType(Object.class,Object.class,String.class,String.class,String.class));
            return MethodHandles.insertArguments(virtual,1,owner.getName(),logical.getName(),logical.getType().descriptorString())
                    .asType(MethodType.methodType(logical.getType(),owner));
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Record component getter unavailable",failure);}
    }
    static void set(Field logical,Object receiver,Object value) {
        Class<?> owner=logical.getDeclaringClass();Field field=physical.get(owner).get(key(logical.getName(),logical.getType()));
        if(field!=null&&Modifier.isStatic(field.getModifiers())==Modifier.isStatic(logical.getModifiers())) {
            try{field.set(receiver,value);return;}catch(IllegalAccessException failure){throw new IllegalStateException("Physical snapshot field write failed",failure);}
        }
        String descriptor=logical.getType().descriptorString();
        if(Modifier.isStatic(logical.getModifiers()))FieldStore.putStaticExtField(owner,logical.getName(),descriptor,value);
        else FieldStore.putExtField(receiver,value,owner.getName(),logical.getName(),descriptor);
    }
    static Object get(Field logical,Object receiver) {
        Class<?> owner=logical.getDeclaringClass();Field field=physical.get(owner).get(key(logical.getName(),logical.getType()));
        if(field!=null&&Modifier.isStatic(field.getModifiers())==Modifier.isStatic(logical.getModifiers())) {
            try{return field.get(receiver);}
            catch(IllegalAccessException failure){throw new IllegalStateException("Record physical field read failed",failure);}
        }
        String descriptor=logical.getType().descriptorString();
        return Modifier.isStatic(logical.getModifiers())?FieldStore.getStaticExtField(owner,logical.getName(),descriptor)
                :FieldStore.getExtField(receiver,owner.getName(),logical.getName(),descriptor);
    }
}
