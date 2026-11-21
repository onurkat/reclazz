/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecordCodecsTest {
    record P(int a) { }
    record Other(String value) { }
    static final class Bean { public List<P> children; }
    static final class Closed { public String name; }
    private static Object snapshot(Object target) throws Exception {
        String name = target instanceof ObjectReader ? "_rootDeserializer" : target instanceof ObjectWriter ? "_prefetch" : "_deserializer";
        Field field = target.getClass().getDeclaredField(name);
        return UnsafeAccess.getObject(target, field);
    }
    private static void flush(ObjectMapper mapper) throws Exception {
        mapper.getSerializerProvider().flushCachedSerializers();
        Field context = ObjectMapper.class.getDeclaredField("_deserializationContext");
        Object dc = UnsafeAccess.getObject(mapper, context);
        Field cache = DeserializationContext.class.getDeclaredField("_cache");
        Object value = UnsafeAccess.getObject(dc, cache);
        value.getClass().getMethod("flushCachedDeserializers").invoke(value);
        Field roots = ObjectMapper.class.getDeclaredField("_rootDeserializers");
        ((Map<?, ?>) UnsafeAccess.getObject(mapper, roots)).clear();
    }
    private static void register(Object codec) throws Exception {
        RecordCodecs.track(codec); RecordCodecs.current(codec, snapshot(codec));
        assertTrue(RecordCodecs.preflight().isEmpty());
    }
    @Test void noRecordGenerationDoesNotContendOnGlobalRegistryMonitor()throws Exception {
        ObjectMapper mapper=new ObjectMapper();ObjectReader reader=mapper.readerFor(P.class);Object raw=snapshot(reader);
        var generation=RecordCodecs.class.getDeclaredField("generation");generation.setAccessible(true);long previous=generation.getLong(null);
        var monitor=RecordCodecs.class.getDeclaredField("lock");monitor.setAccessible(true);Object lock=monitor.get(null);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);var started=new java.util.concurrent.CountDownLatch(4);
        var finished=new java.util.concurrent.CountDownLatch(4);List<java.util.concurrent.Future<?>> futures=new ArrayList<>();
        try {
            generation.setLong(null,0);
            synchronized(lock) {
                for(int i=0;i<4;i++)futures.add(executor.submit(()->{started.countDown();try{
                    for(int j=0;j<512;j++){assertSame(raw,RecordCodecs.current(reader,raw));assertEquals(new P(1),reader.readValue("{\"a\":1}"));}
                }catch(Exception failure){throw new AssertionError(failure);}finally{finished.countDown();}}));
                assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(finished.await(2,java.util.concurrent.TimeUnit.SECONDS),"generation-zero reads must finish while the registry monitor is held");
            }
            for(var future:futures)future.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }finally{executor.shutdownNow();executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);generation.setLong(null,previous);}
    }
    @Test void generationZeroBranchIsOnlyAVolatileReadAndRawReturn()throws Exception {
        assertTrue(java.lang.reflect.Modifier.isVolatile(RecordCodecs.class.getDeclaredField("generation").getModifiers()));
        var node=new org.objectweb.asm.tree.ClassNode();
        try(var in=RecordCodecs.class.getResourceAsStream("RecordCodecs.class")){new org.objectweb.asm.ClassReader(in).accept(node,0);}
        var current=node.methods.stream().filter(m->m.name.equals("current")).findFirst().orElseThrow();
        boolean generationRead=false;
        for(var instruction:current.instructions){
            if(instruction instanceof org.objectweb.asm.tree.FieldInsnNode field&&field.name.equals("generation"))generationRead=true;
            if(instruction.getOpcode()==org.objectweb.asm.Opcodes.ARETURN){assertTrue(generationRead);return;}
            assertFalse(instruction instanceof org.objectweb.asm.tree.MethodInsnNode,"the no-record path must not call helpers or touch a ThreadLocal");
            assertNotEquals(org.objectweb.asm.Opcodes.MONITORENTER,instruction.getOpcode());
        }fail("missing raw return");
    }
    @Test void openMapRootKeepsSnapshotIdentityAcrossUnrelatedRecordReload()throws Exception {
        ObjectMapper mapper=new ObjectMapper();ObjectReader reader=mapper.readerFor(new TypeReference<Map<String,Object>>(){});
        register(reader);Object before=snapshot(reader);flush(mapper);
        assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty());
        assertSame(before,snapshot(reader),"an open non-record root must resolve dynamic values through flushed mapper caches without replacing its root snapshot");
        assertEquals(Map.of("value",List.of(1,2)),reader.readValue("{\"value\":[1,2]}"));
    }
    @Test void failedIteratorRepairIsReportedOnceAndDoesNotThrowBridgeExceptionsOnEveryUse()throws Exception {
        ObjectMapper mapper=new ObjectMapper();var iterator=mapper.readerFor(P.class).<P>readValues("[{\"a\":1},{\"a\":2}]");
        register(iterator);Object original=snapshot(iterator);Field context=iterator.getClass().getDeclaredField("_context");
        Object old=UnsafeAccess.getObject(iterator,context);UnsafeAccess.putObject(iterator,context,null);
        try {
            List<String> failures=RecordCodecs.refresh(List.of(P.class));
            assertEquals(1,failures.stream().filter(message->message.startsWith(iterator.getClass().getName()+":")).count());
            for(int i=0;i<32;i++)assertSame(original,assertDoesNotThrow(()->RecordCodecs.current(iterator,original)),"failed epoch must use raw Jackson behavior without a bridge exception/retry storm");
        }finally{UnsafeAccess.putObject(iterator,context,old);iterator.close();}
    }
    @Test void nonRecordReadsAfterFirstRecordEpochDoNotContendOnGlobalMonitor()throws Exception {
        assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty());
        ObjectMapper mapper=new ObjectMapper();ObjectReader reader=mapper.readerFor(Closed.class);register(reader);Object raw=snapshot(reader);
        var monitor=RecordCodecs.class.getDeclaredField("lock");monitor.setAccessible(true);Object lock=monitor.get(null);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            synchronized(lock){var future=executor.submit(()->{
                for(int i=0;i<512;i++){assertSame(raw,RecordCodecs.current(reader,raw));assertEquals("ok",reader.<Closed>readValue("{\"name\":\"ok\"}").name);}return true;
            });assertTrue(future.get(2,java.util.concurrent.TimeUnit.SECONDS));}
        }finally{executor.shutdownNow();executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);}
    }
    @Test void lateCodecFailureReportsOnceAndSuccessfulRepairResolvesIt()throws Exception {
        assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty());
        var reporter=RecordCodecs.class.getDeclaredField("failureReporter");reporter.setAccessible(true);
        @SuppressWarnings("unchecked") var previous=(java.util.function.BiConsumer<String,String>)reporter.get(null);
        var reports=new ArrayList<String>();var recoveries=new ArrayList<String>();
        ObjectMapper mapper=new ObjectMapper();var iterator=mapper.readerFor(P.class).<P>readValues("[{\"a\":1}]");
        Object raw=snapshot(iterator);Field context=iterator.getClass().getDeclaredField("_context");Object old=UnsafeAccess.getObject(iterator,context);
        try {
            RecordCodecs.reporter((subject,reason)->{if(reason==null)recoveries.add(subject);else reports.add(subject);});
            UnsafeAccess.putObject(iterator,context,null);
            for(int i=0;i<32;i++)assertSame(raw,assertDoesNotThrow(()->RecordCodecs.current(iterator,raw)));
            assertEquals(1,reports.size());assertTrue(recoveries.isEmpty());
            UnsafeAccess.putObject(iterator,context,old);assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty());
            assertEquals(reports,recoveries);assertEquals(new P(1),iterator.nextValue());
        }finally{UnsafeAccess.putObject(iterator,context,old);iterator.close();RecordCodecs.reporter(previous);}
    }
    @Test void refreshIncludesApplicationFailureFromItsJustPublishedGeneration()throws Exception {
        ObjectMapper mapper=new ObjectMapper();var iterator=mapper.readerFor(P.class).<P>readValues("[{\"a\":1}]");register(iterator);
        Object raw=snapshot(iterator);var entryMethod=RecordCodecs.class.getDeclaredMethod("entry",Object.class);entryMethod.setAccessible(true);Object entry=entryMethod.invoke(null,iterator);
        var generation=RecordCodecs.class.getDeclaredField("generation");generation.setAccessible(true);long before=generation.getLong(null);
        Field context=iterator.getClass().getDeclaredField("_context");Object previous=UnsafeAccess.getObject(iterator,context);UnsafeAccess.putObject(iterator,context,null);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();java.util.concurrent.Future<List<String>> future;
        try {
            synchronized(entry){
                future=executor.submit(()->RecordCodecs.refresh(List.of(P.class)));
                long deadline=System.nanoTime()+5_000_000_000L;
                while(generation.getLong(null)==before&&System.nanoTime()<deadline)Thread.sleep(1);
                assertTrue(generation.getLong(null)>before,"refresh must publish its generation");
                assertSame(raw,RecordCodecs.current(iterator,raw));
            }
            var failures=future.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(failures.stream().anyMatch(line->line.startsWith(iterator.getClass().getName()+":")),"repair failure already seen by an application thread must make this native refresh unverified");
        }finally{UnsafeAccess.putObject(iterator,context,previous);executor.shutdownNow();executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);iterator.close();}
    }
    @Test void retainedDerivedReaderAndWriterKeepConfigurationAndReplaceWholeSnapshots() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectReader reader = mapper.readerFor(P.class).with(Locale.FRANCE).withView(Object.class);
        ObjectWriter writer = mapper.writerFor(P.class).with(Locale.FRANCE).withView(Object.class);
        register(reader); register(writer);
        Object rc = reader.getConfig(), wc = writer.getConfig();
        Object oldReader = snapshot(reader), oldWriter = snapshot(writer);
        assertNotNull(oldReader); assertNotNull(oldWriter);
        assertTrue(RecordCodecs.preflight().isEmpty()); flush(mapper);
        assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty());
        assertNotSame(oldReader, snapshot(reader)); assertNotSame(oldWriter, snapshot(writer));
        assertSame(rc, reader.getConfig()); assertSame(wc, writer.getConfig());
        assertEquals(new P(7), reader.readValue("{\"a\":7}"));
        assertEquals("{\"a\":8}", writer.writeValueAsString(new P(8)));
    }
    @Test void heldCodecCachesRebuildEvenWithoutAMapperRegistryEntry() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); ObjectReader reader = mapper.readerFor(P.class); ObjectWriter writer = mapper.writerFor(P.class);
        register(reader); register(writer); Object oldReader = snapshot(reader), oldWriter = snapshot(writer);
        // This test JVM has no constructor instrumentation: no mapper inventory can stand in for held-cache repair.
        assertTrue(JacksonMapperRegistry.live().stream().noneMatch(value -> value == mapper));
        List<RecordCodecs.CacheReset> resets = RecordCodecs.prepareCaches();
        assertFalse(resets.isEmpty()); for (var reset : resets) reset.action().apply();
        assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty());
        assertNotSame(oldReader, snapshot(reader)); assertNotSame(oldWriter, snapshot(writer));
        assertEquals(new P(6), reader.readValue("{\"a\":6}"));
    }
    @Test void staleRawLoadCannotCrossCompletedRefresh() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); ObjectWriter writer = mapper.writerFor(P.class); register(writer);
        Object rawBeforeBoundary = snapshot(writer); flush(mapper);
        assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty());
        Object current = RecordCodecs.current(writer, rawBeforeBoundary);
        assertSame(snapshot(writer), current); assertNotSame(rawBeforeBoundary, current);
    }
    @Test void concurrentRawLoadBeforeRefreshCannotReturnStaleSnapshotAfterBoundary() throws Exception {
        ObjectMapper mapper=new ObjectMapper();ObjectWriter writer=mapper.writerFor(P.class);register(writer);
        var captured=new java.util.concurrent.CountDownLatch(1);var repaired=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(()->{
                Object raw=snapshot(writer);captured.countDown();
                assertTrue(repaired.await(5,java.util.concurrent.TimeUnit.SECONDS));
                Object current=RecordCodecs.current(writer,raw);
                assertSame(snapshot(writer),current);assertNotSame(raw,current);return true;
            });
            assertTrue(captured.await(5,java.util.concurrent.TimeUnit.SECONDS));flush(mapper);
            assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty());repaired.countDown();
            assertTrue(future.get(5,java.util.concurrent.TimeUnit.SECONDS));
        }finally{repaired.countDown();executor.shutdownNow();}
    }
    @Test void genericCollectionsMapsArraysAndNestedBeansInvalidateOnContainedRecord() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JavaType> roots = List.of(mapper.constructType(new TypeReference<List<P>>() {}),
                mapper.constructType(new TypeReference<Map<String,P>>() {}),
                mapper.constructType(P[].class), mapper.constructType(Bean.class));
        List<ObjectWriter> writers = new ArrayList<>(); List<Object> snapshots = new ArrayList<>();
        for (JavaType root : roots) { ObjectWriter writer = mapper.writerFor(root); register(writer); writers.add(writer); snapshots.add(snapshot(writer)); }
        flush(mapper); assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty());
        for (int i = 0; i < writers.size(); i++) assertNotSame(snapshots.get(i), snapshot(writers.get(i)), roots.get(i).toString());
    }
    @Test void closedUnrelatedRootsRetainSnapshotIdentity() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); ObjectWriter writer = mapper.writerFor(Closed.class); register(writer);
        Object old = snapshot(writer); flush(mapper);
        assertTrue(RecordCodecs.refresh(List.of(Other.class)).isEmpty());
        assertSame(old, snapshot(writer)); assertSame(old, RecordCodecs.current(writer, old));
    }
    @Test void iteratorCreatedBeforeRefreshKeepsSameContextAndUsesRebuiltDeserializer() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); MappingIterator<P> iterator = mapper.readerFor(P.class).readValues("{\"a\":9}");
        try (iterator) {
            register(iterator); Object old = snapshot(iterator);
            Field cf = MappingIterator.class.getDeclaredField("_context"); Object context = UnsafeAccess.getObject(iterator, cf);
            flush(mapper); assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty());
            assertNotSame(old, snapshot(iterator)); assertSame(context, UnsafeAccess.getObject(iterator, cf));
            assertEquals(new P(9), iterator.nextValue());
        }
    }
    @Test void preflightCapturesDependenciesOfCodecThatHasNotBeenUsed() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); ObjectWriter writer = mapper.writerFor(Closed.class);
        RecordCodecs.track(writer); Object old = snapshot(writer);
        assertTrue(RecordCodecs.preflight().isEmpty()); flush(mapper);
        assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty()); assertSame(old, snapshot(writer));
    }
    @Test void codecNotSeenBeforeNativeBoundaryRepairsOnFirstGuardedUse() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); ObjectWriter held = mapper.writerFor(P.class); Object old = snapshot(held);
        flush(mapper); assertTrue(RecordCodecs.refresh(List.of(P.class)).isEmpty());
        assertNotSame(old, RecordCodecs.current(held, old));
    }
    @Test void registryDoesNotKeepCodecAlive() throws Exception {
        WeakReference<Object> ref = disposable();
        for (int i = 0; i < 80 && ref.get() != null; i++) { System.gc(); Thread.sleep(25); }
        assertNull(ref.get()); RecordCodecs.liveCount();
    }
    @Test void registryDoesNotKeepRootClassOrDefiningLoaderAlive() throws Exception {
        WeakReference<?>[] references = disposableLoader();
        for (int i = 0; i < 100 && references[0].get() != null; i++) { System.gc(); Thread.sleep(25); }
        assertNull(references[0].get(), "codec registry retains defining loader");
        assertNull(references[1].get(), "codec registry retains root Class");
    }
    private static WeakReference<?>[] disposableLoader() throws Exception {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "com/example/DisposableCodecRoot", null, "java/lang/Object", null);
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "value", "Ljava/lang/String;", null, null).visitEnd(); writer.visitEnd();
        class Loader extends ClassLoader { Class<?> root() { byte[] bytes = writer.toByteArray(); return defineClass(null,bytes,0,bytes.length); } }
        Loader loader = new Loader(); Class<?> root = loader.root(); ObjectMapper mapper = new ObjectMapper();
        ObjectWriter codec = mapper.writerFor(root); register(codec);
        // Jackson's process-wide TypeFactory cache is not the registry under test.
        mapper.getTypeFactory().clearCache();
        return new WeakReference<?>[]{new WeakReference<>(loader), new WeakReference<>(root)};
    }
    private static WeakReference<Object> disposable() {
        ObjectWriter writer = new ObjectMapper().writerFor(P.class); RecordCodecs.track(writer); return new WeakReference<>(writer);
    }
}
