/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.annotation.*;
import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordPreparationLockTest {
    static final CountDownLatch PARSING=new CountDownLatch(1),RELEASE=new CountDownLatch(1);
    enum Deferred {
        VALUE;
        static {
            PARSING.countDown();
            try {if(!RELEASE.await(10,TimeUnit.SECONDS))throw new AssertionError("metadata release deadline");}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
        }
    }
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.RECORD_COMPONENT)
    @interface Annotation {Deferred value();}
    record Gate(@Annotation(Deferred.VALUE) int value) { }
    public static class Caller {
        public Object[] __reclazz$ext;
        public static Object initial(Caller receiver){return "old";}
    }
    @Test void fallibleMetadataParsingDoesNotHoldThePublicationLock()throws Exception {
        var slot=RecordSchema.slot(Gate.class);var baseline=slot.current().members();
        var initial=MethodHandles.lookup().findStatic(Caller.class,"initial",MethodType.methodType(Object.class,Caller.class));
        try(var first=slot.prepare(baseline)){first.stageFieldInitializers(Caller.class,Map.of("added:Ljava/lang/String;",initial));assertTrue(first.commit());}
        var members=new RecordSchema.Members(baseline.components(),baseline.methods(),List.of(Gate.class.getDeclaredField("value")),
                baseline.constructors(),baseline.constructorTargets(),baseline.initializerTargets(),baseline.objectMethods());
        var pool=Executors.newFixedThreadPool(2);Future<?> preparation=null;
        try {
            preparation=pool.submit(()->{try(var pending=slot.prepareVirtual(members)){assertNotNull(pending);}});
            assertTrue(PARSING.await(5,TimeUnit.SECONDS),"real annotation enum parsing reached the pause");
            var started=new CountDownLatch(1);
            var read=pool.submit(()->{started.countDown();return FieldStore.getExtField(new Caller(),Caller.class.getName(),"added","Ljava/lang/String;");});
            assertTrue(started.await(5,TimeUnit.SECONDS));
            try {assertEquals("old",read.get(2,TimeUnit.SECONDS));}
            catch(TimeoutException blocked){throw new AssertionError("fallible metadata parsing holds the caller field publication lock",blocked);}
        } finally {
            RELEASE.countDown();if(preparation!=null)preparation.get(10,TimeUnit.SECONDS);
            pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));
        }
    }
}
