package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationMetadataRepairTest {
    public static class Owner { @Cacheable(cacheNames="items", condition="false") public String read() { return "ok"; } }
    public static class BrokenSource implements CacheOperationSource {
        public final Map<Object,Object> attributeCache = new HashMap<>();
        public Collection<CacheOperation> getCacheOperations(Method m, Class<?> c) { throw new IllegalStateException("synthetic parser failure"); }
    }
    @Test void repairFailureWarns() {
        try(var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("source", BrokenSource.class); context.refresh();
            List<String> warnings = new ArrayList<>();
            StatusReporter.StatusListener listener=(level,message)->{if(level.equals("WARN"))warnings.add(message);};
            StatusReporter.addListener(listener);
            ReloadEffects.begin();
            try { new SpringOperationSourceReloader(new OneContext(context)).reloadOperationSources(Owner.class,true); }
            finally { ReloadEffects.end(); StatusReporter.removeListener(listener); }
            assertTrue(warnings.stream().anyMatch(s->s.contains(Owner.class.getName()) && s.contains("metadata may still be stale")), "failed source parsing must warn instead of silently succeeding");
        }
    }
    @Test void successfulClearNeverClaimsGuaranteedFreshness() {
        try(var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("source", BrokenSource.class); context.refresh();
            context.getBean(BrokenSource.class).attributeCache.put("old","old");
            List<String> events = new ArrayList<>();
            StatusReporter.StatusListener listener=(level,message)->events.add(message);
            StatusReporter.addListener(listener);
            ReloadEffects.begin();
            try { new SpringOperationSourceReloader(new OneContext(context)).reloadOperationSources(Owner.class,true); }
            finally { events.add(ReloadEffects.end()); StatusReporter.removeListener(listener); }
            assertFalse(events.stream().anyMatch(s->s.contains("metadata re-read")), "a cleared map does not prove fresh metadata");
        }
    }
    public static class RemovalOwner { @Cacheable("removed") public String read(){return "ok";} }
    @Test void firstReloadRemovalNeedsPreReloadRelevance() throws Exception {
        var source = new org.springframework.cache.annotation.AnnotationCacheOperationSource();
        assertNotNull(source.getCacheOperations(RemovalOwner.class.getMethod("read"),RemovalOwner.class));
        var inst = net.bytebuddy.agent.ByteBuddyAgent.install();
        var transformer = new java.lang.instrument.ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,
                    java.security.ProtectionDomain domain,byte[] bytes) {
                if(type != RemovalOwner.class) return null;
                var node = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(bytes).accept(node,0);
                for(var method:node.methods) if(method.name.equals("read")) method.visibleAnnotations=null;
                var writer = new org.objectweb.asm.ClassWriter(0); node.accept(writer); return writer.toByteArray();
            }
        };
        inst.addTransformer(transformer,true);
        try {
            inst.retransformClasses(RemovalOwner.class);
            Method fresh = RemovalOwner.class.getMethod("read");
            assertEquals(0,fresh.getDeclaredAnnotations().length,"current annotations cannot establish former relevance");
            assertNotNull(source.getCacheOperations(fresh,RemovalOwner.class),"real Spring still returns the removed annotation's cached operation");
        } finally {
            inst.removeTransformer(transformer); inst.retransformClasses(RemovalOwner.class);
            org.springframework.core.annotation.AnnotationUtils.clearCache();
            assertNotNull(RemovalOwner.class.getMethod("read").getAnnotation(Cacheable.class));
        }
    }
    record OneContext(Object ctx) implements PlatformContext {
        public Platform getPlatformId(){return Platform.GENERIC;} public void initialize(){}
        public Map<String,List<Path>> getClassOutputDirs(){return Map.of();} public Map<String,List<Path>> getSourceDirs(){return Map.of();}
        public Map<String,List<Path>> getResourceDirs(){return Map.of();} public String resolveClasspath(){return "";}
        public String resolveClassName(Path p){return null;} public Path resolveOutputDir(Path p){return null;}
        public Object getApplicationContext(){return ctx instanceof List<?> list?list.get(0):ctx;}
        public List<Object> getAllApplicationContexts(){return ctx instanceof List<?> list?list.stream().map(x->(Object)x).toList():List.of(ctx);}
    }
}
