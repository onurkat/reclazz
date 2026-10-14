/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Helper metadata relevance gates without bean recreation or cold product discovery. */
class HelperOperationMetadataGateTest {
    public static class Helper { public void original() { } }
    public static class ColdProduct { }
    public static class TxSource implements TransactionAttributeSource {
        final Map<Object,Object> attributeCache = new ConcurrentHashMap<>();
        @Override public TransactionAttribute getTransactionAttribute(Method method, Class<?> targetClass) { return null; }
    }
    @Test void bodyOnlyHelperPreservesMetadataAndGlobalReflectionCache() { exercise(false,false,false,false); }
    @Test void annotationChangedHelperRefreshesMetadata() { exercise(false,true,false,true); }
    @Test void structuralHelperRefreshesMetadata() { exercise(true,false,false,true); }
    @Test void laterBodyOnlyHelperWithAddedMembersRefreshesMetadata() { exercise(false,false,true,true); }

    private void exercise(boolean structural, boolean annotations, boolean addedPreviously, boolean clears) {
        try(var context=new AnnotationConfigApplicationContext()) {
            AtomicInteger cold=new AtomicInteger();
            context.registerBean("txSource",TxSource.class);
            context.registerBean("coldProduct",ColdProduct.class,()->{cold.incrementAndGet();return new ColdProduct();},bd->bd.setLazyInit(true));
            context.refresh();
            var orchestrator=new SpringReloadOrchestrator(new OneContext(context));
            if(addedPreviously)orchestrator.onHelperReloaded(Helper.class.getName(),Helper.class,false,false,true,Set.of(),null);
            TxSource source=context.getBean(TxSource.class);source.attributeCache.put("sentinel","kept");
            Method before=ReflectionUtils.getDeclaredMethods(Helper.class)[0];
            orchestrator.onHelperReloaded(Helper.class.getName(),Helper.class,structural,annotations,false,Set.of(),null);
            assertEquals(clears,source.attributeCache.isEmpty());
            if(!clears)assertSame(before,ReflectionUtils.getDeclaredMethods(Helper.class)[0],"body-only helper must not clear Spring's global reflection cache");
            else assertNotSame(before,ReflectionUtils.getDeclaredMethods(Helper.class)[0],"relevant metadata refresh clears Spring's reflection cache");
            assertEquals(0,cold.get(),"metadata refresh must not create a lazy product");
            assertSame(source,context.getBean(TxSource.class),"source identity must be preserved");
        }
    }
    /** Hands the orchestrator exactly the one context under test. */
    private record OneContext(Object ctx) implements PlatformContext {
        @Override public Platform getPlatformId() { return Platform.GENERIC; }
        @Override public void initialize() { }
        @Override public Map<String, List<Path>> getClassOutputDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getSourceDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
        @Override public String resolveClasspath() { return ""; }
        @Override public String resolveClassName(Path classFile) { return null; }
        @Override public Path resolveOutputDir(Path classFile) { return null; }
        @Override public Object getApplicationContext() { return ctx; }
    }
}
