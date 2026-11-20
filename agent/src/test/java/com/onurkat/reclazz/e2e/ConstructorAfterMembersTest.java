/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual agent JVM witnesses; the observer never changes a redefine payload. */
class ConstructorAfterMembersTest {
    @TempDir Path tmp;

    @Test void methodThenAddedFieldRefreshesNewObjects() throws Exception {
        run("method-then-field", holder(0, "", "", "", 1),
                holder(1, "", "", "public int m(){return 11;}", 1),
                holder(2, "public int b=7;", "", "public int m(){return 11;}", 2),
                "STATE=old1:1|new1:1", "STATE=old2:1:7|new2:2:7");
    }

    @Test void methodThenAddedFieldWithoutObserverStillRefreshesNewObjects() throws Exception {
        run("method-then-field-no-observer", holder(0, "", "", "", 1),
                holder(1, "", "", "public int m(){return 11;}", 1),
                holder(2, "public int b=7;", "", "public int m(){return 11;}", 2),
                "STATE=old1:1|new1:1", "STATE=old2:1:7|new2:2:7");
    }

    @Test void addedFieldThenConstructorOnlyRefreshesNewObjects() throws Exception {
        run("field-then-constructor", holder(0, "", "", "", 1),
                holder(1, "public int b=3;", "", "", 1),
                holder(2, "public int b=3;", "b=9;", "", 2),
                "STATE=old1:1:3|new1:1:3", "STATE=old2:1:3|new2:2:9");
    }

    @Test void removalPayloadAfterMethodAdditionRefreshesNewObjects() throws Exception {
        run("method-then-removal-field", holder(0, "", "", "public int removable(){return 5;}", 1),
                holder(1, "", "", "public int removable(){return 5;} public int m(){return 11;}", 1),
                holder(2, "public int b=7;", "", "public int m(){return 11;}", 2),
                "STATE=old1:1|new1:1", "STATE=old2:1:7|new2:2:7");
    }

    private static String holder(int generation, String fields, String ctor, String methods, int a) {
        return "package app; public class Holder { public int a; " + fields
                + " public Holder(){a=" + a + ";" + ctor + "} " + methods
                + " public String describe(){return \"" + generation + ":\"+a"
                + (fields.isEmpty() ? "" : "+\":\"+b") + ";} }";
    }

    private void run(String name, String baseline, String first, String second,
                     String firstExpected, String target) throws Exception {
        Path evidence = Path.of(Optional.ofNullable(System.getenv("RECLAZZ_R26_EVIDENCE"))
                .orElse(tmp.resolve("evidence").toString())).resolve(name);
        Files.createDirectories(evidence);
        Path probeDir = Files.createDirectories(tmp.resolve("probe"));
        Path probe = observerJar();
        boolean observe=!name.endsWith("-no-observer");
        String[] observerArgs=observe ? new String[]{"-javaagent:"+probe,"-Dr26.probe="+probeDir,"-Dr26.observe=true"} : new String[0];
        try (WatchedApp app = WatchedApp.in(tmp.resolve("app"))
                .classpath(probe.toString()).jvmArgs(observerArgs)
                .agentArgs("startupDelaySec=1,debounceMs=200,verbose=true")
                .with("Holder", baseline).with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                if(Boolean.getBoolean("r26.observe"))research.Probe.install();
                                System.out.println("RUNTIME="+System.getProperty("java.vm.name")+" "+System.getProperty("java.runtime.version")+" "+System.getProperty("java.vendor"));
                                Holder old=new Holder();
                                System.out.println("APP_STARTED");
                                while(true){
                                    try{System.out.println("STATE=old"+old.describe()+"|new"+new Holder().describe());}
                                    catch(Throwable e){System.out.println("STATE_ERROR="+e);}
                                    Thread.sleep(100);
                                }
                            }
                        }
                        """).start()) {
            app.awaitOrFail("STATE=old0:1|new0:1", "baseline objects");
            app.rewrite("Holder", first);
            String observedFirst = afterReceipt(app, 1, "STATE=old1:");
            assertEquals(firstExpected, observedFirst, "first structural save control");
            app.rewrite("Holder", second);
            String observedSecond = afterReceipt(app, 2, "STATE=old2:");
            List<String> summary = new ArrayList<>();
            summary.add("FIRST=" + observedFirst);
            summary.add("SECOND=" + observedSecond);
            summary.add("TARGET=" + target);
            for (String line : app.output()) if (line.contains("Constructor bodies not refreshed")
                    || line.contains("read their default") || isReceipt(line) || line.startsWith("RUNTIME=")
                    || line.toLowerCase(Locale.ROOT).contains("structural hot-reload")) summary.add(line);
            List<Path> captured;
            try (var paths = Files.list(probeDir)) {
                captured = paths.sorted().toList();
            }
            if(observe)assertFalse(captured.isEmpty(), "observer captured no actual redefine");
            assertFalse(Files.exists(probeDir.resolve("probe-error.txt")), "observer failed");
            for (Path p : captured) Files.copy(p, evidence.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            for (Path p : captured) if (p.toString().endsWith(".class")) {
                Set<String> physical = new TreeSet<>(Files.readAllLines(p.resolveSibling(p.getFileName()+".physical")));
                Set<String> payload = shape(Files.readAllBytes(p));
                Set<String> added = new TreeSet<>(payload); added.removeAll(physical);
                Set<String> missing = new TreeSet<>(physical); missing.removeAll(payload);
                summary.add(p.getFileName()+" EXTRA="+added+" MISSING="+missing);
            }
            Files.write(evidence.resolve("summary.txt"), summary);
            System.out.println("R26 " + name + " " + observedSecond);
            assertEquals(target, observedSecond, "new objects must use the latest constructor; evidence at " + evidence);
        }
    }

    private static boolean isReceipt(String line) {
        return line.contains("Reloaded app.Holder") || line.contains("Structural reload: app.Holder");
    }

    private static String afterReceipt(WatchedApp app, int count, String marker) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < end) {
            List<String> lines = app.output(); int receipts=0, last=-1;
            for(int i=0;i<lines.size();i++) if(isReceipt(lines.get(i))){receipts++;last=i;}
            if(receipts>=count) for(int i=last+1;i<lines.size();i++)
                if(lines.get(i).startsWith(marker))return lines.get(i);
            Thread.sleep(100);
        }
        fail("No post-reload observation " + count + ": " + app.tail()); return null;
    }

    private static Set<String> shape(byte[] bytes) {
        Set<String> shape = new TreeSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int a,String n,String d,String s,Object v){shape.add("F "+n+":"+d);return null;}
            @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){if(!n.equals("<clinit>"))shape.add("M "+n+":"+d);return null;}
        }, ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        return shape;
    }

    private Path observerJar() throws Exception {
        Path source=tmp.resolve("observer-src/research/Probe.java");Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package research;
                import java.lang.instrument.*;
                import java.lang.reflect.*;
                import java.lang.invoke.MethodType;
                import java.nio.file.*;
                import java.util.*;
                import java.security.ProtectionDomain;
                public class Probe {
                    private static Instrumentation inst;
                    public static void premain(String args,Instrumentation value){inst=value;}
                    public static void install(){
                        inst.addTransformer(new ClassFileTransformer(){
                            private int serial;
                            public byte[] transform(ClassLoader l,String name,Class<?> type,ProtectionDomain d,byte[] bytes){
                                if(type==null || !name.equals("app/Holder"))return null;
                                Path dir=Path.of(System.getProperty("r26.probe"));
                                try{
                                    Class<?> bridge=Class.forName("com.onurkat.reclazz.bootstrap.ReflectionBridge",true,null);
                                    Field[] fields=(Field[])bridge.getMethod("physicalDeclaredFields",Class.class).invoke(null,type);
                                    Method[] methods=(Method[])bridge.getMethod("physicalDeclaredMethods",Class.class).invoke(null,type);
                                    Constructor<?>[] ctors=(Constructor<?>[])bridge.getMethod("physicalDeclaredConstructors",Class.class).invoke(null,type);
                                    Set<String> shape=new TreeSet<>();
                                    for(Field f:fields)shape.add("F "+f.getName()+":"+f.getType().descriptorString());
                                    for(Method m:methods)shape.add("M "+m.getName()+":"+MethodType.methodType(m.getReturnType(),m.getParameterTypes()).toMethodDescriptorString());
                                    for(Constructor<?> c:ctors)shape.add("M <init>:"+MethodType.methodType(void.class,c.getParameterTypes()).toMethodDescriptorString());
                                    Path file=dir.resolve(String.format("attempt-%02d.class",++serial));
                                    Files.write(file,bytes);Files.write(file.resolveSibling(file.getFileName()+".physical"),shape);
                                }catch(Throwable e){try{Files.writeString(dir.resolve("probe-error.txt"),e.toString());}catch(Exception ignored){}}
                                return null;
                            }
                        },true);
                    }
                }
                """);
        Path classes=Files.createDirectories(tmp.resolve("observer-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,
                "--release","17","-d",classes.toString(),source.toString()), "compile test-only observer");
        Manifest manifest=new Manifest();var attributes=manifest.getMainAttributes();
        attributes.putValue("Manifest-Version","1.0");attributes.putValue("Premain-Class","research.Probe");
        attributes.putValue("Can-Retransform-Classes","true");
        Path jar=tmp.resolve("observer.jar");
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(jar),manifest);var paths=Files.walk(classes)){
            for(Path p:paths.filter(Files::isRegularFile).toList()){
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\','/')));
                out.write(Files.readAllBytes(p));out.closeEntry();
            }
        }
        return jar;
    }
}
