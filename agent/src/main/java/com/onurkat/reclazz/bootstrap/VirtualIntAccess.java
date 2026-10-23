/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;
import java.lang.reflect.Field;

/** Accessors share FieldStore's instance monitor and copy-on-write storage. */
public final class VirtualIntAccess {
    private VirtualIntAccess() { }
    private static void receiver(Class<?> coordinate,Object receiver){coordinate.cast(java.util.Objects.requireNonNull(receiver));}
    public static int get(Field field,Class<?> coordinate,Object receiver){
        receiver(coordinate,receiver);return (Integer)FieldStore.getExtField(receiver,field.getDeclaringClass().getName(),field.getName(),"I");
    }
    public static void set(Field field,Class<?> coordinate,Object receiver,int value){
        receiver(coordinate,receiver);FieldStore.putExtField(receiver,value,field.getDeclaringClass().getName(),field.getName(),"I");
    }
    public static boolean cas(Field field,Class<?> coordinate,Object receiver,int expected,int update){
        receiver(coordinate,receiver);return FieldStore.compareAndSetInt(receiver,field.getDeclaringClass().getName(),field.getName(),expected,update);
    }
    /** VarForm resolves these signatures to advertise exactly the implemented modes. */
    public static int get(VarHandle ignored,Object receiver){throw new UnsupportedOperationException();}
    public static void set(VarHandle ignored,Object receiver,int value){throw new UnsupportedOperationException();}
    public static boolean compareAndSet(VarHandle ignored,Object receiver,int expected,int update){throw new UnsupportedOperationException();}
    static MethodHandle handle(Field field,Class<?> coordinate,VarHandle.AccessMode mode) {
        try {
            String name;MethodType type;
            switch(mode) {
                case GET->{name="get";type=MethodType.methodType(int.class,Field.class,Class.class,Object.class);}
                case SET->{name="set";type=MethodType.methodType(void.class,Field.class,Class.class,Object.class,int.class);}
                case COMPARE_AND_SET->{name="cas";type=MethodType.methodType(boolean.class,Field.class,Class.class,Object.class,int.class,int.class);}
                default->throw new UnsupportedOperationException(mode.methodName());
            }
            MethodHandle result=MethodHandles.insertArguments(MethodHandles.lookup().findStatic(VirtualIntAccess.class,name,type),0,field,coordinate);
            result=result.asType(result.type().changeParameterType(0,coordinate));
            return MethodHandles.dropArguments(result,0,VarHandle.class);
        }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
}
