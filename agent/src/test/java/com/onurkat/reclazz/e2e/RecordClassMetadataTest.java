/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.reload.*;
import com.onurkat.reclazz.transform.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Class declarations and member type variables must belong to the same published record snapshot. */
class RecordClassMetadataTest {
    @TempDir Path tmp;
    @Test void classAnnotationsRenamedVariablesAndRecursiveBoundsPublishWithMembers()throws Exception {
        var harness=new RecordComponentMetadataTest();harness.tmp=tmp;harness.runProbe(Probe.class,"RECORD_CLASS_METADATA_OK");
    }
    public static class Probe extends TransformTestBase {
        private static int checks;
        private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
        private static String tag(AnnotatedElement element)throws Exception {
            for(Annotation a:element.getAnnotations())if(a.annotationType().getName().equals("com.example.Mark"))return (String)a.annotationType().getMethod("value").invoke(a);
            return null;
        }
        private static Type argument(Type type){return ((ParameterizedType)type).getActualTypeArguments()[0];}
        private static final class Lifetime {
            final Class<?> owner;final List<WeakReference<?>> references;TypeVariable<?> held;Method method;
            Lifetime(Class<?> owner,List<WeakReference<?>> references,TypeVariable<?> held,Method method){this.owner=owner;this.references=references;this.held=held;this.method=method;}
        }
        private static Lifetime lifetimeWitness()throws Throwable {
            String name="com/example/HeaderLifetime";
            var original=compile(new SourceFile("com.example.HeaderLifetime","package com.example; public record HeaderLifetime<T>(T value) {}"));
            byte[] next=compile(new SourceFile("com.example.HeaderLifetime","package com.example; public record HeaderLifetime<U extends Comparable<U>>(U value,int added) {}" )).get(name);
            byte[] last=compile(new SourceFile("com.example.HeaderLifetime","package com.example; public record HeaderLifetime<V extends Comparable<V>>(V value,int added) {}" )).get(name);
            var context=new TransformContext();context.addWatched(name);var transformer=new ReclazzTransformer(context,AgentConfig.parse(null));
            class Loader extends ClassLoader {
                Loader(){super(Probe.class.getClassLoader());}
                Class<?> record()throws Exception {
                    byte[] raw=original.get(name);byte[] changed=transformer.transform(this,name,null,null,raw);
                    if(changed!=null)raw=changed;return defineClass(name.replace('/','.'),raw,0,raw.length);
                }
            }
            Class<?> owner=new Loader().record();var field=owner.getDeclaredField(InjectedNames.LOOKUP_FIELD);field.setAccessible(true);
            var lookup=(MethodHandles.Lookup)field.get(null);LookupCapture.store(owner,lookup);
            var prepared=RecordCompanion.prepare(owner,lookup,next,context,1);
            try(var pending=RecordSchema.slot(owner).prepareVirtual(prepared.snapshot().members())) {
                pending.stageDispatch(owner,prepared.snapshot().targets());check(pending.commit(),"first lifetime schema publication");
            }
            var variable=owner.getTypeParameters()[0];var member=owner.getDeclaredMethod("value");
            var refs=List.<WeakReference<?>>of(new WeakReference<>(prepared.snapshot().members().header()),new WeakReference<>(prepared.snapshot().members().components().get(0).metadata().getDeclaringRecord()),new WeakReference<>(variable),new WeakReference<>(member));
            var replacement=RecordCompanion.prepare(owner,lookup,last,context,2);
            try(var pending=RecordSchema.slot(owner).prepareVirtual(replacement.snapshot().members())) {
                pending.stageDispatch(owner,replacement.snapshot().targets());check(pending.commit(),"second lifetime schema publication");
            }
            // Histories retain the prior committed target until the next touch; prune it with
            // a later aborted preparation, while preserving the current V selection.
            try(var pruning=RecordSchema.slot(owner).prepareVirtual(replacement.snapshot().members())) {
                pruning.stageDispatch(owner,replacement.snapshot().targets());
            }
            return new Lifetime(owner,refs,variable,member);
        }
        private static void retainedMethodAcrossGc(Lifetime witness)throws Exception {
            // Only the held Method pins this retired view; no State/View/TypeVariable local
            // survives from its preparation. This is the retained Jackson/Spring member case.
            for(int attempt=0;attempt<30;attempt++){System.gc();Thread.sleep(10);}
            var variable=(TypeVariable<?>)witness.method.getGenericReturnType();
            check(variable.getName().equals("U")&&variable.getGenericDeclaration()==witness.owner,"held Method pins retired generic scope across GC");
            check(variable.getAnnotations().length==0&&variable.getAnnotation(Deprecated.class)==null,"held Method pins source annotation metadata across GC");
            check(argument(variable.getBounds()[0])==variable,"held Method pins recursive bounds across GC");
            check(argument(variable.getAnnotatedBounds()[0].getType())==variable,"held Method pins annotated bounds and source Context across GC");
        }
        private static void arityWitness()throws Throwable {
            String name="com/example/SelfRecord";
            var original=compile(new SourceFile("com.example.SelfRecord","package com.example; public record SelfRecord<T>(T value) {}"));
            byte[] incoming=compile(new SourceFile("com.example.SelfRecord","""
                package com.example;
                public record SelfRecord<A extends Comparable<SelfRecord<A,B>>,B>(A value,B second,SelfRecord<A,B> sibling) {
                    public SelfRecord<A,B> again(){return this;}
                    public java.util.List<? extends SelfRecord<A,B>[]> nested(){return null;}
                }
                """)).get(name);
            var context=new TransformContext();context.addWatched(name);var transformer=new ReclazzTransformer(context,AgentConfig.parse(null));
            class Loader extends ClassLoader {
                Loader(){super(Probe.class.getClassLoader());}
                Class<?> record()throws Exception {
                    byte[] raw=original.get(name);byte[] changed=transformer.transform(this,name,null,null,raw);
                    if(changed!=null)raw=changed;return defineClass(name.replace('/','.'),raw,0,raw.length);
                }
            }
            Class<?> owner=new Loader().record();var field=owner.getDeclaredField(InjectedNames.LOOKUP_FIELD);field.setAccessible(true);
            var lookup=(MethodHandles.Lookup)field.get(null);LookupCapture.store(owner,lookup);
            var prepared=RecordCompanion.prepare(owner,lookup,incoming,context,1);
            check(owner.getTypeParameters().length==1,"self generic preparation does not publish new arity");
            try(var pending=RecordSchema.slot(owner).prepareVirtual(prepared.snapshot().members())) {
                pending.stageDispatch(owner,prepared.snapshot().targets());check(pending.commit(),"changed generic arity publication");
            }
            var variables=owner.getTypeParameters();check(variables.length==2,"Class exposes changed generic arity");
            var method=owner.getDeclaredMethod("again");var type=(ParameterizedType)method.getGenericReturnType();
            check(type.getRawType()==owner&&Arrays.equals(type.getActualTypeArguments(),variables),"self method uses new arity and own variables");
            check(argument(variables[0].getBounds()[0]).equals(type),"self recursive variable bound uses candidate arity");
            check(argument(variables[0].getAnnotatedBounds()[0].getType()).equals(type),"self annotated variable bound uses candidate arity");
            var nested=owner.getDeclaredMethod("nested");
            var wildcard=(WildcardType)argument(nested.getGenericReturnType());
            check(((GenericArrayType)wildcard.getUpperBounds()[0]).getGenericComponentType().equals(type),"lazy wildcard generic array retains candidate factory");
            var annotatedWildcard=(AnnotatedWildcardType)((AnnotatedParameterizedType)nested.getAnnotatedReturnType()).getAnnotatedActualTypeArguments()[0];
            check(((AnnotatedArrayType)annotatedWildcard.getAnnotatedUpperBounds()[0]).getAnnotatedGenericComponentType().getType().equals(type),"annotated self wildcard array retains candidate variables");
            var sibling=owner.getDeclaredField("sibling");
            check(sibling.getGenericType().equals(type)&&sibling.getAnnotatedType().getType().equals(type),"self field generic and annotated types agree");
            var component=owner.getRecordComponents()[2];
            check(component.getGenericType().equals(type)&&component.getAnnotatedType().getType().equals(type),"self component generic and annotated types agree");
            var ctor=owner.getDeclaredConstructor(Comparable.class,Object.class,owner);
            check(ctor.getGenericParameterTypes()[2].equals(type)&&ctor.getAnnotatedParameterTypes()[2].getType().equals(type),"self constructor generic and annotated types agree");
        }
        public static void main(String[] args)throws Throwable {
            String name="com/example/GenericRecord";
            var mark=new SourceFile("com.example.Mark","""
                package com.example;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE,ElementType.TYPE_PARAMETER,ElementType.TYPE_USE})
                public @interface Mark {String value();}
                """);
            var signal=new SourceFile("com.example.Signal","""
                package com.example;
                public enum Signal {READY;static {((Runnable)System.getProperties().get("reclazz.header.callback")).run();}}
                """);
            var trigger=new SourceFile("com.example.Trigger","""
                package com.example;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
                public @interface Trigger {Signal value();}
                """);
            var original=compile(mark,signal,trigger,new SourceFile("com.example.GenericRecord","""
                package com.example;
                import java.util.*;import java.util.function.*;
                @Mark("old-class") public record GenericRecord<@Mark("old-variable") T>(List<@Mark("old-element") T> value)
                        implements @Mark("old-interface") Supplier<List<T>> {
                    public List<T> get(){return value;}
                    public <@Mark("old-method-variable") W extends @Mark("old-method-bound") Comparable<W>> Map<T,W> oldEcho(W value){return null;}
                    public <@Mark("old-constructor-variable") X extends @Mark("old-constructor-bound") Comparable<X>> GenericRecord(List<T> value,X extra){this(value);}
                }
                """));
            byte[] incoming=compile(mark,signal,trigger,new SourceFile("com.example.GenericRecord","""
                package com.example;
                import java.util.*;import java.util.function.*;
                @Trigger(Signal.READY) @Mark("new-class") public record GenericRecord<@Mark("new-variable") U extends @Mark("new-bound") Comparable<@Mark("recursive-bound") U>>(
                        List<@Mark("new-element") U> value,int extra)
                        implements @Mark("new-interface") Supplier<List<U>> {
                    public GenericRecord(List<U> value){this(value,99);}
                    public List<U> get(){return value;}
                    public <@Mark("method-variable") V extends @Mark("method-bound") Comparable<V>> V echo(V value){return value;}
                }
                """)).get(name);
            var context=new TransformContext();context.addWatched(name);var transformer=new ReclazzTransformer(context,AgentConfig.parse(null));
            class Loader extends ClassLoader {
                Loader(){super(Probe.class.getClassLoader());}
                @Override protected Class<?> findClass(String binary)throws ClassNotFoundException {
                    byte[] raw=original.get(binary.replace('.','/'));if(raw==null)throw new ClassNotFoundException(binary);
                    try {byte[] changed=transformer.transform(this,binary.replace('.','/'),null,null,raw);if(changed!=null)raw=changed;}
                    catch(Exception failure){throw new ClassNotFoundException(binary,failure);}
                    return defineClass(binary,raw,0,raw.length);
                }
            }
            Class<?> owner=new Loader().loadClass(name.replace('/','.'));
            var lookupField=owner.getDeclaredField(InjectedNames.LOOKUP_FIELD);lookupField.setAccessible(true);var lookup=(MethodHandles.Lookup)lookupField.get(null);
            var oldVariable=owner.getTypeParameters()[0];var oldComponent=owner.getRecordComponents()[0];
            var oldGenericMethod=owner.getDeclaredMethod("oldEcho",Comparable.class);
            var oldGenericConstructor=owner.getDeclaredConstructor(List.class,Comparable.class);
            var oldField=owner.getDeclaredField("value");var oldMethod=owner.getDeclaredMethod("value");var oldConstructor=owner.getDeclaredConstructor(List.class);
            check(oldVariable.getName().equals("T")&&tag(owner).equals("old-class"),"initial class metadata");
            LookupCapture.trust(Probe.class);LookupCapture.trust(RecordCompanion.class);LookupCapture.trust(RecordMemberSnapshot.class);LookupCapture.trust(ProtectedCallResolver.class);
            LookupCapture.store(owner,lookup);LookupCapture.seal();
            boolean[] callback={false};
            System.getProperties().put("reclazz.header.callback",(Runnable)()->{
                try {
                    check(owner.getTypeParameters()[0]==oldVariable&&tag(owner).equals("old-class"),"real annotation enum initializer sees old Class during preparation");
                    check(owner.getRecordComponents().length==1&&owner.getDeclaredConstructor(List.class)!=null,"real annotation callback sees old members too");
                    callback[0]=true;
                }catch(Exception failure){throw new AssertionError(failure);}
            });
            RecordCompanion.Prepared prepared;
            try {prepared=RecordCompanion.prepare(owner,lookup,incoming,context,1);}
            finally {System.getProperties().remove("reclazz.header.callback");}
            check(callback[0],"actual annotation enum initialization executed");
            check(owner.getTypeParameters()[0]==oldVariable&&tag(owner).equals("old-class"),"private class preparation stays invisible");
            try(var pending=RecordSchema.slot(owner).prepareVirtual(prepared.snapshot().members())) {
                pending.stageDispatch(owner,prepared.snapshot().targets());
                check(owner.getTypeParameters()[0]==oldVariable&&tag(owner).equals("old-class"),"staging leaves class metadata old");
                check(pending.commit(),"class and members publish");
            }
            check(tag(owner).equals("new-class"),"class annotation changes at the record CAS");
            var variable=owner.getTypeParameters()[0];
            check(variable.getName().equals("U")&&variable.getGenericDeclaration()==owner,"renamed variable belongs to actual record Class");
            check(tag(variable).equals("new-variable"),"variable declaration annotation comes from its source");
            check(argument(variable.getBounds()[0])==variable,"recursive bound resolves the candidate's exact own variable");
            check(tag(variable.getAnnotatedBounds()[0]).equals("new-bound"),"annotated variable bound");
            var recursive=(AnnotatedParameterizedType)variable.getAnnotatedBounds()[0];
            check(tag(recursive.getAnnotatedActualTypeArguments()[0]).equals("recursive-bound"),"recursive bound type annotation");
            check(argument(argument(owner.getGenericInterfaces()[0])).equals(variable),"generic interface uses logical variable");
            check(tag(owner.getAnnotatedInterfaces()[0]).equals("new-interface"),"class interface type annotation");
            var component=owner.getRecordComponents()[0];
            check(argument(component.getGenericType()).equals(variable),"component generic variable belongs to logical Class");
            var element=((AnnotatedParameterizedType)component.getAnnotatedType()).getAnnotatedActualTypeArguments()[0];
            check(tag(element).equals("new-element")&&element.getType().equals(variable),"component type annotation and generic variable agree");
            check(argument(owner.getDeclaredField("value").getGenericType()).equals(variable),"logical field generic variable");
            check(argument(owner.getDeclaredMethod("value").getGenericReturnType()).equals(variable),"logical method generic variable");
            check(argument(owner.getDeclaredConstructor(List.class,int.class).getGenericParameterTypes()[0]).equals(variable),"logical constructor generic variable");
            check(argument(oldComponent.getGenericType()).equals(oldVariable),"held physical component keeps original generic owner variable");
            check(argument(oldField.getGenericType()).equals(oldVariable),"held unparsed physical field keeps its original scope");
            check(argument(oldMethod.getGenericReturnType()).equals(oldVariable),"held unparsed physical method keeps its original scope");
            check(argument(oldConstructor.getGenericParameterTypes()[0]).equals(oldVariable),"held unparsed physical constructor keeps its original scope");
            check(tag(oldVariable).equals("old-variable"),"held physical type variable keeps original annotations");
            var physicalMethodVariable=oldGenericMethod.getTypeParameters()[0];
            check(physicalMethodVariable.getName().equals("W")&&tag(physicalMethodVariable).equals("old-method-variable"),"held unparsed physical method variable retains declaration metadata");
            check(argument(physicalMethodVariable.getBounds()[0])==physicalMethodVariable&&tag(physicalMethodVariable.getAnnotatedBounds()[0]).equals("old-method-bound"),"held unparsed physical method variable retains its recursive scope");
            var oldReturn=((ParameterizedType)oldGenericMethod.getGenericReturnType()).getActualTypeArguments();
            check(oldReturn[0]==oldVariable&&oldReturn[1]==physicalMethodVariable,"held physical method combines original class and own variables");
            var physicalConstructorVariable=oldGenericConstructor.getTypeParameters()[0];
            check(tag(physicalConstructorVariable).equals("old-constructor-variable")&&argument(physicalConstructorVariable.getBounds()[0])==physicalConstructorVariable,"held unparsed physical constructor variable retains original metadata and recursive scope");
            check(tag(physicalConstructorVariable.getAnnotatedBounds()[0]).equals("old-constructor-bound")&&argument(oldGenericConstructor.getGenericParameterTypes()[0])==oldVariable,"held physical constructor combines original class and annotated own scope");
            var echo=owner.getDeclaredMethod("echo",Comparable.class);var methodVariable=echo.getTypeParameters()[0];
            check(methodVariable.getGenericDeclaration().equals(echo)&&tag(methodVariable).equals("method-variable"),"method variable and declaration annotation");
            check(argument(methodVariable.getBounds()[0]).equals(methodVariable)&&tag(methodVariable.getAnnotatedBounds()[0]).equals("method-bound"),"method recursive bound uses its own scope");
            arityWitness();
            Lifetime witness=lifetimeWitness();
            check(witness.held.getName().equals("U")&&witness.held.getGenericDeclaration()==witness.owner,"held retired variable retains its own record declaration");
            check(argument(witness.held.getBounds()[0])==witness.held,"held retired recursive variable remains usable after another rename");
            check(argument(witness.held.getAnnotatedBounds()[0].getType())==witness.held,"held retired variable keeps source annotated bounds after another rename");
            witness.held=null;
            retainedMethodAcrossGc(witness);
            witness.method=null;
            for(int attempt=0;attempt<80&&witness.references.stream().anyMatch(ref->ref.get()!=null);attempt++) {
                System.gc();Thread.sleep(20);
            }
            check(witness.owner.getTypeParameters()[0].getName().equals("V"),"live record keeps only the current class header");
            check(witness.references.stream().allMatch(ref->ref.get()==null),"retired header, carrier, variable and roots collect after history pruning while record remains live; retained="+witness.references.stream().map(ref->ref.get()!=null).toList());
            System.out.println("RECORD_CLASS_METADATA_OK checks="+checks+" runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny"));
        }
    }
}
