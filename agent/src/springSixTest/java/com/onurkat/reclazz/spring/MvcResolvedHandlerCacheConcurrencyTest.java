/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.SpringVersion;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class MvcResolvedHandlerCacheConcurrencyTest {
    @RestController public static class Endpoint {
        final String value;
        Endpoint(String value) { this.value=value; }
        @GetMapping("/probe") public String probe() { return value; }
    }
    public static class PausingFactory extends DefaultListableBeanFactory {
        final CountDownLatch resolved=new CountDownLatch(1), release=new CountDownLatch(1);
        volatile boolean armed;
        @Override public Object getBean(String name) {
            Object bean=super.getBean(name);
            if(armed && name.equals("endpoint")) {
                armed=false; resolved.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("resolution latch timed out"); }
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            }
            return bean;
        }
    }
    @Test void lockedScanWaitsForOldResolutionThenClearsItsStore() throws Exception { race(true); }
    @Test void unlockedNegativeControlRetainsOldResolutionAfterScan() throws Exception { race(false); }

    private void race(boolean locked) throws Exception {
        Assumptions.assumeTrue(SpringVersion.getVersion().startsWith("7."),"Spring 7 resolved handler cache only");
        var factory=new PausingFactory();var pool=Executors.newFixedThreadPool(2);
        try(var context=new GenericWebApplicationContext(factory)) {
            context.setServletContext(new MockServletContext());
            var endpoint=new RootBeanDefinition(Endpoint.class);endpoint.setInstanceSupplier(()->new Endpoint("old"));
            context.registerBeanDefinition("endpoint",endpoint);
            context.registerBeanDefinition("mapping",new RootBeanDefinition(RequestMappingHandlerMapping.class));
            context.refresh();var mapping=context.getBean("mapping",RequestMappingHandlerMapping.class);
            var mvc=MockMvcBuilders.webAppContextSetup(context).build();
            factory.armed=true;
            var request=pool.submit(()->mvc.perform(get("/probe")).andReturn().getResponse().getContentAsString());
            assertTrue(factory.resolved.await(5,TimeUnit.SECONDS),"request reached post-getBean pause");
            factory.destroySingleton("endpoint");factory.registerSingleton("endpoint",new Endpoint("new"));
            var scannerThread=new AtomicReference<Thread>();var scanStarted=new CountDownLatch(1);
            var scan=pool.submit(()->{scannerThread.set(Thread.currentThread());scanStarted.countDown();return MvcResolvedHandlerCache.invalidate(factory,mapping,null,locked);});
            assertTrue(scanStarted.await(5,TimeUnit.SECONDS));
            if(locked) {
                Object registry=Reflect.findField(mapping.getClass(),"mappingRegistry").get(mapping);
                var lock=(ReentrantReadWriteLock)Reflect.findField(registry.getClass(),"readWriteLock").get(registry);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!lock.hasQueuedThread(scannerThread.get()) && !scan.isDone() && System.nanoTime()<until)Thread.sleep(5);
                assertTrue(lock.hasQueuedThread(scannerThread.get()),"scanner queued on exact registry lock held by request");
                assertFalse(scan.isDone());
            } else assertEquals(0,scan.get(5,TimeUnit.SECONDS),"null-cache scan finishes before old store");
            factory.release.countDown();
            assertEquals("old",request.get(5,TimeUnit.SECONDS),"in-flight request may finish on old controller");
            assertEquals(locked?1:0,scan.get(5,TimeUnit.SECONDS));
            assertEquals(locked?"new":"old",mvc.perform(get("/probe")).andReturn().getResponse().getContentAsString(),
                    "negative control must reproduce persistent old routing");
        } finally {factory.release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
