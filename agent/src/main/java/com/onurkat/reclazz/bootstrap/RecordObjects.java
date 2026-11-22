/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.lang.runtime.ObjectMethods;
import java.util.ArrayList;

/** Generated record ObjectMethods use one logical snapshot and the JDK's own component semantics. */
public final class RecordObjects {
    private RecordObjects() { }
    record Bodies(MethodHandle hash,MethodHandle equal,MethodHandle text) { }
    static Bodies prepare(Class<?> owner,RecordSchema.Members members) {
        var getters=new ArrayList<MethodHandle>();var names=new ArrayList<String>();
        for(var component:members.components()) {
            var field=members.fields().stream().filter(f->f.getName().equals(component.name())&&f.getType()==component.type()
                    &&!Modifier.isStatic(f.getModifiers())).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Record component field missing: "+component.name()));
            getters.add(RecordStorage.getter(field));names.add(component.name());
        }
        try {
            var handles=getters.toArray(MethodHandle[]::new);String joined=String.join(";",names);
            var hash=(MethodHandle)ObjectMethods.bootstrap(MethodHandles.lookup(),"hashCode",MethodHandle.class,owner,joined,handles);
            var equal=(MethodHandle)ObjectMethods.bootstrap(MethodHandles.lookup(),"equals",MethodHandle.class,owner,joined,handles);
            var text=(MethodHandle)ObjectMethods.bootstrap(MethodHandles.lookup(),"toString",MethodHandle.class,owner,joined,handles);
            return new Bodies(hash.asType(MethodType.methodType(int.class,Object.class)),
                    equal.asType(MethodType.methodType(boolean.class,Object.class,Object.class)),
                    text.asType(MethodType.methodType(String.class,Object.class)));
        }catch(Throwable failure){throw new IllegalStateException("Record ObjectMethods unavailable",failure);}
    }
    private static void validateLookup(MethodHandles.Lookup lookup,Class<?> owner) {
        // A public bridge must not let an unrelated caller bypass private/custom record accessors.
        if(!owner.isRecord()||!lookup.hasFullPrivilegeAccess()||lookup.lookupClass().getClassLoader()!=owner.getClassLoader()
                ||lookup.lookupClass().getNestHost()!=owner.getNestHost())
            throw new SecurityException("Record ObjectMethods require the record's own nest lookup");
    }
    private static Bodies current(Class<?> owner) {
        var state=RecordSchema.virtualState(owner);
        if(state==null)throw new IllegalStateException("No logical record ObjectMethods snapshot");
        return state.objectsInternal();
    }
    /** The VM supplies the actual hidden companion lookup, without a caller-sensitive reflective call. */
    public static CallSite bootstrap(MethodHandles.Lookup caller,String name,MethodType type,Class<?> owner)
            throws ReflectiveOperationException {
        validateLookup(caller,owner);
        MethodType expected=switch(name) {
            case "hashCode" -> MethodType.methodType(int.class,owner);
            case "equals" -> MethodType.methodType(boolean.class,owner,Object.class);
            case "toString" -> MethodType.methodType(String.class,owner);
            default -> throw new IllegalArgumentException("Unknown record ObjectMethods site: "+name);
        };
        if(!expected.equals(type))throw new IllegalArgumentException("Record ObjectMethods site type changed");
        MethodType implementation=type.changeParameterType(0,Object.class).insertParameterTypes(0,Class.class);
        var target=MethodHandles.lookup().findStatic(RecordObjects.class,"logical$"+name,implementation);
        return new ConstantCallSite(MethodHandles.insertArguments(target,0,owner).asType(type));
    }
    private static int logical$hashCode(Class<?> owner,Object receiver)throws Throwable {
        Bodies bodies=current(owner);return (int)bodies.hash.invokeExact(receiver);
    }
    private static boolean logical$equals(Class<?> owner,Object receiver,Object other)throws Throwable {
        Bodies bodies=current(owner);return (boolean)bodies.equal.invokeExact(receiver,other);
    }
    private static String logical$toString(Class<?> owner,Object receiver)throws Throwable {
        Bodies bodies=current(owner);return (String)bodies.text.invokeExact(receiver);
    }
    public static int hashCode(MethodHandles.Lookup lookup,Class<?> owner,Object receiver)throws Throwable {
        validateLookup(lookup,owner);return logical$hashCode(owner,receiver);
    }
    public static boolean equals(MethodHandles.Lookup lookup,Class<?> owner,Object receiver,Object other)throws Throwable {
        validateLookup(lookup,owner);return logical$equals(owner,receiver,other);
    }
    public static String toString(MethodHandles.Lookup lookup,Class<?> owner,Object receiver)throws Throwable {
        validateLookup(lookup,owner);return logical$toString(owner,receiver);
    }
}
