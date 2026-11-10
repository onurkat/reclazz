package com.onurkat.reclazz.hybris.backoffice;

import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Contract-shaped native config alias; the owner explicitly authorized F4 reset. */
class BackofficeConfigReloaderTest {
    static URLClassLoader fixture(Path dir) throws Exception {
        var sources = new LinkedHashMap<String,String>();
        sources.put("com/hybris/cockpitng/core/config/CockpitConfigurationService.java", """
            package com.hybris.cockpitng.core.config;
            public interface CockpitConfigurationService { void resetToDefaults(); }
            """);
        sources.put("example/Fixture.java", """
            package example;
            import java.util.*;
            import com.hybris.cockpitng.core.config.CockpitConfigurationService;
            public class Fixture {
                public static final ThreadLocal<Boolean> BOUND = ThreadLocal.withInitial(() -> false);
                public static class Service implements CockpitConfigurationService {
                    public int defaults; public boolean fail, checkTenant;
                    public void reset() { throw new AssertionError("Cache-only reset is not F4"); }
                    public void resetToDefaults() {
                        if (checkTenant) {
                            try {
                                Class<?> fixture=Class.forName("com.onurkat.reclazz.hybris.ProcessTenantFixture");
                                Object tenant=((ThreadLocal<?>)fixture.getField("CURRENT").get(null)).get();
                                Map<?,?> sessions=(Map<?,?>)((ThreadLocal<?>)fixture.getField("SESSIONS").get(null)).get();
                                if(tenant==null || !sessions.containsKey(tenant)) throw new IllegalStateException("no tenant/session");
                            } catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
                        } else if (!BOUND.get()) throw new IllegalStateException("no tenant");
                        if (fail) throw new IllegalStateException("native reset failed");
                        defaults++;
                    }
                }
                public static class Context {
                    public final Map<String,Object> beans=new LinkedHashMap<>();
                    public int lookups;
                    public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
                    public Object getBean(String name) { lookups++; return beans.get(name); }
                    public String getDisplayName() { return "synthetic Backoffice"; }
                }
            }
            """);
        var args=new ArrayList<>(List.of("-proc:none","-d",dir.resolve("classes").toString()));
        for(var entry:sources.entrySet()) {
            Path file=dir.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());args.add(file.toString());
        }
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        return new URLClassLoader(new java.net.URL[]{dir.resolve("classes").toUri().toURL()},BackofficeConfigReloaderTest.class.getClassLoader());
    }
    static Object make(URLClassLoader loader,String type) throws Exception {return loader.loadClass("example.Fixture$"+type).getConstructor().newInstance();}
    @SuppressWarnings("unchecked") static void add(Object ctx,String name,Object bean) throws Exception {((Map<String,Object>)ctx.getClass().getField("beans").get(ctx)).put(name,bean);}
    static int count(Object bean,String name) throws Exception {return bean.getClass().getField(name).getInt(bean);}
    @SuppressWarnings("unchecked") static BackofficeConfigReloader.OwnerScope scope(URLClassLoader loader) throws Exception {
        var bound=(ThreadLocal<Boolean>)loader.loadClass("example.Fixture").getField("BOUND").get(null);
        return (ctx,action)->{boolean before=bound.get();try{bound.set(true);return action.call();}finally{bound.set(before);}};
    }
    static List<String> capture(Runnable work) {
        var messages=new ArrayList<String>();StatusReporter.StatusListener listener=(level,message)->messages.add(level+":"+message);
        StatusReporter.addListener(listener);try{work.run();}finally{StatusReporter.removeListener(listener);}return messages;
    }
    @Test void exactActiveAliasResetsInsideScopeOnceNotUnusedDefinitions(@TempDir Path dir) throws Exception {
        try(var loader=fixture(dir)) {
            Object ctx=make(loader,"Context"), active=make(loader,"Service"), unused=make(loader,"Service");
            add(ctx,"cockpitConfigurationService",active);add(ctx,"unused",unused);var boundary=scope(loader);
            var messages=capture(()->{var result=BackofficeConfigReloader.reload("example-backoffice-config.xml",List.of(ctx,ctx),true,boundary);assertEquals(1,result.services());assertEquals(0,result.failures());});
            assertEquals(1,count(active,"defaults"));assertEquals(0,count(unused,"defaults"));
            assertEquals(1,messages.size());assertTrue(messages.getFirst().startsWith("OK:"));assertTrue(messages.getFirst().contains("as with F4 (1 service)"));assertTrue(messages.getFirst().contains("UI was reset too"));
            assertThrows(Exception.class,()->active.getClass().getMethod("resetToDefaults").invoke(active));
        }
    }
    @Test void failedContextWarnsWithCauseAndDoesNotHideLaterOwner(@TempDir Path dir) throws Exception {
        try(var loader=fixture(dir)) {
            Object bad=make(loader,"Context"),good=make(loader,"Context"),broken=make(loader,"Service"),healthy=make(loader,"Service");
            broken.getClass().getField("fail").setBoolean(broken,true);add(bad,"cockpitConfigurationService",broken);add(good,"cockpitConfigurationService",healthy);var boundary=scope(loader);
            var messages=capture(()->{var result=BackofficeConfigReloader.reload("example.xml",List.of(bad,good),true,boundary);assertEquals(1,result.failures());assertEquals(1,result.services());});
            assertEquals(1,count(healthy,"defaults"));assertTrue(messages.stream().anyMatch(m->m.startsWith("WARN:")&&m.contains("synthetic Backoffice")&&m.contains("IllegalStateException: native reset failed")));
            assertTrue(messages.stream().noneMatch(m->m.contains("No running backoffice")));
        }
    }
    @Test void failedScopeOrNativeCallNeverEmitsSuccess(@TempDir Path dir) throws Exception {
        try(var loader=fixture(dir)) {
            Object ctx=make(loader,"Context"),service=make(loader,"Service");add(ctx,"cockpitConfigurationService",service);
            for(var boundary:List.<BackofficeConfigReloader.OwnerScope>of((c,a)->a.call(),(c,a)->{throw new IllegalStateException("owner unavailable");})) {
                var messages=capture(()->{var result=BackofficeConfigReloader.reload("example.xml",List.of(ctx),true,boundary);assertEquals(1,result.failures());assertEquals(0,result.services());});
                assertEquals(0,count(service,"defaults"));assertEquals(1,messages.size());assertTrue(messages.getFirst().startsWith("WARN:"));
            }
        }
    }
    @Test void optOutDoesNoNativeLookupOrScopeWork(@TempDir Path dir) throws Exception {
        try(var loader=fixture(dir)) {
            Object ctx=make(loader,"Context");
            var messages=capture(()->assertTrue(BackofficeConfigReloader.reload("example.xml",List.of(ctx),false,(c,a)->{throw new AssertionError("disabled scope");}).disabled()));
            assertEquals(0,count(ctx,"lookups"));assertEquals(1,messages.size());assertTrue(messages.getFirst().startsWith("WARN:"));assertTrue(messages.getFirst().contains("saved, not applied; press F4"));
        }
    }
    @Test void noCockpitTypesIsAbsenceButMissingActiveBeanIsFailure(@TempDir Path dir) throws Exception {
        var empty=BackofficeConfigReloader.reload("example.xml",List.of(new Object()),true,(c,a)->{throw new AssertionError();});assertEquals(0,empty.contexts());assertEquals(0,empty.failures());
        try(var loader=fixture(dir)) {
            Object ctx=make(loader,"Context");var boundary=scope(loader);
            var messages=capture(()->assertEquals(1,BackofficeConfigReloader.reload("example.xml",List.of(ctx),true,boundary).failures()));
            assertTrue(messages.getFirst().contains("incompatible type"));assertTrue(messages.stream().noneMatch(m->m.startsWith("OK:")));
        }
    }
}
