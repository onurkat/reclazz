/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Full agent/watch/compile path with a synthetic opaque native-shaped module loader. */
class BackofficeModuleWatcherTest {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(booleans={true,false})
    void savedModuleReloadsHeldWidget(boolean autoCompile) throws Exception {
        Path jar=Path.of(com.onurkat.reclazz.e2e.harness.WatchedApp.agentJarOrSkip());
        Path ext=Files.createDirectories(root.resolve("bin/custom/example"));
        Files.createDirectories(root.resolve("bin/platform"));Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/localextensions.xml"),"<hybrisconfig><extensions><extension name='example'/></extensions></hybrisconfig>");
        Files.writeString(ext.resolve("extensioninfo.xml"),"<extensioninfo><extension name='example'><meta key='backoffice-module' value='true'/></extension></extensioninfo>");
        Path output=Files.createDirectories(ext.resolve("backoffice/classes"));
        Path source=ext.resolve("backoffice/src/com/example/Controller.java");Files.createDirectories(source.getParent());
        Files.writeString(source,controller("before",false));compile(output,jar,List.of(source));
        Path fixture=Files.createDirectories(root.resolve("fixture-src"));
        Path loader=fixture.resolve("WidgetClassLoader.java");Files.writeString(loader,LOADER);
        Path app=fixture.resolve("ModuleApp.java");Files.writeString(app,APP);
        Path appClasses=Files.createDirectories(root.resolve("fixture-classes"));compile(appClasses,jar,List.of(loader,app));
        Path log=root.resolve("child.log");
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                "-javaagent:"+jar+"=platform=hybris,hybrisHome="+root+",autoCompile="+autoCompile+",startupDelaySec=0,debounceMs=100,portFile="+root.resolve("port"),
                "-cp",jar+java.io.File.pathSeparator+appClasses,"com.example.ModuleApp",output.toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            await(log,"WIDGET=before");await(log,"] Watching ");
            Files.writeString(source,controller("body",false));if(!autoCompile)compile(output,jar,List.of(source));
            await(log,"WIDGET=body");
            Files.writeString(source,controller("after",true));if(!autoCompile)compile(output,jar,List.of(source));
            await(log,"WIDGET=newafter");
            String receipt=Files.readString(log);
            assertEquals(1,receipt.lines().filter(l->l.startsWith("MODULE_READY ")).count(),receipt);
            assertFalse(receipt.contains("ClassFormatError")||receipt.contains("VerifyError"),receipt);
            assertTrue(Files.isRegularFile(output.resolve("com/example/Controller.class")));
            assertFalse(Files.exists(ext.resolve("classes/com/example/Controller.class")));
        } finally { if(child.isAlive())child.destroyForcibly().waitFor(); }
    }
    private static void compile(Path out,Path jar,List<Path> sources) {
        var args=new ArrayList<>(List.of("-proc:none","-g","-parameters","-cp",jar.toString(),"-d",out.toString()));
        sources.forEach(p->args.add(p.toString()));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
    }
    private static String controller(String value,boolean added) {
        return "package com.example; public class Controller { public String run(){return "
                +(added?"added()+":"")+"\""+value+"\";} "+(added?"public String added(){return \"new\";}":"")+"}";
    }
    private static void await(Path log,String value) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        while(System.nanoTime()<end) { if(Files.exists(log)&&Files.readString(log).contains(value))return;Thread.sleep(25); }
        fail("Missing "+value+": "+Files.readString(log));
    }
    private static final String LOADER="""
        package com.hybris.cockpitng.core.persistence.packaging;
        public class WidgetClassLoader extends ClassLoader {
            private final java.nio.file.Path output;
            public WidgetClassLoader(ClassLoader parent,java.nio.file.Path output){super(parent);this.output=output;}
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException {
                try {return defineClass(name,java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(output.resolve(name.replace('.','/')+".class"))),null);}
                catch(java.io.IOException e){throw new ClassNotFoundException(name,e);}
            }
            @Override public java.io.InputStream getResourceAsStream(String name) {
                try {if(java.nio.file.Files.isRegularFile(output.resolve(name)))return java.nio.file.Files.newInputStream(output.resolve(name));}
                catch(java.io.IOException ignored){}return super.getResourceAsStream(name);
            }
        }
        """;
    private static final String APP="""
        package com.example;
        import com.hybris.cockpitng.core.persistence.packaging.WidgetClassLoader;
        public class ModuleApp {
            public static void main(String[] args)throws Exception {
                var field=com.onurkat.reclazz.agent.ReclazzAgent.class.getDeclaredField("transformContext");field.setAccessible(true);
                while(field.get(null)==null)Thread.sleep(20);
                // The transformer is registered immediately after context population.
                Thread.sleep(500);
                var loader=new WidgetClassLoader(ModuleApp.class.getClassLoader(),java.nio.file.Path.of(args[0]));
                Class<?> type=loader.loadClass("com.example.Controller");Object held=type.getConstructor().newInstance();
                if(((com.onurkat.reclazz.transform.TransformContext)field.get(null)).getMetadata("com/example/Controller")==null)
                    throw new AssertionError("module unprepared");
                System.out.println("MODULE_READY "+ProcessHandle.current().pid());String previous="";
                for(int i=0;i<2400;i++) {
                    String value=(String)type.getMethod("run").invoke(held);
                    if(!value.equals(previous)){System.out.println("WIDGET="+value);previous=value;}
                    Thread.sleep(50);
                }
            }
        }
        """;
}
