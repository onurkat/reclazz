/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.function.Function;

/** Annotation values are parsed by a real carrier; its logical types belong to the original declaration. */
final class ReflectionTypes {
    private ReflectionTypes() { }

    static AnnotatedType wrap(AnnotatedType source, GenericDeclaration carrier, GenericDeclaration logical) {return wrap(source,carrier,logical,null);}
    static AnnotatedType wrap(AnnotatedType source, GenericDeclaration carrier, GenericDeclaration logical,Function<TypeVariable<?>,Type> resolver) {
        if (source == null) return null;
        if (source instanceof AnnotatedParameterizedType p) return new Parameterized(p,carrier,logical,resolver);
        if (source instanceof AnnotatedArrayType a) return new Array(a,carrier,logical,resolver);
        if (source instanceof AnnotatedWildcardType w) return new Wildcard(w,carrier,logical,resolver);
        if (source instanceof AnnotatedTypeVariable v) return new Variable(v,carrier,logical,resolver);
        return new Base(source,carrier,logical,resolver);
    }

    static AnnotatedType[] wrap(AnnotatedType[] source, GenericDeclaration carrier, GenericDeclaration logical) {return wrap(source,carrier,logical,null);}
    static AnnotatedType[] wrap(AnnotatedType[] source, GenericDeclaration carrier, GenericDeclaration logical,Function<TypeVariable<?>,Type> resolver) {
        return Arrays.stream(source).map(t -> wrap(t,carrier,logical,resolver)).toArray(AnnotatedType[]::new);
    }

    static Type type(Type value, GenericDeclaration carrier, GenericDeclaration logical) {return type(value,carrier,logical,null);}
    static Type type(Type value, GenericDeclaration carrier, GenericDeclaration logical,Function<TypeVariable<?>,Type> resolver) {
        Class<?> carrierClass = carrier instanceof Executable e ? e.getDeclaringClass() : (Class<?>)carrier;
        Class<?> logicalClass = logical instanceof Executable e ? e.getDeclaringClass() : (Class<?>)logical;
        if (value == carrierClass) return logicalClass;
        if (value instanceof TypeVariable<?> variable) {
            if(resolver!=null){Type replacement=resolver.apply(variable);if(replacement!=null)return replacement;}
            GenericDeclaration owner=variable.getGenericDeclaration();
            GenericDeclaration replacement=owner.equals(carrier)?logical:owner.equals(carrierClass)?logicalClass:null;
            if(replacement!=null)for(var candidate:replacement.getTypeParameters())
                if(candidate.getName().equals(variable.getName()))return candidate;
            return value;
        }
        if(value instanceof ParameterizedType p) {
            Type raw=type(p.getRawType(),carrier,logical,resolver);
            Type owner=p.getOwnerType()==null?null:type(p.getOwnerType(),carrier,logical,resolver);
            Type[] args=Arrays.stream(p.getActualTypeArguments()).map(t->type(t,carrier,logical,resolver)).toArray(Type[]::new);
            return new ParameterizedType() {
                public Type getRawType(){return raw;} public Type getOwnerType(){return owner;}
                public Type[] getActualTypeArguments(){return args.clone();}
                public boolean equals(Object o){return o instanceof ParameterizedType q && raw.equals(q.getRawType()) && java.util.Objects.equals(owner,q.getOwnerType()) && Arrays.equals(args,q.getActualTypeArguments());}
                public int hashCode(){return Arrays.hashCode(args)^raw.hashCode()^java.util.Objects.hashCode(owner);}
                public String getTypeName(){return raw.getTypeName()+"<"+String.join(", ",Arrays.stream(args).map(Type::getTypeName).toList())+">";}
                public String toString(){return getTypeName();}
            };
        }
        if(value instanceof GenericArrayType a) {
            Type component=type(a.getGenericComponentType(),carrier,logical,resolver);
            if(component instanceof Class<?> c)return java.lang.reflect.Array.newInstance(c,0).getClass();
            return new GenericArrayType() {
                public Type getGenericComponentType(){return component;}
                public boolean equals(Object o){return o instanceof GenericArrayType a && component.equals(a.getGenericComponentType());}
                public int hashCode(){return component.hashCode();} public String getTypeName(){return component.getTypeName()+"[]";}
                public String toString(){return getTypeName();}
            };
        }
        if(value instanceof WildcardType w) {
            Type[] upper=Arrays.stream(w.getUpperBounds()).map(t->type(t,carrier,logical,resolver)).toArray(Type[]::new);
            Type[] lower=Arrays.stream(w.getLowerBounds()).map(t->type(t,carrier,logical,resolver)).toArray(Type[]::new);
            return new WildcardType() {
                public Type[] getUpperBounds(){return upper.clone();} public Type[] getLowerBounds(){return lower.clone();}
                public boolean equals(Object o){return o instanceof WildcardType w && Arrays.equals(upper,w.getUpperBounds()) && Arrays.equals(lower,w.getLowerBounds());}
                public int hashCode(){return Arrays.hashCode(upper)^Arrays.hashCode(lower);}
                public String getTypeName(){return lower.length>0?"? super "+lower[0].getTypeName():upper.length==0 || upper[0]==Object.class?"?":"? extends "+upper[0].getTypeName();}
                public String toString(){return getTypeName();}
            };
        }
        return value;
    }

    private static class Base implements AnnotatedType {
        final AnnotatedType source; final GenericDeclaration carrier,logical;final Function<TypeVariable<?>,Type> resolver;
        Base(AnnotatedType source,GenericDeclaration carrier,GenericDeclaration logical,Function<TypeVariable<?>,Type> resolver){this.source=source;this.carrier=carrier;this.logical=logical;this.resolver=resolver;}
        public Type getType(){return type(source.getType(),carrier,logical,resolver);}
        public AnnotatedType getAnnotatedOwnerType(){return wrap(source.getAnnotatedOwnerType(),carrier,logical,resolver);}
        public <T extends Annotation>T getAnnotation(Class<T> annotation){return source.getAnnotation(annotation);}
        public Annotation[] getAnnotations(){return source.getAnnotations();}
        public Annotation[] getDeclaredAnnotations(){return source.getDeclaredAnnotations();}
    }
    private static final class Parameterized extends Base implements AnnotatedParameterizedType {
        Parameterized(AnnotatedParameterizedType s,GenericDeclaration c,GenericDeclaration l,Function<TypeVariable<?>,Type> r){super(s,c,l,r);}
        public AnnotatedType[] getAnnotatedActualTypeArguments(){return wrap(((AnnotatedParameterizedType)source).getAnnotatedActualTypeArguments(),carrier,logical,resolver);}
    }
    private static final class Array extends Base implements AnnotatedArrayType {
        Array(AnnotatedArrayType s,GenericDeclaration c,GenericDeclaration l,Function<TypeVariable<?>,Type> r){super(s,c,l,r);}
        public AnnotatedType getAnnotatedGenericComponentType(){return wrap(((AnnotatedArrayType)source).getAnnotatedGenericComponentType(),carrier,logical,resolver);}
    }
    private static final class Wildcard extends Base implements AnnotatedWildcardType {
        Wildcard(AnnotatedWildcardType s,GenericDeclaration c,GenericDeclaration l,Function<TypeVariable<?>,Type> r){super(s,c,l,r);}
        public AnnotatedType[] getAnnotatedLowerBounds(){return wrap(((AnnotatedWildcardType)source).getAnnotatedLowerBounds(),carrier,logical,resolver);}
        public AnnotatedType[] getAnnotatedUpperBounds(){return wrap(((AnnotatedWildcardType)source).getAnnotatedUpperBounds(),carrier,logical,resolver);}
    }
    private static final class Variable extends Base implements AnnotatedTypeVariable {
        Variable(AnnotatedTypeVariable s,GenericDeclaration c,GenericDeclaration l,Function<TypeVariable<?>,Type> r){super(s,c,l,r);}
        public AnnotatedType[] getAnnotatedBounds(){return wrap(((AnnotatedTypeVariable)source).getAnnotatedBounds(),carrier,logical,resolver);}
    }
}
