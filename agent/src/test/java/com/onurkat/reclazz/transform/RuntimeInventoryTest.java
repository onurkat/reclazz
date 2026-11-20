/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.config.*;
import com.onurkat.reclazz.platform.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeInventoryTest extends TransformTestBase {
    @TempDir Path home;
    private static void field(Object target,String name,Object value)throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private HybrisPlatformContext platform(AgentConfig config)throws Exception {return platform(config,false);}
    private HybrisPlatformContext platform(AgentConfig config,boolean nonCustomB)throws Exception {
        Path platform=Files.createDirectories(home.resolve("bin/platform"));Files.createDirectories(home.resolve("config"));
        Map<String,ExtensionInfo> extensions=new LinkedHashMap<>();
        for(String name:List.of("a","b")){
            Path dir=home.resolve(name);Files.createDirectories(dir.resolve("classes"));
            extensions.put(name,new ExtensionInfo(name,dir,List.of(),true,false,!(nonCustomB&&name.equals("b"))));
        }
        var hybris=new HybrisContext(home);field(hybris,"platformHome",platform);field(hybris,"configDir",home.resolve("config"));field(hybris,"extensions",extensions);
        var result=new HybrisPlatformContext(home,config);field(result,"hybrisContext",hybris);return result;
    }
    private static void publish(AgentConfig config,WatchInventory inventory,Set<String> names){
        var old=config.runtime();assertTrue(config.compareAndSetRuntime(old,new AgentConfig.Runtime(old.revision()+1,old.options(),new AgentConfig.WatchScope(false,names),old.stats(),inventory)));
    }
    @Test void stagingIsPureAndCapturedTransformKeepsItsOwnRevision()throws Exception {
        var config=AgentConfig.parse("watchExtensions=a");var platform=platform(config);var context=new TransformContext();context.bindRuntime(config,platform);
        Files.write(home.resolve("a/classes/Old.class"),new byte[]{1});Files.write(home.resolve("b/classes/New.class"),new byte[]{2});
        var a=context.stageInventory(new ScopeView(platform,config.runtime().scope()),new Class<?>[0]);publish(config,a,Set.of("a"));
        var b=context.stageInventory(new ScopeView(platform,new AgentConfig.WatchScope(false,Set.of("b"))),new Class<?>[0]);
        assertFalse(context.isWatched("New"));assertEquals(Set.of("a"),config.getWatchExtensions());
        try(var snapshot=context.snapshot()){
            publish(config,b,Set.of("b"));assertFalse(context.isWatched("New"));assertTrue(context.isInWatchedDir(home.resolve("a/classes/X.class")));
            assertFalse(context.isInWatchedDir(home.resolve("b/classes/X.class")));
        }
        assertTrue(context.isWatched("New"));assertTrue(context.isWatched("Old"),"previously admitted names retain ownership");
        assertFalse(context.isInWatchedDir(home.resolve("a/classes/X.class")));assertTrue(context.isInWatchedDir(home.resolve("b/classes/X.class")));
    }
    @Test void futureOutputNeedsActiveRootAndDiskEvidenceAndRawOtherLoaderCannotBorrowProof()throws Exception {
        var config=AgentConfig.parse("watchExtensions=a");var platform=platform(config);var context=new TransformContext();context.bindRuntime(config,platform);
        publish(config,context.stageInventory(platform,new Class<?>[0]),Set.of("a"));
        Path b=home.resolve("b/classes");var source=new CodeSource(b.toUri().toURL(),(java.security.cert.Certificate[])null);
        var domain=new ProtectionDomain(source,null);Files.write(b.resolve("Future.class"),new byte[]{1});
        assertTrue(context.knownOutput(domain));assertFalse(context.admitOutput("Future",domain));
        publish(config,context.stageInventory(new ScopeView(platform,new AgentConfig.WatchScope(false,Set.of("a","b"))),new Class<?>[0]),Set.of("a","b"));
        Files.write(b.resolve("Later.class"),new byte[]{1});assertTrue(context.admitOutput("Later",domain));assertFalse(context.admitOutput("Proxy",domain));
        ClassLoader raw=new ClassLoader(){},instrumented=new ClassLoader(){};
        context.markRaw(raw,"Later");context.markTransformed(instrumented,"Later");assertTrue(context.isLoadedUninstrumented(raw,"Later"));assertFalse(context.isLoadedUninstrumented(instrumented,"Later"));
        context.markTransformed(raw,"Later");assertFalse(context.isLoadedUninstrumented(raw,"Later"));
        context.markRaw(raw,"Later");assertTrue(context.isLoadedUninstrumented(raw,"Later"),"failed/retried definition must discard its previous owner proof");
    }
    @Test void latePlatformDefinitionBetweenCensusAndPublishIsTrackedRaw()throws Exception {
        var config=AgentConfig.parse("watchExtensions=a");var platform=platform(config,true);var context=new TransformContext();context.bindRuntime(config,platform);
        String name="LatePlatformScope";byte[] bytes=compile(new SourceFile(name,"public class "+name+" {public int value(){return 1;}}")).get(name);
        Path root=home.resolve("b/classes");Files.write(root.resolve(name+".class"),bytes);
        var target=new ScopeView(platform,new AgentConfig.WatchScope(false,Set.of("a","b")));var staged=context.stageInventory(target,new Class<?>[0]);
        var transformer=new ReclazzTransformer(context,config);var domain=new ProtectionDomain(new CodeSource(root.toUri().toURL(),(java.security.cert.Certificate[])null),null);
        Class<?> late=new ClassLoader(getClass().getClassLoader()){Class<?> define()throws Exception {assertNull(transformer.transform(this,name,null,domain,bytes));return defineClass(name,bytes,0,bytes.length,domain);}}.define();
        publish(config,staged,Set.of("a","b"));assertTrue(context.isWatched(name));assertFalse(context.isTransformed(late));assertTrue(context.isLoadedUninstrumented(name),"late raw definition must not be assumed instrumented after publication");
    }

    @Test void expiredAndProvenTransformedCensusDefinitionsArePruned()throws Exception {
        var config=AgentConfig.parse("watchExtensions=a");var platform=platform(config);var context=new TransformContext();context.bindRuntime(config,platform);
        String name="RawCensus";byte[] bytes=compile(new SourceFile(name,"public class "+name+" {} ")).get(name);
        Files.write(home.resolve("a/classes/"+name+".class"),bytes);
        Class<?> raw=new ClassLoader(getClass().getClassLoader()){Class<?> define(){return defineClass(name,bytes,0,bytes.length);}}.define();
        var first=context.stageInventory(platform,new Class<?>[]{raw});publish(config,first,Set.of("a"));
        assertTrue(context.isLoadedUninstrumented(raw.getClassLoader(),name));
        first.rawDefinitions().get(name).forEach(java.lang.ref.Reference::clear);
        var cleared=context.stageInventory(platform,new Class<?>[0]);assertFalse(cleared.rawDefinitions().containsKey(name));
        var reobserved=context.stageInventory(platform,new Class<?>[]{raw});publish(config,reobserved,Set.of("a"));
        context.markTransformed(raw.getClassLoader(),name);
        var second=context.stageInventory(platform,new Class<?>[0]);assertFalse(second.rawDefinitions().containsKey(name));
    }

}
