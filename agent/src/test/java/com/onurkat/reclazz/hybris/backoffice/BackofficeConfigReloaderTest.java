package com.onurkat.reclazz.hybris.backoffice;

import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Contract-shaped cockpit objects, no installed SAP server or UI reset. */
class BackofficeConfigReloaderTest {
    private URLClassLoader fixture(Path dir) throws Exception {
        var sources = new java.util.LinkedHashMap<String, String>();
        sources.put("com/hybris/cockpitng/core/util/Resettable.java", """
                package com.hybris.cockpitng.core.util;
                public interface Resettable { void reset(); void resetToDefaults(); }
                """);
        sources.put("com/hybris/cockpitng/core/config/CockpitConfigurationService.java", """
                package com.hybris.cockpitng.core.config;
                public interface CockpitConfigurationService { }
                """);
        sources.put("example/Fixture.java", """
                package example;
                import java.util.*;
                import com.hybris.cockpitng.core.config.CockpitConfigurationService;
                import com.hybris.cockpitng.core.util.Resettable;
                public class Fixture {
                    public static class Service implements CockpitConfigurationService, Resettable {
                        public int resets, defaults; public boolean fail;
                        public void reset() { resets++; if (fail) throw new IllegalStateException("fixture reset failure"); }
                        public void resetToDefaults() { defaults++; throw new AssertionError("User customizations must survive"); }
                    }
                    public static class ReadOnly implements CockpitConfigurationService { }
                    public static class Context {
                        public final Map<String,Object> beans = new LinkedHashMap<>();
                        public boolean fail;
                        public String[] getBeanNamesForType(Class<?> type) {
                            if (fail) throw new IllegalStateException("fixture context failure");
                            return beans.entrySet().stream().filter(e -> type.isInstance(e.getValue())).map(Map.Entry::getKey).toArray(String[]::new);
                        }
                        public Object getBean(String name) { return beans.get(name); }
                    }
                }
                """);
        var args = new ArrayList<>(List.of("-proc:none", "-d", dir.resolve("classes").toString()));
        for (var entry : sources.entrySet()) {
            Path file = dir.resolve(entry.getKey()); Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue()); args.add(file.toString());
        }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return new URLClassLoader(new java.net.URL[]{dir.resolve("classes").toUri().toURL()}, getClass().getClassLoader());
    }
    private Object make(URLClassLoader loader, String kind) throws Exception {
        return loader.loadClass("example.Fixture$" + kind).getConstructor().newInstance();
    }
    @SuppressWarnings("unchecked")
    private void add(Object context, String name, Object bean) throws Exception {
        ((java.util.Map<String,Object>) context.getClass().getField("beans").get(context)).put(name, bean);
    }
    private int count(Object bean, String field) throws Exception { return bean.getClass().getField(field).getInt(bean); }

    @Test void everyMatchingResettableResetsOnceAcrossContextsWithoutDefaults(@TempDir Path dir) throws Exception {
        try (var loader = fixture(dir)) {
            Object a = make(loader,"Context"), b = make(loader,"Context");
            Object first = make(loader,"Service"), second = make(loader,"Service"), third = make(loader,"Service");
            add(a,"first",first); add(a,"second",second); add(a,"unrelated",new Object());
            add(b,"third",third); add(b,"notResettable",make(loader,"ReadOnly"));
            assertEquals(3, BackofficeConfigReloader.reload("example-backoffice-config.xml", List.of(a,b)));
            for (Object bean : List.of(first,second,third)) { assertEquals(1,count(bean,"resets")); assertEquals(0,count(bean,"defaults")); }
        }
    }
    @Test void throwingContextAndServiceDoNotBlockLaterHealthyContext(@TempDir Path dir) throws Exception {
        try (var loader = fixture(dir)) {
            Object badContext=make(loader,"Context"),badServiceContext=make(loader,"Context"),healthy=make(loader,"Context");
            badContext.getClass().getField("fail").setBoolean(badContext,true);
            Object broken=make(loader,"Service"),good=make(loader,"Service");broken.getClass().getField("fail").setBoolean(broken,true);
            add(badServiceContext,"broken",broken);add(healthy,"good",good);
            assertEquals(1,BackofficeConfigReloader.reload("example-backoffice-config.xml",List.of(badContext,badServiceContext,healthy)));
            assertEquals(1,count(broken,"resets"));assertEquals(1,count(good,"resets"));
            assertEquals(0,count(broken,"defaults"));assertEquals(0,count(good,"defaults"));
        }
    }
    @Test void zeroServicesReturnsZeroAndDoesNotEmitSuccess(@TempDir Path dir) throws Exception {
        try (var loader = fixture(dir)) {
            Object context=make(loader,"Context");add(context,"notResettable",make(loader,"ReadOnly"));
            var events=new ArrayList<String>();StatusReporter.StatusListener listener=(level,message)->events.add(level+":"+message);
            StatusReporter.addListener(listener);
            try { assertEquals(0,BackofficeConfigReloader.reload("example-backoffice-config.xml",List.of(new Object(),context))); }
            finally { StatusReporter.removeListener(listener); }
            assertTrue(events.stream().noneMatch(e->e.startsWith("OK:")),events.toString());
        }
    }
}
