/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.compiler.IncrementalCompiler;
import com.onurkat.reclazz.ext.PremiumContext.GeneratedOutput;
import com.onurkat.reclazz.hybris.codegen.CodegenReloader;
import com.onurkat.reclazz.platform.ExtensionInfo;
import com.onurkat.reclazz.platform.HybrisContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.spring.SpringReloadOrchestrator;
import com.onurkat.reclazz.ext.PremiumModules;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real held-batch retention and javac publication; native apply is covered by the live profile. */
class HeldSourceRetryAfterCodegenTest {
    @TempDir Path tmp;
    Path source, output;
    ReloadQueue queue;
    IncrementalCompiler compiler;
    Map<Path,ChangeEvent> pending;
    Map<Path,ChangeEvent> savedPending;
    Set<Path> speculative, savedSpeculative;
    long savedGeneration, savedAttempt;
    final AtomicInteger calls=new AtomicInteger(), active=new AtomicInteger(), maximum=new AtomicInteger();
    final AtomicReference<Throwable> failure=new AtomicReference<>();
    final List<String> messages=new CopyOnWriteArrayList<>();
    final CountDownLatch inFlight=new CountDownLatch(1), release=new CountDownLatch(1);
    volatile boolean blockFirst;
    StatusReporter.StatusListener listener=(level,message)->messages.add(message);

    static Field field(String name) throws Exception {var f=ReclazzAgent.class.getDeclaredField(name);f.setAccessible(true);return f;}
    @SuppressWarnings("unchecked") @BeforeEach void setup() throws Exception {
        pending=(Map<Path,ChangeEvent>)field("pendingJavaChanges").get(null);
        synchronized(pending) {
            savedPending=new LinkedHashMap<>(pending);pending.clear();
            speculative=(Set<Path>)field("speculativeJava").get(null);savedSpeculative=new HashSet<>(speculative);speculative.clear();
            savedGeneration=field("javaChangeGeneration").getLong(null);savedAttempt=field("lastJavaAttemptGeneration").getLong(null);
            field("javaChangeGeneration").setLong(null,0);field("lastJavaAttemptGeneration").setLong(null,-1);
        }
        output=Files.createDirectories(tmp.resolve("classes"));source=tmp.resolve("src/com/example/A.java");Files.createDirectories(source.getParent());
        Files.writeString(source,"package com.example; public class A { public String value(){return B.value();} }");
        PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PlatformContext.class},
            (p,m,a)->m.getName().equals("getClassOutputDirs")?Map.of("m",List.of(output)):null);
        compiler=new IncrementalCompiler(output.toString(),platform) {
            @Override public CompileResult compilePackage(Map<String,List<Path>> sources) {
                int number=calls.incrementAndGet();maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
                try {
                    CompileResult result=super.compilePackage(sources);
                    if(blockFirst && number==1) {inFlight.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));}
                    // Preserve real javac output on disk; this unit stops at publication, before framework apply.
                    return result.isSuccess()?CompileResult.success(Map.of(),result.getCompileTimeMs()):result;
                } catch(Throwable t) {failure.set(t);return CompileResult.failure(List.of(t.toString()));}
                finally {active.decrementAndGet();}
            }
        };
        queue=ReloadQueue.start(e->{},new ReloadQueue.Bracket(){public void begin(){}public void end(){}});
        StatusReporter.addListener(listener);
    }
    @AfterEach void cleanup() throws Exception {
        release.countDown();drain();queue.shutdown();StatusReporter.removeListener(listener);
        synchronized(pending) {pending.clear();pending.putAll(savedPending);speculative.clear();speculative.addAll(savedSpeculative);field("javaChangeGeneration").setLong(null,savedGeneration);field("lastJavaAttemptGeneration").setLong(null,savedAttempt);}
    }
    void save() throws Exception {
        synchronized(pending) {pending.put(source,new ChangeEvent(source,ChangeEvent.Type.MODIFIED,"m","src"));var f=field("javaChangeGeneration");f.setLong(null,f.getLong(null)+1);}
        queue.submitAutomatic(source.toString(),"Java save",this::attempt);
    }
    void attempt() {
        try {
            var m=ReclazzAgent.class.getDeclaredMethod("handleJavaBatch",IncrementalCompiler.class,ClassReloader.class,SpringReloadOrchestrator.class,PremiumModules.class);
            m.setAccessible(true);m.invoke(null,compiler,null,null,null);
        } catch(Throwable t) {failure.set(t);}
    }
    void signal() {ReclazzAgent.retryHeldSourcesAfterCodegen(queue,this::attempt);}
    void drain() throws Exception {var done=new CountDownLatch(1);queue.submit("test barrier",done::countDown);assertTrue(done.await(30,TimeUnit.SECONDS));assertNull(failure.get());}
    void generateDependency() throws Exception {
        Path b=tmp.resolve("generated/B.java");Files.createDirectories(b.getParent());Files.writeString(b,"package com.example; public class B {public static String value(){return \"fresh\";} }");
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",output.toString(),b.toString()));
    }
    String value() throws Exception {
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{output.toUri().toURL()},null)) {
            var a=loader.loadClass("com.example.A");return (String)a.getMethod("value").invoke(a.getConstructor().newInstance());
        }
    }
    @Test void samePassAdmitsXmlBeforeEitherCallerOrder() {
        ChangeEvent xml = new ChangeEvent(tmp.resolve("demo-beans.xml"), ChangeEvent.Type.MODIFIED, "m", "resources");
        ChangeEvent java = new ChangeEvent(source, ChangeEvent.Type.MODIFIED, "m", "src");
        assertEquals(List.of(xml, java), ReclazzAgent.codegenFirst(List.of(java, xml)));
        assertEquals(List.of(xml, java), ReclazzAgent.codegenFirst(List.of(xml, java)));
        assertEquals(List.of(java), ReclazzAgent.codegenFirst(List.of(java)));
    }

    @Test void callerFromLaterCallbackWaitsThroughBothGenerations() throws Exception {
        CodegenSourceGate gate = (CodegenSourceGate) field("codegenSourceGate").get(null);
        Object previous = field("resumeJavaSources").get(null);
        Runnable resume = () -> queue.submitAutomatic("java-source-batch", "Resume callers", this::attempt);
        field("resumeJavaSources").set(null, resume);
        try {
            Runnable first = gate.begin();
            save(); drain();
            assertEquals(0, calls.get());
            Runnable last = gate.begin();
            first.run();
            drain();
            assertEquals(0, calls.get());
            generateDependency();
            gate.accepted(this::signal);
            last.run(); drain(); drain();
            assertEquals(1, calls.get());
            assertEquals("fresh", value());
            assertTrue(messages.stream().noneMatch(m -> m.startsWith("Compilation failed")));
        } finally {
            field("resumeJavaSources").set(null, previous);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void nativeAdmissionHoldsThroughEchoAndReleasesOnSuccessOrTimeout(boolean timeout) throws Exception {
        Path platform = tmp.resolve("bin/platform"), ext = tmp.resolve("bin/custom/demo");
        Path bin = Files.createDirectories(platform.resolve("apache-ant/bin"));
        Files.writeString(platform.resolve("setantenv.sh"), "export PATH=\"$PWD/apache-ant/bin:$PATH\"\n");
        Path ant = Files.writeString(bin.resolve("ant"), "#!/bin/sh\necho BUILD SUCCESSFUL\n");
        if (!System.getProperty("os.name").startsWith("Windows")) assertTrue(ant.toFile().setExecutable(true));
        Files.writeString(platform.resolve("setantenv.bat"), "@set PATH=%CD%\\apache-ant\\bin;%PATH%\r\n");
        Files.writeString(bin.resolve("ant.bat"), "@echo BUILD SUCCESSFUL\r\n@exit /b 0\r\n");
        Path xml = Files.createDirectories(ext.resolve("resources")).resolve("demo-beans.xml");
        Files.writeString(xml, "<beans/>");
        ChangeEvent event = new ChangeEvent(xml, ChangeEvent.Type.MODIFIED, "demo", "resources");
        HybrisContext hybris = new HybrisContext(tmp);
        hybris.getExtensions().put("demo", new ExtensionInfo("demo", ext, List.of(), true, false, true));
        var admissions = new LinkedBlockingQueue<CompletableFuture<Void>>();
        var finished = new CountDownLatch(1);
        var starts = new AtomicInteger();
        CodegenSourceGate gate = (CodegenSourceGate) field("codegenSourceGate").get(null);
        Object previous = field("resumeJavaSources").get(null);
        field("resumeJavaSources").set(null, (Runnable) () -> queue.submit("Resume callers", this::attempt));
        var reloader = new CodegenReloader(hybris, false, null, () -> {}, () -> new GeneratedOutput() {
            final CompletableFuture<Void> output = new CompletableFuture<>();
            public CompletionStage<Void> compiled(Runnable expose) {
                expose.run(); admissions.add(output); return output;
            }
            public void failed() { output.completeExceptionally(new CancellationException()); }
        }, () -> gate.accepted(this::signal));
        reloader.setSourceHold(() -> {
            starts.incrementAndGet();
            Runnable releaseGate = gate.begin();
            return () -> { releaseGate.run(); finished.countDown(); };
        });
        Field executor = CodegenReloader.class.getDeclaredField("executor");
        executor.setAccessible(true);
        try {
            reloader.handle(event);
            var attempt = admissions.poll(15, TimeUnit.SECONDS); assertNotNull(attempt);
            reloader.handle(event); // native touch echo
            assertEquals(1, starts.get());
            save(); drain(); assertEquals(0, calls.get());
            if (timeout) {
                attempt.completeExceptionally(new TimeoutException("class admission timed out"));
            } else {
                Files.writeString(xml, "<beans>changed</beans>"); reloader.handle(event);
                var finalAttempt = admissions.poll(15, TimeUnit.SECONDS); assertNotNull(finalAttempt);
                drain(); assertEquals(0, calls.get());
                generateDependency(); finalAttempt.complete(null);
            }
            assertTrue(finished.await(15, TimeUnit.SECONDS)); drain(); drain();
            assertEquals(1, calls.get());
            assertEquals(timeout, pending.containsKey(source));
            reloader.handle(event);
            assertEquals(1, starts.get(), "unchanged XML cannot start a new hold");
        } finally {
            ((ExecutorService)executor.get(reloader)).shutdownNow();
            field("resumeJavaSources").set(null, previous);
        }
    }

    @Test void failedGenerationReleasesOneRealCompilerError() throws Exception {
        CodegenSourceGate gate = (CodegenSourceGate) field("codegenSourceGate").get(null);
        Object previous = field("resumeJavaSources").get(null);
        field("resumeJavaSources").set(null, (Runnable) () -> queue.submit("Resume callers", this::attempt));
        try {
            Runnable finish = gate.begin();
            save(); drain(); save(); drain();
            assertEquals(0, calls.get());
            finish.run(); drain();
            assertEquals(1, calls.get());
            assertEquals(Set.of(source), pending.keySet());
            assertEquals(1, messages.stream().filter(m -> m.startsWith("Compilation failed")).count());
        } finally {
            field("resumeJavaSources").set(null, previous);
        }
    }

    @Test void heldSourceCompilesAfterAcceptedCodegen() throws Exception {
        save();drain();assertEquals(Set.of(source),pending.keySet());assertFalse(Files.exists(output.resolve("com/example/A.class")));
        generateDependency();signal();drain();assertEquals(2,calls.get());assertTrue(pending.isEmpty());assertEquals("fresh",value());
        assertEquals(1,messages.stream().filter(s->s.startsWith("Codegen finished; compiling")).count());
    }
    @Test void retryThatFailsStaysHeldWithoutLoop() throws Exception {
        save();drain();signal();drain();drain();assertEquals(2,calls.get());assertEquals(Set.of(source),pending.keySet());
        assertEquals(2,messages.stream().filter(s->s.startsWith("Holding 1 source files")).count());
    }
    @Test void noPendingSourcesMeansNoCompile() throws Exception {signal();drain();assertEquals(0,calls.get());assertTrue(messages.stream().noneMatch(s->s.startsWith("Codegen finished; compiling")));}
    @Test void inFlightCompileThenSignalStillRetries() throws Exception {
        blockFirst=true;save();assertTrue(inFlight.await(30,TimeUnit.SECONDS));assertTrue(pending.isEmpty());
        generateDependency();signal();release.countDown();drain();assertEquals(2,calls.get());assertEquals("fresh",value());assertTrue(pending.isEmpty());
        int held=-1,retry=-1;for(int i=0;i<messages.size();i++){if(messages.get(i).startsWith("Holding"))held=i;if(messages.get(i).startsWith("Codegen finished"))retry=i;}
        assertTrue(held>=0 && retry>held,messages.toString());assertEquals(1,maximum.get());
    }
    @Test void newerSaveDuringFailedAttemptIsNotLost() throws Exception {
        blockFirst=true;save();assertTrue(inFlight.await(30,TimeUnit.SECONDS));generateDependency();
        Files.writeString(source,"package com.example; public class A {public String value(){return B.value()+\"-new\";} }");
        save();signal();release.countDown();drain();assertEquals("fresh-new",value());assertTrue(pending.isEmpty());assertEquals(2,calls.get());
    }
    @Test void deletedSourceDuringFailedAttemptIsNotResurrected() throws Exception {
        blockFirst=true;save();assertTrue(inFlight.await(30,TimeUnit.SECONDS));Files.delete(source);signal();release.countDown();drain();assertEquals(1,calls.get());assertTrue(pending.isEmpty());
    }
    @Test void multipleSignalsCoalesceToOneRetry() throws Exception {
        save();drain();generateDependency();var entered=new CountDownLatch(1);var unblock=new CountDownLatch(1);
        queue.submit("block before signals",()->{entered.countDown();try {assertTrue(unblock.await(30,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}});
        assertTrue(entered.await(30,TimeUnit.SECONDS));try {signal();signal();signal();}finally {unblock.countDown();}
        drain();assertEquals(2,calls.get());assertEquals("fresh",value());assertEquals(1,messages.stream().filter(s->s.startsWith("Codegen finished")).count());
    }
    @Test void compilerNeverRunsConcurrently() throws Exception {
        blockFirst=true;save();assertTrue(inFlight.await(30,TimeUnit.SECONDS));generateDependency();save();signal();signal();release.countDown();drain();assertEquals(1,maximum.get());assertEquals("fresh",value());
    }
}
