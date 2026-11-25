/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;
import com.onurkat.reclazz.transform.*;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.util.ClassLookup;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class DefiningLoaderAdmissionTest extends TransformTestBase {
    static final java.util.concurrent.atomic.AtomicInteger IDS=new java.util.concurrent.atomic.AtomicInteger();
    @Test void lookupNeverDefinesAnUnusedCopyAndAnAmbiguousSaveChangesNeitherDefinition() throws Exception {
        String name = "ControllerLoaderProbe" + IDS.incrementAndGet();
        String source = "public class %s { public String body() { return \"%s\"; } }";
        byte[] first = compile(new SourceFile(name, source.formatted(name, "before"))).get(name);
        byte[] second = compile(new SourceFile(name, source.formatted(name, "after"))).get(name);
        class FixtureLoader extends ClassLoader {
            int definitions;
            FixtureLoader() { super(DefiningLoaderAdmissionTest.class.getClassLoader()); }
            @Override protected Class<?> findClass(String requested) throws ClassNotFoundException {
                if (!requested.equals(name)) throw new ClassNotFoundException(requested);
                definitions++;
                return defineClass(name, first, 0, first.length);
            }
        }
        var worker = new FixtureLoader();
        var previous = Thread.currentThread().getContextClassLoader();
        var instrumentation = ByteBuddyAgent.install();
        Thread.currentThread().setContextClassLoader(worker);
        try {
            assertNull(ClassLookup.findLoadedClass(name, instrumentation));
            var live = new FixtureLoader().loadClass(name);
            Object bean = live.getConstructor().newInstance();
            var engine = new com.onurkat.reclazz.agent.ClassReloader(instrumentation);
            assertSame(live, ClassLookup.findLoadedClass(name, instrumentation));
            var observed = new TransformContext();
            observed.markTransformed(live.getClassLoader(), name);
            observed.markRaw(worker, name);
            assertSame(live, ClassLookup.findLoadedClass(name, instrumentation, observed::recordedLoaders));
            assertEquals(0, worker.definitions);
            assertTrue(engine.reload(name, second).isSuccess());
            assertEquals("after", live.getMethod("body").invoke(bean));
            assertEquals(0, worker.definitions);
            var other = worker.loadClass(name);
            assertThrows(ClassLookup.AmbiguousDefinition.class,
                    () -> ClassLookup.findLoadedClass(name, instrumentation, observed::recordedLoaders));
            var refused = engine.reload(name, first);
            assertFalse(refused.isSuccess());
            assertTrue(refused.getError().contains("multiple defining class loaders"));
            assertEquals("after", live.getMethod("body").invoke(bean));
            assertEquals("before", other.getMethod("body").invoke(other.getConstructor().newInstance()));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test void liveInstrumentedOwnerStillReloadsWithStaleRawDuplicate()throws Exception {
        String name="DuplicateLive"+IDS.incrementAndGet();byte[] first=compile(new SourceFile(name,"public class "+name+" {public String value(){return \"one\";}}")).get(name);
        byte[] next=compile(new SourceFile(name,"public class "+name+" {public String value(){return \"two\";}}")).get(name);
        var context=new TransformContext();context.addWatched(name);var transformer=new ReclazzTransformer(context,AgentConfig.parse(null));
        Class<?> owner=new ClassLoader(getClass().getClassLoader()){Class<?> define()throws Exception {byte[] enhanced=transformer.transform(this,name,null,null,first);assertNotNull(enhanced);return defineClass(name,enhanced,0,enhanced.length);}}.define();
        Object held=owner.getConstructor().newInstance();assertEquals("one",owner.getMethod("value").invoke(held));
        Class<?> stale=new ClassLoader(getClass().getClassLoader()){Class<?> define(){return defineClass(name,first,0,first.length);}}.define();
        var previous=Thread.currentThread().getContextClassLoader();Thread.currentThread().setContextClassLoader(owner.getClassLoader());
        SwitchMapRefresh.begin(Map.of(name,next),Map.of(),ClassLookup.loadedClasses(new Class<?>[]{stale,owner}));
        try{var reloader=new StructuralReloader(ByteBuddyAgent.install(),context,AgentConfig.parse(null),null);reloader.setTransformer(transformer);
            assertFalse(reloader.reload(name,next).isSuccess(), "an instrumented duplicate alone cannot establish which instance is live");
            var result=reloader.reload(name,next,held.getClass());assertTrue(result.isSuccess(),result.getError());assertEquals("two",owner.getMethod("value").invoke(held));
            assertEquals("one",stale.getMethod("value").invoke(stale.getConstructor().newInstance()));
        }finally{SwitchMapRefresh.end();Thread.currentThread().setContextClassLoader(previous);com.onurkat.reclazz.ui.RestartLedger.clear();}
    }
    @Test void identityProofDoesNotInitializeAnUnregisteredTransformedClass()throws Exception {
        String name="UninitializedOwner"+IDS.incrementAndGet(),key="reclazz.test.owner.init."+name;System.clearProperty(key);
        byte[] first=compile(new SourceFile(name,"public class "+name+" {static{System.setProperty(\""+key+"\",\"ran\");}public int value(){return 1;}}")).get(name);
        var context=new TransformContext();context.addWatched(name);var transformer=new ReclazzTransformer(context,AgentConfig.parse(null));byte[] enhanced=transformer.doTransform(name,first,getClass().getClassLoader());
        Class<?> owner=new ClassLoader(getClass().getClassLoader()){Class<?> define(){return defineClass(name,enhanced,0,enhanced.length);}}.define();
        var reloader=new StructuralReloader(ByteBuddyAgent.install(),context,AgentConfig.parse(null),null);var proof=StructuralReloader.class.getDeclaredMethod("instrumentedIdentity",Class.class);proof.setAccessible(true);
        try{assertNull(System.getProperty(key));proof.invoke(reloader,owner);assertNull(System.getProperty(key),"proof must not execute application clinit");}
        finally{System.clearProperty(key);}
    }
    @Test void mixedFixtureLoaderAdmitsTransformedBytesAndStillRefusesRawStructuralEdits()throws Exception {
        String good="FixtureTransformed"+IDS.incrementAndGet(),raw="FixtureRaw"+IDS.incrementAndGet();
        String body="public class %s {public String value(){return \"one\";}}";
        var bytes=compile(new SourceFile(good,body.formatted(good)),new SourceFile(raw,body.formatted(raw)));
        var context=new TransformContext();context.addWatched(good);context.addWatched(raw);
        var config=AgentConfig.parse(null);var transformer=new ReclazzTransformer(context,config);
        byte[] enhanced=transformer.transform(getClass().getClassLoader(),good,null,null,bytes.get(good));
        assertNotNull(enhanced);bytes.put(good,enhanced);
        var loader=sharedLoader(bytes,context);Class<?> owner=loader.load(good),untouched=loader.load(raw);
        Object held=owner.getConstructor().newInstance(),rawHeld=untouched.getConstructor().newInstance();
        assertTrue(context.isTransformed(owner));assertFalse(context.isTransformed(untouched));
        var reloader=new StructuralReloader(ByteBuddyAgent.install(),context,config,null);reloader.setTransformer(transformer);
        try {
            byte[] changed=compile(new SourceFile(good,"public class "+good+" {public String value(){return \"two\";}public int added(){return 7;}}")).get(good);
            var applied=reloader.reload(good,changed);assertTrue(applied.isSuccess(),applied.getError());
            assertEquals("two",owner.getMethod("value").invoke(held));
            byte[] rawEdit=compile(new SourceFile(raw,"public class "+raw+" {public int added;public String value(){return \"two\";}}")).get(raw);
            var refused=reloader.reload(raw,rawEdit);assertFalse(refused.isSuccess(),"raw fixture must not gain structural admission");
            assertTrue(refused.getError().contains("loaded before Reclazz could instrument it"),refused.getError());
            assertEquals("one",untouched.getMethod("value").invoke(rawHeld));
        } finally {com.onurkat.reclazz.ui.RestartLedger.clear();}
    }
}
