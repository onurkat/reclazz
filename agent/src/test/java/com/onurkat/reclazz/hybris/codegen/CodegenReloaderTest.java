/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.codegen;

import com.onurkat.reclazz.platform.ExtensionInfo;
import com.onurkat.reclazz.platform.HybrisContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit tests for the pieces of {@link CodegenReloader} that don't
 * require a running Hybris: {@link CodegenReloader.Kind} detection from
 * file suffix, owning-extension resolution from a beans/items.xml path,
 * and the deepest-match tie-breaker for nested extension layouts.
 *
 * Callback ordering and terminal failures use the worker's package-private ant
 * seam. Actual ant invocation and held-source application are covered by the
 * opt-in live codegen profile; these tests do not mock ProcessBuilder.
 */
class CodegenReloaderTest {

    @TempDir
    Path tempDir;

    private HybrisContext hybrisContext;
    private CodegenReloader reloader;

    @BeforeEach
    void setUp() throws Exception {
        Path hybrisHome = tempDir;
        Files.createDirectories(hybrisHome.resolve("bin").resolve("platform"));
        hybrisContext = new HybrisContext(hybrisHome);
        setField(hybrisContext, "platformHome", hybrisHome.resolve("bin/platform"));
        setField(hybrisContext, "extensions", new LinkedHashMap<String, ExtensionInfo>());
        reloader = new CodegenReloader(hybrisContext);
    }

    // ─── Kind.fromFileName ────────────────────────────────────────────────────

    @Test
    void kindFromFileName_beansXml_returnsBeans() {
        assertEquals(CodegenReloader.Kind.BEANS,
                CodegenReloader.Kind.fromFileName("examplecore-beans.xml"));
    }

    @Test
    void kindFromFileName_itemsXml_returnsItems() {
        assertEquals(CodegenReloader.Kind.ITEMS,
                CodegenReloader.Kind.fromFileName("examplecore-items.xml"));
    }

    @Test
    void kindFromFileName_unrelatedXml_returnsNull() {
        assertNull(CodegenReloader.Kind.fromFileName("examplecore-spring.xml"));
        assertNull(CodegenReloader.Kind.fromFileName("local.properties"));
        assertNull(CodegenReloader.Kind.fromFileName("beans.xml"),
                "bare beans.xml (no extension prefix) should not match");
    }

    @Test
    void kindFromFileName_suffixTailIsCaseSensitive() {
        // Hybris's own naming convention is always lowercase -beans.xml
        // / -items.xml. We intentionally don't case-fold because an
        // uppercase match would hide real naming mistakes.
        assertNull(CodegenReloader.Kind.fromFileName("MyExt-BEANS.xml"));
        assertNull(CodegenReloader.Kind.fromFileName("MyExt-ITEMS.XML"));
    }

    // ─── findOwningExtension ──────────────────────────────────────────────────

    @Test
    void findOwningExtension_beansFileInsideExtensionResources_returnsExtension() throws Exception {
        Path extDir = tempDir.resolve("bin/custom/examplecore");
        Files.createDirectories(extDir.resolve("resources"));
        ExtensionInfo ext = new ExtensionInfo(
                "examplecore", extDir, List.of(), true, false, true);
        registerExtensions(ext);

        Path beansFile = extDir.resolve("resources").resolve("examplecore-beans.xml");
        Files.writeString(beansFile, "<beans/>");

        ExtensionInfo found = reloader.findOwningExtension(beansFile);
        assertNotNull(found);
        assertEquals("examplecore", found.getName());
    }

    @Test
    void findOwningExtension_itemsFileInsideExtensionResources_returnsExtension() throws Exception {
        Path extDir = tempDir.resolve("bin/custom/myStuff");
        Files.createDirectories(extDir.resolve("resources"));
        ExtensionInfo ext = new ExtensionInfo(
                "myStuff", extDir, List.of(), true, false, true);
        registerExtensions(ext);

        Path itemsFile = extDir.resolve("resources").resolve("myStuff-items.xml");
        Files.writeString(itemsFile, "<items/>");

        ExtensionInfo found = reloader.findOwningExtension(itemsFile);
        assertNotNull(found);
        assertEquals("myStuff", found.getName(),
                "items.xml path lookup is identical to beans.xml path lookup, same owning-extension logic");
    }

    @Test
    void findOwningExtension_fileOutsideAnyExtension_returnsNull() throws Exception {
        ExtensionInfo ext = new ExtensionInfo(
                "foo", tempDir.resolve("bin/custom/foo"),
                List.of(), true, false, true);
        registerExtensions(ext);

        Path stray = tempDir.resolve("somewhere-else").resolve("stray-beans.xml");
        Files.createDirectories(stray.getParent());
        Files.writeString(stray, "<beans/>");

        assertNull(reloader.findOwningExtension(stray));
    }

    @Test
    void findOwningExtension_nestedExtensions_picksDeepestMatch() throws Exception {
        Path parentDir = tempDir.resolve("bin/custom/group");
        Path childDir = parentDir.resolve("group-child");
        Files.createDirectories(childDir.resolve("resources"));

        ExtensionInfo parent = new ExtensionInfo(
                "group", parentDir, List.of(), true, false, true);
        ExtensionInfo child = new ExtensionInfo(
                "groupChild", childDir, List.of(), true, false, true);
        registerExtensions(parent, child);

        Path childItemsFile = childDir.resolve("resources").resolve("groupChild-items.xml");
        Files.writeString(childItemsFile, "<items/>");

        ExtensionInfo found = reloader.findOwningExtension(childItemsFile);
        assertNotNull(found);
        assertEquals("groupChild", found.getName(),
                "deepest-match tie-breaker: child wins over parent");
    }

    @Test
    void findOwningExtension_sameFile_returnsStableResult() throws Exception {
        Path extDir = tempDir.resolve("bin/custom/stable");
        Files.createDirectories(extDir.resolve("resources"));
        ExtensionInfo ext = new ExtensionInfo(
                "stable", extDir, List.of(), true, false, true);
        registerExtensions(ext);

        Path beansFile = extDir.resolve("resources").resolve("stable-beans.xml");
        Files.writeString(beansFile, "<beans/>");

        ExtensionInfo first = reloader.findOwningExtension(beansFile);
        ExtensionInfo second = reloader.findOwningExtension(beansFile);
        assertSame(first, second,
                "lookup is a pure function of the extensions map, same object both calls");
    }

    @Test
    void manualUpdateLocationUsesConfiguredPortAndContext() {
        assertEquals("URL: https://localhost:9202/platform/update", HacUpdateLocation.format("9202", ""));
        assertEquals("URL: https://localhost:9443/admin/hac/platform/update", HacUpdateLocation.format("9443", "/admin/hac/"));
        assertEquals("URL: https://localhost:9202/admin%20tools/platform/update", HacUpdateLocation.format("9202", "/admin tools"));
        assertEquals(HacUpdateLocation.DEFAULT, HacUpdateLocation.format("65536", "/hac"));
        assertEquals(HacUpdateLocation.DEFAULT, HacUpdateLocation.format("9202", null));
        assertEquals(HacUpdateLocation.DEFAULT, HacUpdateLocation.read(null));
    }

    // ─── handleNewPersistence (items.xml schema side) ──────────────────────────

    @Test
    void handleNewPersistence_autoUpdateOff_warnsOnly() {
        assertEquals(CodegenReloader.SchemaOutcome.WARNED,
                reloader.handleNewPersistence(false, () -> {
                    throw new AssertionError("the updater must not run when the opt-in is off");
                }));
    }

    @Test
    void handleNewPersistence_noUpdater_warnsOnly() {
        assertEquals(CodegenReloader.SchemaOutcome.WARNED,
                reloader.handleNewPersistence(true, null));
    }

    @Test
    void handleNewPersistence_appliedWhenTheUpdateCompletes() {
        assertEquals(CodegenReloader.SchemaOutcome.APPLIED,
                reloader.handleNewPersistence(true, () -> true));
    }

    @Test
    void handleNewPersistence_appliedSchemaWithAStaleRelationGivesOnlyTheNamedRestart() {
        com.onurkat.reclazz.ui.RestartLedger.clear();
        try {
            assertEquals(CodegenReloader.SchemaOutcome.RESTART_REQUIRED, reloader.handleNewPersistence(true, () -> {
                throw new com.onurkat.reclazz.hybris.RunningSystemUpdater.RestartRequired(
                        "relation ExampleSet needs a restart before saving it");
            }));
            var notes = com.onurkat.reclazz.ui.RestartLedger.digest();
            assertTrue(notes.stream().anyMatch(s -> s.contains("ExampleSet") && s.contains("restart")));
            assertFalse(notes.stream().anyMatch(s -> s.contains("HAC")));
        } finally {
            com.onurkat.reclazz.ui.RestartLedger.clear();
        }
    }

    @Test
    void handleNewPersistence_failedUpdateFallsBackToTheManualGuidance() {
        com.onurkat.reclazz.ui.RestartLedger.clear();
        try {
            assertEquals(CodegenReloader.SchemaOutcome.FAILED,
                    reloader.handleNewPersistence(true, () -> false));
            assertTrue(com.onurkat.reclazz.ui.RestartLedger.digest().stream()
                            .anyMatch(s -> s.contains("Update Running System")),
                    "a failed auto-update must leave a restart-ledger note to run it in HAC");
        } finally {
            com.onurkat.reclazz.ui.RestartLedger.clear();
        }
    }

    @Test void acceptedCallbackRunsAfterSuccessAndPersistence() throws Exception {
        assertEquals(List.of("expose","admitted","success","schema","accepted"),acceptedRun(true,true,false));
    }
    @Test void noAcceptedCallbackOnAntFailure() throws Exception {
        assertTrue(acceptedRun(false,true,false).stream().noneMatch("accepted"::equals));
    }
    @Test void noAcceptedCallbackOnAdmissionFailure() throws Exception {
        assertTrue(acceptedRun(true,false,false).stream().noneMatch("accepted"::equals));
    }
    @Test void noAcceptedCallbackForSupersededRun() throws Exception {
        var order=acceptedRun(true,true,true);
        assertEquals(1,order.stream().filter("accepted"::equals).count());
        assertEquals(1,order.stream().filter("admitted"::equals).count());
        assertTrue(order.indexOf("accepted")>order.indexOf("schema"),order.toString());
    }
    @Test void noAcceptedCallbackWhenSupersededDuringPersistence() throws Exception {
        var order=acceptedRun(true,true,false,true);
        assertEquals(1,order.stream().filter("accepted"::equals).count(),order.toString());
        assertEquals(2,order.stream().filter("admitted"::equals).count());
    }
    private List<String> acceptedRun(boolean antOk,boolean admissionOk,boolean supersede) throws Exception {
        return acceptedRun(antOk,admissionOk,supersede,false);
    }
    private List<String> acceptedRun(boolean antOk,boolean admissionOk,boolean supersede,boolean duringPersistence) throws Exception {
        var order=new java.util.concurrent.CopyOnWriteArrayList<String>();
        Path extDir=tempDir.resolve("bin/custom/accepted");Files.createDirectories(extDir.resolve("resources"));
        Path xml=extDir.resolve("resources/accepted-items.xml");Files.writeString(xml,"<items>first</items>");
        registerExtensions(new ExtensionInfo("accepted",extDir,List.of(),true,false,true));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var runs=new java.util.concurrent.atomic.AtomicInteger();
        var candidate=new CodegenReloader(hybrisContext,true,()->{
                order.add("schema");
                if(duringPersistence && runs.get()==1) {
                    entered.countDown();try {assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));}
                    catch(InterruptedException e){throw new RuntimeException(e);}
                }
                return true;
            },
            ()->order.add("expose"),()->new com.onurkat.reclazz.ext.PremiumContext.GeneratedOutput() {
                public java.util.concurrent.CompletionStage<Void> compiled(Runnable expose) {
                    if(!admissionOk)return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("admission refused"));
                    expose.run();order.add("admitted");return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                public void failed(){order.add("failed");}
            },()->order.add("accepted")) {
                @Override boolean runAnt(ExtensionInfo extension) {
                    if(runs.incrementAndGet()==1 && supersede) {
                        entered.countDown();try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))return false;}
                        catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
                    }
                    return antOk;
                }
            };
        com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,message)->{
            if(level.equals("OK") && message.startsWith("Codegen regen+compile done for accepted"))order.add("success");
        };
        com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
        try {
            candidate.handle(new com.onurkat.reclazz.watcher.ChangeEvent(xml,com.onurkat.reclazz.watcher.ChangeEvent.Type.MODIFIED,"accepted","resources"));
            if(supersede || duringPersistence) {
                assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
                Files.writeString(xml,"<items>second</items>");
                candidate.handle(new com.onurkat.reclazz.watcher.ChangeEvent(xml,com.onurkat.reclazz.watcher.ChangeEvent.Type.MODIFIED,"accepted","resources"));
                release.countDown();
            }
            Field running=CodegenReloader.class.getDeclaredField("running");running.setAccessible(true);
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(running.getBoolean(candidate) && System.nanoTime()<end)Thread.sleep(10);
            assertTrue(!running.getBoolean(candidate),"codegen worker did not finish");
            assertEquals(supersede || duringPersistence?2:1,runs.get());return List.copyOf(order);
        } finally {
            release.countDown();com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);
            Field executor=CodegenReloader.class.getDeclaredField("executor");executor.setAccessible(true);
            ((java.util.concurrent.ExecutorService)executor.get(candidate)).shutdownNow();
            com.onurkat.reclazz.ui.RestartLedger.clear();
        }
    }

    @Test
    void removesWatchedBootstrapCopiesBeforeAntButLeavesUnwatchedOutput() throws Exception {
        Path watched = tempDir.resolve("bin/custom/watched"), other = tempDir.resolve("bin/custom/other");
        registerExtensions(new ExtensionInfo("watched", watched, List.of(), true, false, true),
                new ExtensionInfo("other", other, List.of(), true, false, true));
        Path generated = tempDir.resolve("bin/platform/bootstrap/gensrc/example/Dto.java");
        Files.createDirectories(generated.getParent()); Files.writeString(generated, "// generated ownership");
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "example/Dto", null, "java/lang/Object", null);
        writer.visitSource("Dto.java", null); writer.visitEnd();
        Path stale = watched.resolve("classes/example/Dto.class"), retained = other.resolve("classes/example/Dto.class");
        for (Path file : List.of(stale, retained)) { Files.createDirectories(file.getParent()); Files.write(file, writer.toByteArray()); }
        Path xml = watched.resolve("resources/watched-beans.xml"); Files.createDirectories(xml.getParent()); Files.writeString(xml, "<beans/>");
        var observed = new java.util.concurrent.atomic.AtomicBoolean();
        var runs = new java.util.concurrent.atomic.AtomicInteger();
        CodegenReloader candidate = new CodegenReloader(hybrisContext) {
            @Override boolean runAnt(ExtensionInfo extension) {
                observed.set(!Files.exists(stale) && Files.exists(retained)); runs.incrementAndGet(); return true;
            }
        };
        candidate.setWatchedExtensions("watched"::equals);
        try {
            candidate.handle(new com.onurkat.reclazz.watcher.ChangeEvent(xml,
                    com.onurkat.reclazz.watcher.ChangeEvent.Type.MODIFIED, "watched", "resources"));
            Field running = CodegenReloader.class.getDeclaredField("running"); running.setAccessible(true);
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (running.getBoolean(candidate) && System.nanoTime() < end) Thread.sleep(10);
            assertTrue(!running.getBoolean(candidate), "codegen worker did not finish");
            assertEquals(1, runs.get()); assertTrue(observed.get(), "cleanup must precede ant and respect watched ownership");
        } finally {
            Field executor = CodegenReloader.class.getDeclaredField("executor"); executor.setAccessible(true);
            ((java.util.concurrent.ExecutorService) executor.get(candidate)).shutdownNow();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void registerExtensions(ExtensionInfo... exts) throws Exception {
        Map<String, ExtensionInfo> map = new LinkedHashMap<>();
        for (ExtensionInfo e : exts) {
            map.put(e.getName(), e);
        }
        setField(hybrisContext, "extensions", map);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> cls, String fieldName) throws NoSuchFieldException {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
    @Test void scopeFenceCoversAdmissionBeforeTheNativeOutputHold()throws Exception {
        Path dir=tempDir.resolve("bin/custom/fenced");Files.createDirectories(dir.resolve("resources"));
        Path xml=dir.resolve("resources/fenced-beans.xml");Files.writeString(xml,"<beans/>");
        registerExtensions(new ExtensionInfo("fenced",dir,List.of(),true,false,true));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var woke=new java.util.concurrent.CountDownLatch(1);var fence=new com.onurkat.reclazz.config.ScopeFence();
        var candidate=new CodegenReloader(hybrisContext,false,null,()->{},()->{
            entered.countDown();
            try {assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new RuntimeException(failure);}
            return new com.onurkat.reclazz.ext.PremiumContext.GeneratedOutput(){
                public java.util.concurrent.CompletionStage<Void> compiled(Runnable expose){expose.run();return java.util.concurrent.CompletableFuture.completedFuture(null);}
                public void failed(){}
            };
        },()->{}){@Override boolean runAnt(ExtensionInfo ext){return true;}};
        candidate.setScopeFence(fence);
        try {
            candidate.handle(new com.onurkat.reclazz.watcher.ChangeEvent(xml,com.onurkat.reclazz.watcher.ChangeEvent.Type.MODIFIED,"fenced","resources"));
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertFalse(fence.tryCloseOrRegister(()->{
                org.junit.jupiter.api.Assertions.assertFalse(Thread.holdsLock(fence));woke.countDown();
            }));
            assertEquals(1,woke.getCount());release.countDown();
            assertTrue(woke.await(10,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(fence.tryCloseOrRegister(()->org.junit.jupiter.api.Assertions.fail("permit already released")));
            fence.reopen();scopeWorker(candidate).submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);
        } finally {release.countDown();fence.reopen();scopeWorker(candidate).shutdownNow();}
    }
    @Test void deferredCodegenChecksTheNewScopeAndRunsOnceAfterReopen()throws Exception {
        Path dir=tempDir.resolve("bin/custom/deferred");Files.createDirectories(dir.resolve("resources"));
        Path xml=dir.resolve("resources/deferred-beans.xml");Files.writeString(xml,"<beans/>");
        registerExtensions(new ExtensionInfo("deferred",dir,List.of(),true,false,true));
        var runs=new java.util.concurrent.atomic.AtomicInteger();var selected=new java.util.concurrent.atomic.AtomicBoolean(true);
        var candidate=new CodegenReloader(hybrisContext){@Override boolean runAnt(ExtensionInfo ext){runs.incrementAndGet();return true;}};
        var fence=new com.onurkat.reclazz.config.ScopeFence();candidate.setScopeFence(fence);candidate.setWatchedExtensions(name->selected.get());
        var event=new com.onurkat.reclazz.watcher.ChangeEvent(xml,com.onurkat.reclazz.watcher.ChangeEvent.Type.MODIFIED,"deferred","resources");
        try {
            assertTrue(fence.tryCloseOrRegister(()->{}));candidate.handle(event);assertEquals(0,runs.get());
            selected.set(false);fence.reopen();scopeWorker(candidate).submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);assertEquals(0,runs.get());
            selected.set(true);assertTrue(fence.tryCloseOrRegister(()->{}));candidate.handle(event);candidate.handle(event);assertEquals(0,runs.get());
            fence.reopen();scopeWorker(candidate).submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);assertEquals(1,runs.get());
            assertTrue(fence.tryCloseOrRegister(()->org.junit.jupiter.api.Assertions.fail("worker permit leaked")));fence.reopen();
        } finally {fence.reopen();scopeWorker(candidate).shutdownNow();}
    }
    private static java.util.concurrent.ExecutorService scopeWorker(CodegenReloader candidate)throws Exception {
        Field field=CodegenReloader.class.getDeclaredField("executor");field.setAccessible(true);
        return (java.util.concurrent.ExecutorService)field.get(candidate);
    }

}
