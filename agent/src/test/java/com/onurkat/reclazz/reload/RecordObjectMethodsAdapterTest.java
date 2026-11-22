/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.KeepBaseline;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class RecordObjectMethodsAdapterTest {
    record Default(int value) {
        static final String LABEL=label();
        static String label(){return "source initializer";}
        public Default {if(value<0)throw new IllegalArgumentException("negative");}
    }
    record Custom(int value) {
        @Override public int hashCode(){return 123;}
        @Override public boolean equals(Object other){return this==other;}
        @Override public String toString(){return "redacted";}
    }
    record Mixed(int value) { @Override public String toString(){return "value="+value;} }
    private static byte[] bytes(Class<?> type)throws Exception {
        try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){return in.readAllBytes();}
    }
    private static ClassNode node(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static Set<String> bridged(ClassNode node) {
        Set<String> result=new HashSet<>();for(var method:node.methods)for(var instruction:method.instructions)
            if(instruction instanceof InvokeDynamicInsnNode call&&call.bsm.getOwner().equals("com/onurkat/reclazz/bootstrap/RecordObjects"))result.add(method.name);
        return result;
    }
    @Test void generatedBodiesUseTheLogicalBridgeAndPreserveConstructorInitializerProof()throws Exception {
        byte[] original=bytes(Default.class),rewritten=RecordObjectMethodsAdapter.rewrite(original);
        assertEquals(Set.of("hashCode","equals","toString"),bridged(node(rewritten)));
        var before=KeepBaseline.fingerprint(original);var after=KeepBaseline.fingerprint(rewritten);
        assertFalse(before.constructors().isEmpty());assertFalse(before.initializers().isEmpty());
        assertEquals(before.constructors(),after.constructors(),"ordinary physical constructor evidence is unchanged");
        assertEquals(before.initializers(),after.initializers(),"static initializer proof is unchanged");
        assertEquals(before.fields(),after.fields());assertEquals(before.annotations(),after.annotations());
        for(var method:node(rewritten).methods)if(Set.of("hashCode","equals","toString").contains(method.name))
            assertTrue(Arrays.stream(method.instructions.toArray()).anyMatch(i->i instanceof InvokeDynamicInsnNode call&&call.bsm.getOwner().equals("com/onurkat/reclazz/bootstrap/RecordObjects")&&call.bsm.getName().equals("bootstrap")),"VM supplies the actual companion nest lookup at linkage");
    }
    @Test void customBodiesStayUnchangedIncludingStringConcatIndy()throws Exception {
        byte[] custom=bytes(Custom.class);assertSame(custom,RecordObjectMethodsAdapter.rewrite(custom));
        byte[] mixed=bytes(Mixed.class),rewritten=RecordObjectMethodsAdapter.rewrite(mixed);
        assertEquals(Set.of("hashCode","equals"),bridged(node(rewritten)));
        var before=KeepBaseline.fingerprint(mixed);var after=KeepBaseline.fingerprint(rewritten);
        String key="toString:()Ljava/lang/String;";
        assertNotNull(before.methods().get(key));assertNotNull(after.methods().get(key));
        assertEquals(before.methods().get(key),after.methods().get(key));
        assertEquals(before.constructors(),after.constructors());
    }
    @Test void unknownObjectMethodsTemplateIsRefusedRatherThanDeletingExtraLogic()throws Exception {
        var source=node(bytes(Default.class));var hash=source.methods.stream().filter(m->m.name.equals("hashCode")).findFirst().orElseThrow();
        hash.instructions.insert(new InsnNode(Opcodes.NOP));var writer=new ClassWriter(0);source.accept(writer);
        assertThrows(IllegalArgumentException.class,()->RecordObjectMethodsAdapter.rewrite(writer.toByteArray()));
    }
    @Test void nonRecordInputKeepsItsBytes()throws Exception {
        byte[] source=bytes(RecordObjectMethodsAdapterTest.class);assertSame(source,RecordObjectMethodsAdapter.rewrite(source));
    }
}
