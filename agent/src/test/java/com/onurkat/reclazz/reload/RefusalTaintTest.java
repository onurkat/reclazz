/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import com.onurkat.reclazz.ui.RestartLedger;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RefusalTaintTest {
    @TempDir Path dir;
    @BeforeEach @AfterEach void clearLedger(){RestartLedger.clear();}
    static class Loader extends ClassLoader {
        final Path root;int loads;
        Loader(Path root){this(root,null);}
        Loader(Path root,ClassLoader parent){super(parent);this.root=root;}
        Class<?> define(String name,byte[] bytes)throws Exception {
            var source=new CodeSource(root.toUri().toURL(),(java.security.cert.Certificate[])null);
            return defineClass(name,bytes,0,bytes.length,new ProtectionDomain(source,null,this,null));
        }
        @Override protected Class<?> findClass(String name)throws ClassNotFoundException{loads++;throw new ClassNotFoundException(name);}
    }
    static byte[] type(String name,boolean record,String[] components,java.util.function.Consumer<MethodVisitor> body) {
        var w=new ClassWriter(0);String parent=record?"java/lang/Record":"java/lang/Object";
        w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL|(record?Opcodes.ACC_RECORD:0),name.replace('.','/'),null,parent,null);
        for(int i=0;i<components.length;i+=2){w.visitRecordComponent(components[i],components[i+1],null).visitEnd();w.visitField(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,components[i],components[i+1],null,null).visitEnd();}
        var c=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);c.visitCode();c.visitVarInsn(Opcodes.ALOAD,0);c.visitMethodInsn(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false);c.visitInsn(Opcodes.RETURN);c.visitMaxs(1,1);c.visitEnd();
        var m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,body==null?"old":"newMember","()V",null,null);m.visitCode();if(body!=null)body.accept(m);m.visitInsn(Opcodes.RETURN);m.visitMaxs(3,0);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
    static byte[] record(String... shape){return type("p.Greeting",true,shape,null);}
    static byte[] reference(String name,String owner,String member,String desc){return type(name,false,new String[0],m->m.visitMethodInsn(Opcodes.INVOKESTATIC,owner.replace('.','/'),member,desc,false));}
    Class<?> define(Loader loader,String name,byte[] bytes)throws Exception{return loader.define(name,bytes);}
    Map<String,Path> paths(String... names){Map<String,Path> p=new HashMap<>();for(String name:names)p.put(name,dir.resolve(name.replace('.','/')+".class"));return p;}
    @Test void laterTwoHopCallerSaveRefusedAndRestoreDoesNotEraseFactoryTaint()throws Exception {
        var loader=new Loader(dir);byte[] original=record("text","Ljava/lang/String;");
        Class<?> record=define(loader,"p.Greeting",original), factory=define(loader,"p.Factory",type("p.Factory",false,new String[0],null)), caller=define(loader,"p.Caller",type("p.Caller",false,new String[0],null));
        Class<?>[] census={record,factory,caller};var guard=new RefusalTaint();
        byte[] changed=record("text","Ljava/lang/String;","marker","Ljava/lang/String;");
        byte[] badFactory=reference("p.Factory","p.Greeting","missing","()V"), badCaller=reference("p.Caller","p.Factory","newMember","()V");
        var save=new LinkedHashMap<String,byte[]>();save.put("p.Caller",badCaller);save.put("p.Factory",badFactory);save.put("p.Greeting",changed);
        assertEquals(Set.of("p.Greeting","p.Factory","p.Caller"),guard.preflight(save,paths("p.Greeting","p.Factory","p.Caller"),census).keySet());
        assertTrue(guard.preflight(Map.of("p.Caller",badCaller),paths("p.Caller"),census).containsKey("p.Caller"));
        assertTrue(guard.preflight(Map.of("p.Greeting",original),paths("p.Greeting"),census).isEmpty());guard.accepted(record,original);
        assertEquals(0,RestartLedger.size());assertTrue(guard.preflight(Map.of("p.Caller",badCaller),paths("p.Caller"),census).containsKey("p.Caller"));
        assertTrue(guard.preflight(Map.of("p.Caller",reference("p.Caller","p.Factory","old","()V")),paths("p.Caller"),census).isEmpty());
    }
    @Test void historicalShapesAndStaleCallerRemainRefused()throws Exception {
        var loader=new Loader(dir);byte[] original=record("text","Ljava/lang/String;");Class<?> owner=define(loader,"p.Greeting",original),caller=define(loader,"p.Caller",type("p.Caller",false,new String[0],null));var guard=new RefusalTaint();Class<?>[] census={owner,caller};
        for(String name:List.of("marker","count"))assertTrue(guard.preflight(Map.of("p.Greeting",record("text","Ljava/lang/String;",name,"I")),paths("p.Greeting"),census).containsKey("p.Greeting"));
        var missing=reference("p.Caller","p.Greeting","marker","()I");assertTrue(guard.preflight(Map.of("p.Caller",missing),paths("p.Caller"),census).containsKey("p.Caller"));
        guard.accepted(owner,original);assertTrue(guard.preflight(Map.of("p.Caller",missing),paths("p.Caller"),census).containsKey("p.Caller"));
    }
    @Test void taintedNeverLoadedOwnerRefusedWithoutDefiningIt()throws Exception {
        var loader=new Loader(dir);Class<?> owner=define(loader,"p.Greeting",record("text","Ljava/lang/String;")),caller=define(loader,"p.Caller",type("p.Caller",false,new String[0],null));var guard=new RefusalTaint();Class<?>[] census={owner,caller};
        var first=guard.preflight(Map.of("p.Greeting",record("text","I"),"p.NewFactory",reference("p.NewFactory","p.Greeting","missing","()V")),paths("p.Greeting","p.NewFactory"),census);assertTrue(first.containsKey("p.NewFactory"));
        int loads=loader.loads;assertTrue(guard.preflight(Map.of("p.Caller",reference("p.Caller","p.NewFactory","newMember","()V")),paths("p.Caller"),census).containsKey("p.Caller"));assertEquals(loads,loader.loads);
    }
    @Test void sameNameOtherLoaderUnaffected()throws Exception {
        Path other=Files.createDirectory(dir.resolve("other"));var a=new Loader(dir);var b=new Loader(other);Class<?> ra=define(a,"p.Greeting",record("text","Ljava/lang/String;")),rb=define(b,"p.Greeting",record("text","Ljava/lang/String;")),cb=define(b,"p.Caller",type("p.Caller",false,new String[0],null));var guard=new RefusalTaint();Class<?>[] census={ra,rb,cb};
        assertTrue(guard.preflight(Map.of("p.Greeting",record("text","I")),paths("p.Greeting"),census).containsKey("p.Greeting"));
        assertTrue(guard.preflight(Map.of("p.Caller",reference("p.Caller","p.Greeting","old","()V")),Map.of("p.Caller",other.resolve("p/Caller.class")),census).isEmpty());
        assertEquals(1,RestartLedger.size());
    }
    @Test void unloadedUntaintedOwnerNotChecked()throws Exception {
        var loader=new Loader(dir);Class<?> caller=define(loader,"p.Caller",type("p.Caller",false,new String[0],null));assertTrue(new RefusalTaint().preflight(Map.of("p.Caller",reference("p.Caller","p.NotLoaded","missing","()V")),paths("p.Caller"),new Class<?>[]{caller}).isEmpty());assertEquals(0,loader.loads);
    }
    @Test void ambiguousOwnerAcrossLoadersRefused()throws Exception {
        Path other=Files.createDirectory(dir.resolve("child"));var parent=new Loader(dir);var child=new Loader(other,parent);
        Class<?> a=define(parent,"p.Greeting",record("text","Ljava/lang/String;")),b=define(child,"p.Greeting",record("text","Ljava/lang/String;")),caller=define(child,"p.Caller",type("p.Caller",false,new String[0],null));
        var guard=new RefusalTaint();Class<?>[] census={a,b,caller};
        assertTrue(guard.preflight(Map.of("p.Greeting",record("text","I")),paths("p.Greeting"),census).containsKey("p.Greeting"));
        var refused=guard.preflight(Map.of("p.Caller",reference("p.Caller","p.Greeting","old","()V")),Map.of("p.Caller",other.resolve("p/Caller.class")),census);
        assertTrue(refused.get("p.Caller").contains("ambiguous owner"));
    }
    @Test void reflectedTaintedClassLiteralRefused()throws Exception {
        var loader=new Loader(dir);Class<?> owner=define(loader,"p.Greeting",record("text","Ljava/lang/String;")),caller=define(loader,"p.Caller",type("p.Caller",false,new String[0],null));
        var guard=new RefusalTaint();Class<?>[] census={owner,caller};guard.preflight(Map.of("p.Greeting",record("text","I")),paths("p.Greeting"),census);
        byte[] reflected=type("p.Caller",false,new String[0],m->{m.visitLdcInsn(Type.getObjectType("p/Greeting"));m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/Class","getRecordComponents","()[Ljava/lang/reflect/RecordComponent;",false);m.visitInsn(Opcodes.POP);});
        assertTrue(guard.preflight(Map.of("p.Caller",reflected),paths("p.Caller"),census).get("p.Caller").contains("reflective use"));
    }
    @Test void preparingAnUnadmittedRestoreDoesNotClearPending()throws Exception {
        var loader=new Loader(dir);byte[] original=record("text","Ljava/lang/String;");Class<?> owner=define(loader,"p.Greeting",original);var guard=new RefusalTaint();
        guard.preflight(Map.of("p.Greeting",record("text","I")),paths("p.Greeting"),new Class<?>[]{owner});assertEquals(1,RestartLedger.size());
        guard.preflight(Map.of("p.Greeting",original),paths("p.Greeting"),new Class<?>[]{owner});assertEquals(1,RestartLedger.size(),"preparation is not native admission");
        guard.accepted(owner,original);assertEquals(0,RestartLedger.size());
    }

    static class SharedLoader extends Loader {
        final ProtectionDomain shared;
        SharedLoader(Path root)throws Exception {super(root);shared=new ProtectionDomain(new CodeSource(root.toUri().toURL(),(java.security.cert.Certificate[])null),null,this,null);}
        @Override Class<?> define(String name,byte[] raw){return defineClass(name,raw,0,raw.length,shared);}
    }
    List<Class<?>> padding(SharedLoader loader)throws Exception {
        var census=new ArrayList<Class<?>>();for(int i=0;i<128;i++){String name="p.Padding"+i;census.add(define(loader,name,type(name,false,new String[0],null)));}return census;
    }
    @Test void unloadedNonRecordWithoutTaintDoesNoRootResolutions()throws Exception {
        var loader=new SharedLoader(dir);var census=padding(loader);var guard=new RefusalTaint();
        assertTrue(guard.preflight(Map.of("p.NewType",type("p.NewType",false,new String[0],null)),paths("p.NewType"),census.toArray(Class<?>[]::new)).isEmpty());assertEquals(0,guard.rootResolutions());
    }
    @Test void unloadedUnrelatedClassDoesNotResolveRootsForAnUntouchedTaint()throws Exception {
        var loader=new SharedLoader(dir);var census=padding(loader);Class<?> record=define(loader,"p.Greeting",record("text","Ljava/lang/String;"));census.add(record);var guard=new RefusalTaint();
        guard.preflight(Map.of("p.Greeting",record("text","I")),paths("p.Greeting"),census.toArray(Class<?>[]::new));
        assertTrue(guard.preflight(Map.of("p.Unrelated",type("p.Unrelated",false,new String[0],null)),paths("p.Unrelated"),census.toArray(Class<?>[]::new)).isEmpty());assertEquals(0,guard.rootResolutions());
    }
    @Test void taintedUnloadedConsumerResolvesEachCodeSourceOnlyOnce()throws Exception {
        var loader=new SharedLoader(dir);var census=padding(loader);Class<?> record=define(loader,"p.Greeting",record("text","Ljava/lang/String;"));census.add(record);
        var other=new SharedLoader(Files.createDirectory(dir.resolve("other")));census.add(define(other,"p.OtherSource",type("p.OtherSource",false,new String[0],null)));
        var guard=new RefusalTaint();guard.preflight(Map.of("p.Greeting",record("text","I")),paths("p.Greeting"),census.toArray(Class<?>[]::new));
        var inputs=new LinkedHashMap<String,byte[]>();for(int i=0;i<3;i++){String name="p.NewFactory"+i;inputs.put(name,reference(name,"p.Greeting","missing","()V"));}
        assertEquals(inputs.keySet(),guard.preflight(inputs,paths(inputs.keySet().toArray(String[]::new)),census.toArray(Class<?>[]::new)).keySet());assertEquals(2,guard.rootResolutions(),"130 distinct loaded classes share exactly two code sources");
    }

}
