package com.onurkat.reclazz.hybris.impex;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImpexContextClassLoaderTest {
    @TempDir Path root;
    final class Fixture implements AutoCloseable {
        final ClassLoader caller=Thread.currentThread().getContextClassLoader();
        final List<Object> contexts=ApplicationContextHolder.getAllContexts();
        final List<String> messages=new ArrayList<>();
        final StatusReporter.StatusListener listener=(level,text)->messages.add(level+": "+text);
        final URLClassLoader nativeLoader;final Class<?> context;final Object applicationContext;final Path file;
        Fixture(String mode,boolean nullCaller)throws Exception {
            Map<String,String> sources=new LinkedHashMap<>();
            sources.put("com/example/Provider.java","package com.example; public final class Provider {}");
            sources.put("com/example/Context.java","""
                package com.example;
                public class Context {
                    public static String mode;
                    public static boolean resourceAttempted,providerResolved,imported;
                    public static ClassLoader tenantLoader,resourceLoader,importLoader;
                    public boolean containsBean(String name){return name.equals("importService");}
                    public Object getBean(String name){return new Importer();}
                    public static void provider() throws Exception {
                        Class.forName("com.example.Provider",false,Thread.currentThread().getContextClassLoader());
                        providerResolved=true;
                    }
                    public static class Importer {
                        public Result importData(de.hybris.platform.servicelayer.impex.ImpExResource resource)throws Exception {
                            importLoader=Thread.currentThread().getContextClassLoader();provider();
                            if(mode.equals("invocation"))throw new IllegalStateException("owned invocation failure");
                            imported=true;
                            if(mode.equals("mutate"))Thread.currentThread().setContextClassLoader(new ClassLoader(null){});
                            return new Result();
                        }
                    }
                    public static class Result {
                        public boolean isError(){if(mode.equals("inspection"))throw new IllegalStateException("owned result failure");return mode.equals("refused");}
                        public Object getUnresolvedLines(){return java.util.List.of("owned row");}
                    }
                }
                """);
            sources.put("de/hybris/platform/core/Registry.java","""
                package de.hybris.platform.core;
                public class Registry {
                    public static boolean hasCurrentTenant(){com.example.Context.tenantLoader=Thread.currentThread().getContextClassLoader();return false;}
                    public static void activateMasterTenant(){if(com.example.Context.mode.equals("tenant"))throw new IllegalStateException("owned tenant unavailable");}
                }
                """);
            sources.put("de/hybris/platform/servicelayer/impex/ImpExResource.java","package de.hybris.platform.servicelayer.impex; public interface ImpExResource {}");
            sources.put("de/hybris/platform/servicelayer/impex/impl/StreamBasedImpExResource.java","""
                package de.hybris.platform.servicelayer.impex.impl;
                public class StreamBasedImpExResource implements de.hybris.platform.servicelayer.impex.ImpExResource {
                    public StreamBasedImpExResource(java.io.InputStream in,String encoding)throws Exception {
                        com.example.Context.resourceAttempted=true;
                        com.example.Context.resourceLoader=Thread.currentThread().getContextClassLoader();
                        com.example.Context.provider();
                        if(com.example.Context.mode.equals("constructor"))throw new IllegalStateException("owned constructor failure");
                    }
                }
                """);
            List<String> args=new ArrayList<>(List.of("-proc:none","-d",root.toString()));
            for(var entry:sources.entrySet()){Path source=root.resolve(entry.getKey());Files.createDirectories(source.getParent());Files.writeString(source,entry.getValue());args.add(source.toString());}
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),"native fixture must compile");
            nativeLoader=new URLClassLoader(new java.net.URL[]{root.toUri().toURL()},null) {
                @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                    if(mode.equals("missing-class")&&name.equals("de.hybris.platform.servicelayer.impex.impl.StreamBasedImpExResource"))throw new ClassNotFoundException("owned missing native class");
                    return super.loadClass(name,resolve);
                }
            };
            context=nativeLoader.loadClass("com.example.Context");context.getField("mode").set(null,mode);
            file=Files.writeString(root.resolve("owned.impex"),"UPDATE Title;code[unique=true]\n;owned\n");
            applicationContext=context.getConstructor().newInstance();ApplicationContextHolder.clear();ApplicationContextHolder.register(applicationContext);StatusReporter.addListener(listener);
            Thread.currentThread().setContextClassLoader(nullCaller?null:new ClassLoader(null){});
        }
        Object field(String name)throws Exception{return context.getField(name).get(null);}
        void invoke(){new ImpexAutoImporter().importFile(file,null);}
        void restored(ClassLoader original){assertSame(original,Thread.currentThread().getContextClassLoader(),messages.toString());}
        boolean message(String text){return messages.stream().anyMatch(m->m.contains(text));}
        @Override public void close()throws Exception {Thread.currentThread().setContextClassLoader(caller);StatusReporter.removeListener(listener);ApplicationContextHolder.clear();for(Object previous:contexts)ApplicationContextHolder.register(previous);nativeLoader.close();}
    }
    @Test void nativeProviderLoadsDuringResourceAndImportAndCallerIsRestored()throws Exception {
        try(var f=new Fixture("success",false)) {
            ClassLoader original=Thread.currentThread().getContextClassLoader();
            assertThrows(ClassNotFoundException.class,()->Class.forName("com.example.Provider",false,original));
            f.invoke();
            assertTrue(f.message("OK: ImpEx imported: owned.impex"),"native provider was not loaded: "+f.messages);
            assertEquals(true,f.field("resourceAttempted"));assertEquals(true,f.field("providerResolved"));assertEquals(true,f.field("imported"));
            for(String field:List.of("tenantLoader","resourceLoader","importLoader"))assertSame(f.nativeLoader,f.field(field),field);
            f.restored(original);
        }
    }
    @Test void nullOriginalLoaderIsRestored()throws Exception {try(var f=new Fixture("success",true)){f.invoke();assertTrue(f.message("OK: ImpEx imported"),f.messages.toString());f.restored(null);}}
    @Test void importChangingTcclStillRestoresExactOriginal()throws Exception {try(var f=new Fixture("mutate",false)){var original=Thread.currentThread().getContextClassLoader();f.invoke();assertEquals(true,f.field("imported"));assertTrue(f.message("OK: ImpEx imported"));f.restored(original);}}
    private void failure(String mode,String message)throws Exception {try(var f=new Fixture(mode,false)){var original=Thread.currentThread().getContextClassLoader();f.invoke();assertTrue(f.message(message),f.messages.toString());assertFalse(f.message("OK: ImpEx imported"));f.restored(original);}}
    @Test void constructorFailureRestoresCaller()throws Exception {failure("constructor","owned constructor failure");}
    @Test void importInvocationFailureRestoresCaller()throws Exception {failure("invocation","owned invocation failure");}
    @Test void resultInspectionFailureRestoresCaller()throws Exception {failure("inspection","owned result failure");}
    @Test void refusedImportRestoresCaller()throws Exception {failure("refused","ImpEx import had errors: 1 line");}
    @Test void tenantUnavailableReturnRestoresCaller()throws Exception {failure("tenant","platform tenant unavailable");}
    @Test void nativeClassNotFoundCatchRestoresCaller()throws Exception {failure("missing-class","Hybris import classes not available");}
}
