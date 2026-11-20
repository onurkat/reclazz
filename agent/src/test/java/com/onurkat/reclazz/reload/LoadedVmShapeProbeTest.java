/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.TransformTestBase;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import java.io.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Passive VM capture and failure-cleanup witnesses for raw reload admission. */
class LoadedVmShapeProbeTest extends TransformTestBase {
    @Test void retransformationCapturesVmShapeWithoutInitializingOrReadingChangedResources()throws Exception {
        String name="RawVmShapeProbe",key="reclazz.test.vmshape.init";
        String prefix="public class "+name+" {static {System.setProperty(\""+key+"\",\"ran\");} public int value(){return ";
        byte[] original=compile(new SourceFile(name,prefix+"1;}}")).get(name);
        byte[] disk=compile(new SourceFile(name,prefix+"2;}}")).get(name);
        Class<?> owner=new ClassLoader(getClass().getClassLoader()){
            Class<?> define(){return defineClass(name,original,0,original.length);}
            @Override public InputStream getResourceAsStream(String path){return path.equals(name+".class")?new ByteArrayInputStream(disk):super.getResourceAsStream(path);}
        }.define();
        var inst=ByteBuddyAgent.install();var captured=new AtomicReference<byte[]>();
        var observer=new ClassFileTransformer(){
            @Override public byte[] transform(ClassLoader loader,String type,Class<?> redefining,ProtectionDomain domain,byte[] bytes){
                if(redefining==owner)captured.set(bytes.clone());
                return null;
            }
        };
        System.clearProperty(key);
        try {
            assertNull(System.getProperty(key));assertTrue(inst.isModifiableClass(owner));
            inst.addTransformer(observer,true);
            try {inst.retransformClasses(owner);}finally{assertTrue(inst.removeTransformer(observer));}
            assertNull(System.getProperty(key),"capturing VM bytes must not initialize application code");
            assertNotNull(captured.get());assertEquals(List.of(Opcodes.ICONST_1,Opcodes.IRETURN),valueOpcodes(captured.get()));
            try(var resource=owner.getResourceAsStream(name+".class")){
                assertNotNull(resource);assertEquals(List.of(Opcodes.ICONST_2,Opcodes.IRETURN),valueOpcodes(resource.readAllBytes()),"resource is deliberately ahead of the VM");
            }
            Object held=owner.getConstructor().newInstance();assertEquals("ran",System.getProperty(key));assertEquals(1,owner.getMethod("value").invoke(held));
            System.out.println("VM_SHAPE_PROBE java="+System.getProperty("java.version")+" retransform=1 vmValue=1 resourceValue=2 initializedDuringCapture=false");
        } finally {inst.removeTransformer(observer);System.clearProperty(key);}
    }

    @Test void productionCaptureWithOwnTransformerIsPassiveAndDoesNotUseChangedResource()throws Exception {
        String name="UninitializedVmShape",key="reclazz.test.production.vmshape";
        String prefix="public class "+name+" {static {System.setProperty(\""+key+"\",\"ran\");}public int value(){return ";
        byte[] original=compile(new SourceFile(name,prefix+"1;}}")).get(name);
        byte[] disk=compile(new SourceFile(name,prefix+"2;}public int onlyOnDisk(){return 3;}}")).get(name);
        Class<?> owner=new ClassLoader(getClass().getClassLoader()){
            Class<?> define(){return defineClass(name,original,0,original.length);}
            @Override public InputStream getResourceAsStream(String path){return path.equals(name+".class")?new ByteArrayInputStream(disk):super.getResourceAsStream(path);}
        }.define();
        var context=new com.onurkat.reclazz.transform.TransformContext();context.addWatched(name);context.markSeen(name);
        var transformer=new com.onurkat.reclazz.transform.ReclazzTransformer(context,com.onurkat.reclazz.config.AgentConfig.parse(null));
        // A name-only transform record from another loader must not change this raw definition.
        assertNotNull(transformer.transform(getClass().getClassLoader(),name,null,null,original));
        var metadata=context.getMetadata(name);var inst=ByteBuddyAgent.install();inst.addTransformer(transformer,true);System.clearProperty(key);
        try {
            var shape=com.onurkat.reclazz.transform.VmClassShape.read(owner,inst);
            assertNull(System.getProperty(key));assertEquals(com.onurkat.reclazz.transform.RawClassSchema.read(original),shape);
            assertNotEquals(com.onurkat.reclazz.transform.RawClassSchema.read(disk),shape);
            assertSame(metadata,context.getMetadata(name));assertFalse(context.isTransformed(owner));
            assertFalse(com.onurkat.reclazz.transform.AdmittedBytes.matches(owner,original));
            assertNull(com.onurkat.reclazz.transform.AdmittedBytes.trackRawSchema(owner));
            assertFalse(com.onurkat.reclazz.transform.VmClassShape.capturing(owner));
            Object held=owner.getConstructor().newInstance();assertEquals("ran",System.getProperty(key));assertEquals(1,owner.getMethod("value").invoke(held));
            assertThrows(NoSuchMethodException.class,()->owner.getMethod("onlyOnDisk"));
        } finally {inst.removeTransformer(transformer);com.onurkat.reclazz.transform.AdmittedBytes.invalidate(owner);System.clearProperty(key);}
    }
    private static List<Integer> valueOpcodes(byte[] bytes){
        var node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_DEBUG);
        var method=node.methods.stream().filter(m->m.name.equals("value")).findFirst().orElseThrow();
        var opcodes=new ArrayList<Integer>();for(var insn:method.instructions)if(insn.getOpcode()>=0)opcodes.add(insn.getOpcode());
        return opcodes;
    }
    @Test void throwingObserverIsRemovedAndExactClassBypassIsReleased()throws Exception {
        String name="ThrowingShapeObserver";byte[] original=compile(new SourceFile(name,"public class "+name+" { public int value(){return 1;} }")).get(name);
        Class<?> owner=new ClassLoader(getClass().getClassLoader()){Class<?> define(){return defineClass(name,original,0,original.length);}}.define();
        var real=ByteBuddyAgent.install();var installed=new java.util.IdentityHashMap<ClassFileTransformer,ClassFileTransformer>();
        var bypass=new java.util.concurrent.atomic.AtomicBoolean();var callbacks=new java.util.concurrent.atomic.AtomicInteger();var captures=new java.util.concurrent.atomic.AtomicInteger();var removes=new java.util.concurrent.atomic.AtomicInteger();
        Instrumentation counted=(Instrumentation)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Instrumentation.class},(proxy,method,args)->{
            if(method.getName().equals("addTransformer")) {
                ClassFileTransformer observer=(ClassFileTransformer)args[0];
                ClassFileTransformer throwing=new ClassFileTransformer(){@Override public byte[] transform(ClassLoader loader,String type,Class<?> target,ProtectionDomain domain,byte[] bytes)throws IllegalClassFormatException {
                    if(target==owner){callbacks.incrementAndGet();bypass.set(com.onurkat.reclazz.transform.VmClassShape.capturing(owner));throw new IllegalClassFormatException("forced observer failure");}return null;
                }};
                installed.put(observer,throwing);real.addTransformer(throwing,true);return null;
            }
            if(method.getName().equals("removeTransformer")){removes.incrementAndGet();return real.removeTransformer(installed.remove((ClassFileTransformer)args[0]));}
            if(method.getName().equals("retransformClasses"))captures.incrementAndGet();
            try{return method.invoke(real,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        try {
            var failure=assertThrows(IllegalStateException.class,()->com.onurkat.reclazz.transform.VmClassShape.read(owner,counted));
            assertEquals("VM did not expose the definition",failure.getMessage());assertEquals(1,callbacks.get());assertTrue(bypass.get());assertEquals(1,captures.get());assertEquals(1,removes.get());assertTrue(installed.isEmpty());
            assertFalse(com.onurkat.reclazz.transform.VmClassShape.capturing(owner));assertNull(com.onurkat.reclazz.transform.AdmittedBytes.trackRawSchema(owner));
            assertEquals(1,owner.getMethod("value").invoke(owner.getConstructor().newInstance()));
        }finally{installed.values().forEach(real::removeTransformer);com.onurkat.reclazz.transform.AdmittedBytes.invalidate(owner);}
    }

}
