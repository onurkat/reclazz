/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.hybris.cockpitng.core.persistence.packaging.WidgetClassLoader;
import com.onurkat.reclazz.agent.ClassReloader;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.HybrisPlatformContext;
import com.onurkat.reclazz.transform.*;
import com.onurkat.reclazz.util.ClassLookup;
import com.onurkat.reclazz.util.BackofficePreparedOwners;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BackofficeModuleReloadTest extends TransformTestBase {
    @TempDir Path home;
    private static final java.util.concurrent.atomic.AtomicInteger IDS = new java.util.concurrent.atomic.AtomicInteger();
    private final String name = "com.example.Widget" + IDS.incrementAndGet();
    private String internal() { return name.replace('.', '/'); }
    private byte[] bytes(String value, boolean added) {
        return compile(new SourceFile(name, "package com.example; public class " + name.substring(name.lastIndexOf('.')+1)
                + " { public String run(){return " + (added ? "added()+" : "") + "\"" + value + "\";} "
                + (added ? "public String added(){return \"new\";}" : "") + " }")).get(internal());
    }
    private HybrisPlatformContext platform(byte[] initial, boolean duplicate) throws Exception {
        Files.createDirectories(home.resolve("bin/platform"));
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/localextensions.xml"), "<hybrisconfig><extensions/></hybrisconfig>");
        for (String ext : duplicate ? List.of("example", "duplicate") : List.of("example")) {
            Path root=home.resolve("bin/custom/"+ext);
            Path file=root.resolve("backoffice/classes/"+internal()+".class");
            Files.createDirectories(file.getParent()); Files.write(file,initial);
            Files.createDirectories(root.resolve("backoffice/src"));
            Files.writeString(root.resolve("extensioninfo.xml"), "<extensioninfo><extension name='"+ext
                    +"'><meta key='backoffice-module' value='true'/></extension></extensioninfo>");
        }
        var platform=new HybrisPlatformContext(home,AgentConfig.parse(null)); platform.initialize(); return platform;
    }
    private TransformContext context(byte[] initial, boolean duplicate) throws Exception {
        var context=new TransformContext();context.populateFromPlatformContext(platform(initial,duplicate));return context;
    }
    @Test void widgetLoaderClassWithMatchingBytesIsPrepared() throws Exception {
        byte[] initial=bytes("before",false);var context=context(initial,false);
        var loader=new WidgetClassLoader(getClass().getClassLoader());
        byte[] enhanced=new ReclazzTransformer(context,AgentConfig.parse(null)).transform(loader,internal(),null,null,initial);
        assertNotNull(enhanced,"native opaque-loader module must be prepared at definition");
        Class<?> target=loader.define(name,enhanced);
        assertNull(target.getProtectionDomain().getCodeSource().getLocation());
        assertNotNull(context.getMetadata(internal()));
        assertEquals("before",target.getMethod("run").invoke(target.getConstructor().newInstance()));
    }
    @Test void mismatchingBytesAreNotPrepared() throws Exception {
        var context=context(bytes("before",false),false);
        assertNull(new ReclazzTransformer(context,AgentConfig.parse(null)).transform(
                new WidgetClassLoader(getClass().getClassLoader()),internal(),null,null,bytes("stale",false)));
        assertNull(context.getMetadata(internal()));
    }
    @Test void otherLoaderForSameNameNotPrepared() throws Exception {
        byte[] initial=bytes("before",false);var context=context(initial,false);
        assertNull(new ReclazzTransformer(context,AgentConfig.parse(null)).transform(getClass().getClassLoader(),internal(),null,null,initial));
        assertNull(context.getMetadata(internal()));
    }
    @Test void duplicateOwnerRefuses() throws Exception {
        byte[] initial=bytes("before",false);var context=context(initial,true);
        assertNull(new ReclazzTransformer(context,AgentConfig.parse(null)).transform(
                new WidgetClassLoader(getClass().getClassLoader()),internal(),null,null,initial));
        assertNull(context.getMetadata(internal()));
    }
    @Test void excludedClassRemainsUnprepared() throws Exception {
        byte[] initial=bytes("before",false);var context=context(initial,false);
        assertNull(new ReclazzTransformer(context,AgentConfig.parse("excludeClasses="+name)).transform(
                new WidgetClassLoader(getClass().getClassLoader()),internal(),null,null,initial));
        assertFalse(BackofficePreparedOwners.contains(internal()));
    }
    @Test void collectedOwnerRefusesExplicitlyBeforeMetadataChanges() throws Exception {
        byte[] initial=bytes("before",false);var context=context(initial,false);
        var transformer=new ReclazzTransformer(context,AgentConfig.parse(null));
        var owner=new WidgetClassLoader(getClass().getClassLoader());
        assertNotNull(transformer.transform(owner,internal(),null,null,initial));
        var f=BackofficePreparedOwners.class.getDeclaredField("OWNERS");f.setAccessible(true);
        Object entry=((java.util.Map<?,?>)f.get(null)).get(internal());
        var loader=entry.getClass().getDeclaredField("loader");loader.setAccessible(true);
        ((java.lang.ref.WeakReference<?>)loader.get(entry)).clear(); // deterministic collected-owner state
        var metadata=context.getMetadata(internal());
        var error=assertThrows(IllegalStateException.class,()->ClassLookup.findLoadedClass(name,ByteBuddyAgent.install()));
        assertTrue(error.getMessage().contains("Backoffice owner loader unavailable"));
        var result=new StructuralReloader(ByteBuddyAgent.install(),context,AgentConfig.parse(null),null).reload(name,bytes("after",true));
        assertFalse(result.isSuccess()); assertTrue(result.getError().contains("owner loader unavailable"));
        assertSame(metadata,context.getMetadata(internal()));
    }
    @Test void ownerLookupDoesNotLetNoClassDefFoundErrorEscape() throws Exception {
        byte[] initial=bytes("before",false);var context=context(initial,false);
        var owner=new WidgetClassLoader(getClass().getClassLoader());
        assertNotNull(new ReclazzTransformer(context,AgentConfig.parse(null)).transform(owner,internal(),null,null,initial));
        var failing=(java.lang.instrument.Instrumentation)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{java.lang.instrument.Instrumentation.class},
                (p,m,a)->{throw new NoClassDefFoundError("com/example/MissingBase");});
        var failure=assertThrows(IllegalStateException.class,()->ClassLookup.findLoadedClass(name,failing));
        assertTrue(failure.getMessage().contains("owner loader unavailable"));
        assertInstanceOf(NoClassDefFoundError.class,failure.getCause());
    }
    @Test void ordinaryClassLookupUnchanged() {
        assertSame(String.class,ClassLookup.findLoadedClass("java.lang.String",null));
    }
    @Test void newlyCompiledBackofficeClassIsAdmitted() throws Exception {
        byte[] initial=bytes("before",false);var platform=platform(initial,false);
        Path file=home.resolve("bin/custom/example/backoffice/classes/"+internal()+".class");
        Files.delete(file);var context=new TransformContext();context.populateFromPlatformContext(platform);
        Files.write(file,initial);
        assertNotNull(new ReclazzTransformer(context,AgentConfig.parse(null)).transform(
                new WidgetClassLoader(getClass().getClassLoader()),internal(),null,null,initial));
    }
    @Test void addedMethodOnPreparedWidgetClassReloadsOnStockJdk() throws Exception { reloadWithForeignContext(false); }
    @Test void reloadAfterSecondLoaderTargetsPreparedOwnerOnly() throws Exception { reloadWithForeignContext(true); }
    private void reloadWithForeignContext(boolean throwing) throws Exception {
        byte[] initial=bytes("before",false);var context=context(initial,false);var config=AgentConfig.parse(null);
        var transformer=new ReclazzTransformer(context,config);var owner=new WidgetClassLoader(getClass().getClassLoader());
        byte[] enhanced=transformer.transform(owner,internal(),null,null,initial);
        assertNotNull(enhanced,"central baseline: Backoffice module is not prepared on HEAD");
        Class<?> target=owner.define(name,enhanced);Object held=target.getConstructor().newInstance();
        var foreign=new WidgetClassLoader(getClass().getClassLoader());
        var metadata=context.getMetadata(internal());
        assertNull(transformer.transform(foreign,internal(),null,null,initial));
        assertSame(metadata,context.getMetadata(internal()),"declining second loader preserves owner metadata");
        Class<?> other=foreign.define(name,initial);Object otherHeld=other.getConstructor().newInstance();
        byte[] cached=TransformedClassCache.get(internal());
        assertNull(transformer.transform(foreign,internal(),other,null,bytes("foreign",false)));
        assertSame(metadata,context.getMetadata(internal()));
        assertArrayEquals(cached,TransformedClassCache.get(internal()),"foreign redefine cannot replace owner cache");
        var inst=ByteBuddyAgent.install();var reloader=new StructuralReloader(inst,context,config,null);reloader.setTransformer(transformer);
        ClassLoader old=Thread.currentThread().getContextClassLoader();
        inst.addTransformer(transformer,true);
        try {
            Thread.currentThread().setContextClassLoader(throwing ? new ClassLoader(null) {
                @Override protected Class<?> loadClass(String n,boolean resolve) throws ClassNotFoundException {
                    if(n.equals(name)) throw new NoClassDefFoundError("com/example/MissingBase");
                    return super.loadClass(n,resolve);
                }
            } : foreign);
            assertSame(target,ClassLookup.findLoadedClass(name,inst));
            var body=new ClassReloader(inst).reload(name,bytes("body",false));
            assertTrue(body.isSuccess(),body.getError());
            assertEquals("body",target.getMethod("run").invoke(held));
            var structural=reloader.reload(name,bytes("after",true));
            assertTrue(structural.isSuccess(),structural.getError());
            assertEquals("newafter",target.getMethod("run").invoke(held),"held object calls the newly added helper");
            assertEquals("before",other.getMethod("run").invoke(otherHeld));
            assertThrows(NoSuchMethodException.class,()->other.getMethod("added"));
        } finally { Thread.currentThread().setContextClassLoader(old);inst.removeTransformer(transformer); }
    }
}
