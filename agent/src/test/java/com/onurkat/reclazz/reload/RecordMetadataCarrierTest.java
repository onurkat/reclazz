/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import java.lang.annotation.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class RecordMetadataCarrierTest {
    private static final AtomicInteger initialized=new AtomicInteger();
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.RECORD_COMPONENT,ElementType.CONSTRUCTOR,ElementType.PARAMETER,ElementType.TYPE})
    public @interface Tag {String value();}
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE)
    public @interface Use {String value();}
    @Tag("record") public record Source<T>(@Tag("component") List<@Use("element") T> value,int number) {
        static {initialized.incrementAndGet();}
        @Tag("auxiliary") public Source(@Tag("parameter") List<T> value){this(value,7);}
        public String custom(){return "body must not be copied";}
    }
    private static byte[] bytes(Class<?> type)throws Exception {
        try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){return in.readAllBytes();}
    }
    @Test void fullRecordMetadataDefinesAsAnInertHiddenNestmate()throws Exception {
        byte[] generated=RecordMetadataCarrier.generate(bytes(Source.class));
        Class<?> carrier=MethodHandles.lookup().defineHiddenClass(generated,true,MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        assertTrue(carrier.isHidden());assertTrue(carrier.isRecord());assertEquals(0,initialized.get(),"source and carrier static initialization never run");
        assertEquals("record",carrier.getDeclaredAnnotation(Tag.class).value());
        var components=carrier.getRecordComponents();assertEquals(List.of("value","number"),Arrays.stream(components).map(RecordComponent::getName).toList());
        assertEquals("component",components[0].getDeclaredAnnotation(Tag.class).value());
        var generic=(ParameterizedType)components[0].getGenericType();assertEquals(carrier.getTypeParameters()[0],generic.getActualTypeArguments()[0]);
        var annotated=(AnnotatedParameterizedType)components[0].getAnnotatedType();
        assertEquals("element",annotated.getAnnotatedActualTypeArguments()[0].getAnnotation(Use.class).value());
        var auxiliary=carrier.getDeclaredConstructor(List.class);assertEquals("auxiliary",auxiliary.getDeclaredAnnotation(Tag.class).value());
        assertEquals("parameter",auxiliary.getParameters()[0].getDeclaredAnnotation(Tag.class).value());
        assertEquals(2,carrier.getDeclaredConstructors().length,"canonical and explicit auxiliary metadata both retained");
        var canonical=carrier.getDeclaredConstructor(List.class,int.class);
        var originalCanonical=Source.class.getDeclaredConstructor(List.class,int.class);
        assertArrayEquals(Arrays.stream(originalCanonical.getParameters()).map(Parameter::getName).toArray(),
                Arrays.stream(canonical.getParameters()).map(Parameter::getName).toArray());
        assertTrue(canonical.getParameters()[0].isNamePresent(),"record canonical parameter names survive metadata extraction");
        assertEquals(List.class,carrier.getDeclaredField("value").getType());assertNotNull(carrier.getDeclaredMethod("custom"));
    }
    @Test void noSourceBodiesStaticInitializerOrNominalRecordIndyRemain()throws Exception {
        var node=new ClassNode();new ClassReader(RecordMetadataCarrier.generate(bytes(Source.class))).accept(node,0);
        assertTrue(node.methods.stream().noneMatch(m->m.name.equals("<clinit>")));
        for(var method:node.methods) {
            if((method.access&(Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))!=0)continue;
            assertEquals(List.of(Opcodes.ACONST_NULL,Opcodes.ATHROW),Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).map(i->i.getOpcode()).toList(),method.name);
        }
    }
    @Test void nonRecordInputIsRejected()throws Exception {
        assertThrows(IllegalArgumentException.class,()->RecordMetadataCarrier.generate(bytes(RecordMetadataCarrierTest.class)));
    }
    public sealed interface GenericChoice<T> permits GenericSealed { }
    public record GenericSealed<T extends Number>(T value) implements @Use("generic-interface") GenericChoice<T> { }
    public sealed interface PlainChoice permits PlainSealed { }
    public record PlainSealed(int value) implements @Use("plain-interface") PlainChoice { }
    @Test void sealedInterfaceRecordMetadataDoesNotNeedAnotherPermittedSubtype()throws Exception {
        Class<?> carrier=MethodHandles.lookup().defineHiddenClass(RecordMetadataCarrier.generate(bytes(GenericSealed.class)),false,MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        assertTrue(carrier.isRecord());assertEquals(Number.class,carrier.getRecordComponents()[0].getType());
        assertEquals(Number.class,carrier.getTypeParameters()[0].getBounds()[0]);
        var view=(ParameterizedType)carrier.getGenericInterfaces()[0];assertEquals(GenericChoice.class,view.getRawType());
        assertEquals(carrier.getTypeParameters()[0],view.getActualTypeArguments()[0]);
        assertEquals("generic-interface",carrier.getAnnotatedInterfaces()[0].getAnnotation(Use.class).value());
    }
    @Test void plainSealedInterfaceTypeAnnotationIsStillAvailableWithoutASourceGenericSignature()throws Exception {
        var source=new ClassNode();new ClassReader(bytes(PlainSealed.class)).accept(source,ClassReader.SKIP_CODE);assertNull(source.signature);
        Class<?> carrier=MethodHandles.lookup().defineHiddenClass(RecordMetadataCarrier.generate(bytes(PlainSealed.class)),false,MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        assertEquals(PlainChoice.class,carrier.getGenericInterfaces()[0]);
        assertEquals("plain-interface",carrier.getAnnotatedInterfaces()[0].getAnnotation(Use.class).value());
    }

    public sealed static class CallerParent<T> permits SealedCaller { }
    public static final class SealedCaller<T> extends @Use("caller-parent") CallerParent<T> {
        static {initialized.incrementAndGet();}
        public java.util.List<T> items;
    }
    @Test void callerCarrierKeepsSealedParentMetadataWithoutBeingAnotherSubtype()throws Exception {
        Class<?> carrier=MethodHandles.lookup().defineHiddenClass(RecordMetadataCarrier.generateCaller(bytes(SealedCaller.class)),true,MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        assertEquals(Object.class,carrier.getSuperclass());
        var parent=(ParameterizedType)carrier.getGenericSuperclass();
        assertEquals(CallerParent.class,parent.getRawType());
        assertEquals(carrier.getTypeParameters()[0],parent.getActualTypeArguments()[0]);
        assertEquals("caller-parent",carrier.getAnnotatedSuperclass().getAnnotation(Use.class).value());
        assertEquals(carrier.getTypeParameters()[0],((ParameterizedType)carrier.getDeclaredField("items").getGenericType()).getActualTypeArguments()[0]);
        assertEquals(0,initialized.get(),"source and caller metadata static initialization never run");
    }

}
