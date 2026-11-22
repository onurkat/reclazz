/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import com.onurkat.reclazz.transform.TransformTestBase;
import java.lang.ref.WeakReference;
import java.lang.annotation.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

/** Real JDK caller roots and same-allocation constructor delegation share the record CAS. */
class RecordCallerMembersTest {
    @TempDir Path tmp;
    @Test void callerRootsAndConstructorsStayOldUntilCommit()throws Exception {
        var harness=new RecordComponentMetadataTest();harness.tmp=tmp;harness.runProbe(Probe.class,"RECORD_CALLER_MEMBERS_OK");
    }
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD,ElementType.FIELD,ElementType.CONSTRUCTOR})
    public @interface Tag {String value();}
    public record Owner(int value) { }
    public record Other(int value) { }
    public static class Caller {
        @Tag("physical") public int value;
        public String retired;
        public static int counter;
        public static Runnable onAux;
        @Tag("physical") public Caller(int value){this.value=value;}
        @Tag("physical") public String label(){return "physical";}
        public String retired(){return "physical";}
        public static String oldLabel(Caller receiver){return "old:"+receiver.value;}
        public static String newLabel(Caller receiver){return "new:"+receiver.value;}
        public static Caller oldInit(Caller receiver,int value)throws Throwable {MemberForge.delegate(Caller.class,Object.class,"()V",new Object[0]);receiver.value=value;MemberForge.constructorField(receiver,"old",Caller.class,"retired");return receiver;}
        public static Caller newInit(Caller receiver,int value)throws Throwable {MemberForge.delegate(Caller.class,Object.class,"()V",new Object[0]);receiver.value=value+100;return receiver;}
        public static Caller auxiliary(Caller receiver,int value,String ignored)throws Throwable {
            Runnable callback=onAux;onAux=null;if(callback!=null)callback.run();
            return (Caller)MemberForge.delegate(Caller.class,Caller.class,"(I)V",new Object[]{value+1});
        }
    }
    public static class OldTemplate {
        @Tag("old") public int value;
        public static int counter;
        public String retired;
        @Tag("old") public OldTemplate(int value) { }
        @Tag("old-aux") public OldTemplate(int value,String ignored) { }
        @Tag("old") public String label(){return null;}
    }
    public static class NewTemplate {
        @Tag("new") public int value;
        public static int counter;
        @Tag("new") public NewTemplate(int value) { }
        @Tag("new-aux") public NewTemplate(int value,String ignored) { }
        @Tag("new") public String label(){return null;}
    }
    public static class Parent {
        public int base;public String retiredBase;
        public Parent() { }
        public static Parent oldInit(Parent receiver)throws Throwable {
            MemberForge.delegate(Parent.class,Object.class,"()V",new Object[0]);
            MemberForge.constructorField(receiver,10,Parent.class,"base");MemberForge.constructorField(receiver,"old",Parent.class,"retiredBase");return receiver;
        }
        public static Parent newInit(Parent receiver)throws Throwable {
            MemberForge.delegate(Parent.class,Object.class,"()V",new Object[0]);MemberForge.constructorField(receiver,100,Parent.class,"base");return receiver;
        }
    }
    public static class Child extends Parent {
        public int child;public static Runnable onAux;
        public Child(int value){child=value;}
        public static Child initialize(Child receiver,int value)throws Throwable {
            receiver=(Child)MemberForge.delegate(Child.class,Parent.class,"()V",new Object[0]);
            MemberForge.constructorField(receiver,value,Child.class,"child");return receiver;
        }
        public static Child auxiliary(Child receiver,int value,String ignored)throws Throwable {
            Runnable callback=onAux;onAux=null;if(callback!=null)callback.run();
            return (Child)MemberForge.delegate(Child.class,Child.class,"(I)V",new Object[]{value+1});
        }
    }
    public static class OldParentTemplate {public int base;public String retiredBase;public OldParentTemplate() { }}
    public static class NewParentTemplate {public int base;public NewParentTemplate() { }}
    public static class ChildTemplate {public int child;public ChildTemplate(int value) { }public ChildTemplate(int value,String ignored) { }}
    public static class Probe extends TransformTestBase {
        static int checks;
        static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
        static String key(){return InjectedNames.siteKey("label",CallSiteAdapter.descHash("()Ljava/lang/String;"));}
        static RecordSchema.Preparation pending(Class<?> record) {
            var slot=RecordSchema.slot(record);return slot.prepare(slot.current().members());
        }
        record Prepared(RecordCaller.View members,Map<String,MethodHandle> targets) { }
        static Class<?> metadata(Class<?> owner,Class<?> template)throws Throwable {
            byte[] raw;
            try(var in=template.getResourceAsStream("/"+template.getName().replace('.','/')+".class")){raw=in.readAllBytes();}
            var source=new ClassNode();new ClassReader(raw).accept(source,ClassReader.SKIP_CODE);
            var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,source.name+"$__reclazz$Metadata",null,"java/lang/Object",null);
            for(var field:source.fields)field.accept(writer);
            for(var method:source.methods) {
                if(method.name.equals("<clinit>"))continue;
                var visitor=writer.visitMethod(method.access,method.name,method.desc,method.signature,method.exceptions.toArray(String[]::new));
                method.accept(new MethodVisitor(Opcodes.ASM9,visitor){@Override public void visitEnd(){super.visitCode();super.visitInsn(Opcodes.ACONST_NULL);super.visitInsn(Opcodes.ATHROW);super.visitMaxs(0,0);super.visitEnd();}});
            }
            writer.visitEnd();var lookup=MethodHandles.privateLookupIn(owner,MethodHandles.lookup());
            var metadata=lookup.defineHiddenClass(writer.toByteArray(),false,MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
            return metadata;
        }
        static Prepared prepare(boolean next)throws Throwable {
            var lookup=MethodHandles.privateLookupIn(Caller.class,MethodHandles.lookup());
            Class<?> metadata=metadata(Caller.class,next?NewTemplate.class:OldTemplate.class);
            var initializer=lookup.findStatic(Caller.class,next?"newInit":"oldInit",MethodType.methodType(Caller.class,Caller.class,int.class));
            var auxiliary=lookup.findStatic(Caller.class,"auxiliary",MethodType.methodType(Caller.class,Caller.class,int.class,String.class));
            var label=lookup.findStatic(Caller.class,next?"newLabel":"oldLabel",MethodType.methodType(String.class,Caller.class));
            var method=MethodForge.callerDispatchMethod(Caller.class,metadata.getDeclaredMethod("label"),label,key(),null);
            var fields=new ArrayList<Field>();for(var field:metadata.getDeclaredFields())fields.add(MemberForge.snapshotField(Caller.class,field));
            var constructor=MemberForge.snapshotConstructor(Caller.class,metadata.getDeclaredConstructor(int.class),Modifier.PUBLIC,null,initializer);
            var aux=MemberForge.snapshotConstructor(Caller.class,metadata.getDeclaredConstructor(int.class,String.class),Modifier.PUBLIC,null,auxiliary);
            return new Prepared(RecordCaller.prepare(Caller.class,List.of(method),fields,List.of(constructor,aux),Map.of("(I)V",initializer,"(ILjava/lang/String;)V",auxiliary)),Map.of(key(),label));
        }
        static void stage(RecordSchema.Preparation pending,Prepared prepared) {
            pending.stageCallerMembers(Caller.class,prepared.members);pending.stageDispatch(Caller.class,prepared.targets);
        }
        static RecordCaller.View hierarchy(boolean parent,boolean next)throws Throwable {
            Class<?> owner=parent?Parent.class:Child.class;Class<?> template=parent?(next?NewParentTemplate.class:OldParentTemplate.class):ChildTemplate.class;
            Class<?> metadata=metadata(owner,template);var lookup=MethodHandles.privateLookupIn(owner,MethodHandles.lookup());
            var targets=new LinkedHashMap<String,MethodHandle>();
            if(parent)targets.put("()V",lookup.findStatic(Parent.class,next?"newInit":"oldInit",MethodType.methodType(Parent.class,Parent.class)));
            else {targets.put("(I)V",lookup.findStatic(Child.class,"initialize",MethodType.methodType(Child.class,Child.class,int.class)));targets.put("(ILjava/lang/String;)V",lookup.findStatic(Child.class,"auxiliary",MethodType.methodType(Child.class,Child.class,int.class,String.class)));}
            var fields=new ArrayList<Field>();for(var field:metadata.getDeclaredFields())fields.add(MemberForge.snapshotField(owner,field));
            var constructors=new ArrayList<Constructor<?>>();for(var ctor:metadata.getDeclaredConstructors())constructors.add(MemberForge.snapshotConstructor(owner,ctor,Modifier.PUBLIC,null,targets.get(MethodType.methodType(void.class,ctor.getParameterTypes()).toMethodDescriptorString())));
            return RecordCaller.prepare(owner,List.of(),fields,constructors,targets);
        }
        static void hierarchyWitness()throws Throwable {
            var oldParent=hierarchy(true,false);var newParent=hierarchy(true,true);var child=hierarchy(false,false);
            var oldInitializer=MethodHandles.lookup().findStatic(Parent.class,"oldInit",MethodType.methodType(Parent.class,Parent.class));
            MemberForge.initializer(Parent.class,"()V",oldInitializer);
            try(var initial=pending(Owner.class)){initial.stageCallerMembers(Parent.class,oldParent);initial.stageCallerMembers(Child.class,child);check(initial.commit(),"hierarchy old generation publication");}
            var constructor=Child.class.getDeclaredConstructor(int.class);var auxiliary=Child.class.getDeclaredConstructor(int.class,String.class);
            Child baseline=(Child)constructor.newInstance(1);check(baseline.child==1&&baseline.base==10&&baseline.retiredBase.equals("old"),"initial caller hierarchy constructor generation");
            try(var update=pending(Owner.class)) {
                update.stageCallerMembers(Parent.class,newParent);update.stageCallerMembers(Child.class,child);
                Child.onAux=()->{
                    check(update.commit(),"hierarchy CAS inside auxiliary body");
                    try(var prune=pending(Owner.class)){prune.stageCallerMembers(Parent.class,newParent);prune.stageCallerMembers(Child.class,child);}
                };
                Child crossing=(Child)auxiliary.newInstance(2,"ignored");
                check(crossing.child==3&&crossing.base==10&&crossing.retiredBase.equals("old"),"captured superclass initializer and fields survive CAS and history pruning");
            }
            Child after=(Child)constructor.newInstance(4);check(after.base==100&&after.retiredBase==null,"next child construction uses new superclass generation");
            try(var parentOnly=pending(Owner.class)){parentOnly.stageCallerMembers(Parent.class,oldParent);check(parentOnly.commit(),"parent-only cohort publication");}
            Child untouched=(Child)constructor.newInstance(5);check(untouched.base==10&&untouched.retiredBase.equals("old"),"untouched child captures the newest committed parent generation");
        }
        static List<WeakReference<?>> disposableCaller()throws Throwable {
            var bytes=compile(new SourceFile("com.example.DisposableCaller","""
                package com.example;
                public class DisposableCaller {
                    public DisposableCaller(int value) { }
                    public int touch(){return 42;}
                    public static DisposableCaller __reclazz$initialize(DisposableCaller receiver,int value){return receiver;}
                }
                """)).get("com/example/DisposableCaller");
            class Loader extends ClassLoader {Loader(){super(Probe.class.getClassLoader());}Class<?> caller(){return defineClass("com.example.DisposableCaller",bytes,0,bytes.length);}}
            var loader=new Loader();Class<?> owner=loader.caller();var lookup=MethodHandles.privateLookupIn(owner,MethodHandles.lookup());
            var initializer=lookup.findStatic(owner,"__reclazz$initialize",MethodType.methodType(owner,owner,int.class));
            var view=RecordCaller.prepare(owner,List.of(owner.getDeclaredMethod("touch")),List.of(),List.of(owner.getDeclaredConstructor(int.class)),Map.of("(I)V",initializer));
            try(var commit=pending(Owner.class)){commit.stageCallerMembers(owner,view);check(commit.commit(),"disposable caller member generation publication");}
            var site=RecordSchema.callerSite(owner,RecordSchema.slot(Owner.class),"caller-members",MethodHandles.constant(RecordCaller.View.class,null));
            return List.of(new WeakReference<>(loader),new WeakReference<>(owner),new WeakReference<>(view),new WeakReference<>(site));
        }
        static String tag(AnnotatedElement member){return member.getAnnotation(Tag.class).value();}
        public static void main(String[] args)throws Throwable {
            LookupCapture.trust(Probe.class);LookupCapture.seal();
            check(tag(Caller.class.getDeclaredMethod("label")).equals("physical"),"ordinary initial roots");
            var old=prepare(false);var next=prepare(true);
            check(tag(Caller.class.getDeclaredMethod("label")).equals("physical"),"private caller preparation stays invisible");
            try(var first=pending(Owner.class)) {stage(first,old);check(tag(Caller.class.getDeclaredMethod("label")).equals("physical"),"staging does not publish metadata");check(first.commit(),"first caller generation");}
            var method=Caller.class.getDeclaredMethod("label");var constructor=Caller.class.getDeclaredConstructor(int.class);
            var field=Caller.class.getDeclaredField("value");var aux=Caller.class.getDeclaredConstructor(int.class,String.class);
            Caller before=(Caller)constructor.newInstance(3);
            check(before.value==3&&field.getInt(before)==3,"logical caller constructor and compatible physical field agree");
            Caller writable=new Caller(8);field.setInt(writable,9);check(writable.value==9&&field.getInt(writable)==9,"snapshot instance field writes preserve physical storage");
            var counter=Caller.class.getDeclaredField("counter");counter.setInt(null,31);check(Caller.counter==31&&counter.getInt(null)==31,"snapshot static field uses physical storage");
            check(method.invoke(before).equals("old:3")&&tag(method).equals("old"),"caller root body and old metadata");
            try(var aborted=pending(Owner.class)) {stage(aborted,next);check(tag(Caller.class.getDeclaredField("value")).equals("old"),"prepared field stays old");}
            check(method.invoke(before).equals("old:3")&&((Caller)constructor.newInstance(4)).value==4,"abort keeps body and initializer old");
            try(var update=pending(Owner.class)) {
                stage(update,next);
                Caller.onAux=()->{try {check(update.commit(),"CAS inside auxiliary body");}catch(Exception failure){throw new AssertionError(failure);}};
                Caller crossing=(Caller)aux.newInstance(4,"ignored");
                check(crossing.value==5&&crossing.retired.equals("old"),"in-flight auxiliary delegation keeps captured old initializer generation");
            }
            check(((Caller)constructor.newInstance(5)).value==105,"held constructor follows the committed caller generation");
            check(method.invoke(before).equals("new:3")&&tag(method).equals("old"),"held method follows body while retaining old root metadata");
            check(tag(Caller.class.getDeclaredMethod("label")).equals("new")&&tag(Caller.class.getDeclaredConstructor(int.class)).equals("new"),"new member and constructor metadata publish together");
            check(Arrays.stream(Caller.class.getDeclaredFields()).map(Field::getName).collect(java.util.stream.Collectors.toSet()).equals(Set.of("value","counter")),"caller complete field roots hide retired fields");
            try {Caller.class.getDeclaredMethod("retired");throw new AssertionError("retired caller method remains visible");}catch(NoSuchMethodException expected){checks++;}
            try(var untouched=pending(Owner.class)){check(untouched.commit(),"untouched record epoch");}
            check(((Caller)constructor.newInstance(6)).value==106,"untouched record epoch keeps caller initializer");
            try(var replace=pending(Other.class)){stage(replace,old);check(method.invoke(before).equals("new:3"),"another record preparation rebases selected caller generation");check(replace.commit(),"another record commit");}
            check(method.invoke(before).equals("old:3")&&((Caller)constructor.newInstance(7)).value==7,"cross-record selected member and initializer generation");
            try(var active=pending(Owner.class);var overlapping=pending(Other.class)) {
                stage(active,next);
                try {overlapping.stageCallerMembers(Caller.class,old.members);throw new AssertionError("overlapping record caller metadata replaced pending first cohort");}
                catch(IllegalStateException expected){checks++;}
                check(active.commit(),"pending other record refusal preserves first caller attempt");
            }
            try(var lost=pending(Owner.class);var winner=pending(Owner.class)) {
                stage(lost,old);stage(winner,next);check(winner.commit()&&!lost.commit(),"competing caller snapshots use attempt identity");
            }
            check(tag(Caller.class.getDeclaredMethod("label")).equals("new")&&((Caller)constructor.newInstance(8)).value==108,"winning caller metadata and initializer remain selected");
            try(var waiting=pending(Owner.class)) {
                stage(waiting,next);ReflectionBridge.replaceClassState(Caller.class,List.of(),List.of(),List.of());
                check(!waiting.commit(),"ordinary replacement cancels pending caller cohort");
            }
            check(tag(Caller.class.getDeclaredMethod("label")).equals("physical"),"ordinary replacement restores ordinary roots");
            hierarchyWitness();
            var disposable=disposableCaller();
            for(int attempt=0;attempt<80&&disposable.stream().anyMatch(ref->ref.get()!=null);attempt++){System.gc();Thread.sleep(20);}
            check(RecordSchema.slot(Owner.class).current()!=null,"parent record Slot remains reachable");
            check(disposable.stream().allMatch(ref->ref.get()==null),"parent record cannot retain disposable caller loader, view or site; retained="+disposable.stream().map(ref->ref.get()!=null).toList());
            System.out.println("RECORD_CALLER_MEMBERS_OK checks="+checks+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));
        }
    }
}
