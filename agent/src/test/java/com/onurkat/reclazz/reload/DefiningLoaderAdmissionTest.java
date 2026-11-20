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
            var result=reloader.reload(name,next);assertTrue(result.isSuccess(),result.getError());assertEquals("two",owner.getMethod("value").invoke(held));
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
