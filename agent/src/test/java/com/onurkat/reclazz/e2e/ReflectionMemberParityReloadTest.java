/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.onurkat.reclazz.e2e.harness.StatusTestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ReflectionMemberParityReloadTest {
    @TempDir Path tmp;

    @org.junit.jupiter.api.Test
    void addedCovariantOverrideDispatchesThroughBothBaseReflectionPaths() throws Exception {
        String base = "package app; public class Base<T> { %s }";
        String sub = "package app; public class Sub extends Base<String> { %s }";
        var saved = new java.util.LinkedHashMap<String,String>();
        saved.put("Base",base.formatted("public T make(){ return null; }"));
        saved.put("Sub",sub.formatted("public String make(){ return \"child\"; }"));
        String probe = """
                package app;
                import java.lang.invoke.MethodHandles;
                public class App {
                    public static void main(String[] args) throws Throwable {
                        Sub held = new Sub();
                        System.out.println("HELD");
                        for (;;) {
                            try {
                                var method = Base.class.getDeclaredMethod("make");
                                System.out.println("RESULT " + method.invoke(held) + "/" +
                                        MethodHandles.lookup().unreflect(method).invoke(held));
                            } catch (NoSuchMethodException absent) { }
                            Thread.sleep(100);
                        }
                    }
                }
                """;
        try (var nativeApp = WatchedApp.in(tmp.resolve("native")).withoutAgent()
                .with("Base",saved.get("Base")).with("Sub",saved.get("Sub")).with("App",probe).start()) {
            nativeApp.awaitOrFail("RESULT child/child","native covariant dispatch");
        }
        try (var live = WatchedApp.in(tmp.resolve("live")).agentArgs("startupDelaySec=1,debounceMs=100")
                .with("Base",base.formatted("")).with("Sub",sub.formatted("")).with("App",probe).start()) {
            live.awaitOrFail("HELD","initialize subclass");
            live.awaitWatcherReady();
            live.rewriteAll(saved);
            assertTrue(live.awaits("RESULT child/child",15),live::tail);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void heldBaseMethodFollowsChangingOverrideDeclarations(boolean nativeVm) throws Exception {
        String sub = "package app; public class Sub extends Base { public int stage(){return %d;} %s }";
        Path portFile = tmp.resolve("agent.port");
        var builder = WatchedApp.in(tmp).agentArgs("startupDelaySec=1,debounceMs=100,portFile=" + portFile)
                .with("Base", "package app; public class Base { public static Object body(Base receiver){return \"base\";}"
                        + (nativeVm ? "public Object read(){return body(this);}" : "") + "}")
                .with("Sub", sub.formatted(0, ""))
                .with("App", """
                        package app;
                        import java.lang.invoke.*;
                        import java.lang.reflect.*;
                        public class App {
                            public static void main(String[] args) throws Throwable {
                                Sub receiver = new Sub();
                                var bridge = Class.forName("com.onurkat.reclazz.bootstrap.MethodForge",true,null);
                                boolean nativeVm = Boolean.getBoolean("probe.native");
                                var handle = MethodHandles.lookup().findStatic(Base.class, "body",
                                        MethodType.methodType(Object.class, Base.class));
                                Method held;
                                if (nativeVm) {
                                    // Enhanced mode uses the real VM method and does not install forged reflection roots.
                                    held = Base.class.getMethod("read");
                                } else {
                                    while (!Boolean.TRUE.equals(bridge.getMethod("isAvailable").invoke(null))) Thread.sleep(50);
                                    var forge = bridge.getMethod("forgeMethod", Class.class, String.class, Class[].class,
                                            Class.class, int.class, MethodHandle.class, byte[].class);
                                    held = (Method) forge.invoke(null, Base.class, "read", new Class<?>[0],
                                            Object.class, Modifier.PUBLIC, handle, null);
                                }
                                MethodHandle virtual = MethodHandles.lookup().unreflect(held);
                                var dispatch = bridge.getDeclaredMethod("invokeVirtual", Method.class, Object.class,
                                        Object[].class, MethodHandle.class);
                                dispatch.setAccessible(true);
                                var physical = Class.forName("com.onurkat.reclazz.bootstrap.ReflectionBridge", true, null)
                                        .getMethod("physicalDeclaredMethods", Class.class);
                                if (!nativeVm) {
                                    var probe = Class.forName("com.onurkat.reclazz.reload.StructuralReloader")
                                            .getDeclaredField("afterSwitchProbe");
                                    probe.setAccessible(true);
                                    probe.set(null, (Runnable) () -> {
                                        int stage = receiver.stage();
                                        if (stage == 2 || stage == 4) {
                                            try {
                                                System.out.println("REMOVAL " + stage + " " + held.invoke(receiver)
                                                        + "/" + virtual.invoke(receiver));
                                            } catch (Throwable failure) {
                                                failure.printStackTrace();
                                            }
                                        }
                                    });
                                }
                                String previous = "";
                                for (;;) {
                                    boolean vmMethod = java.util.Arrays.stream((Method[])physical.invoke(null,Sub.class))
                                            .anyMatch(m -> m.getName().equals("read"));
                                    String value = "DISPATCH " + receiver.stage() + " " + held.invoke(receiver) + "/"
                                            + virtual.invoke(receiver) + "/" + dispatch.invoke(null, held, receiver, new Object[0], handle)
                                            + " physical=" + vmMethod;
                                    if (!value.equals(previous)) {System.out.println(value); previous = value;}
                                    Thread.sleep(25);
                                }
                            }
                        }
                        """);
        if (nativeVm) {
            String java = System.getProperty("reclazz.record.java");
            org.junit.jupiter.api.Assumptions.assumeTrue(java != null && !java.isBlank(), "enhanced JVM required");
            builder.javaExecutable(Path.of(java)).jvmArgs("-XX:+AllowEnhancedClassRedefinition", "-Dprobe.native=true");
        }
        try (var live = builder.start()) {
            live.awaitWatcherReady();
            live.awaitOrFail("DISPATCH 0 base/base/base physical=false", "warm missing override");
            String[] declarations = {"public Object read(){return \"exact\";}", "",
                    "public String read(){return \"covariant\";}", "", "public Object read(){return \"restored\";}"};
            String[] values = {"exact", "base", "covariant", "base", "restored"};
            for (int i = 0; i < declarations.length; i++) {
                live.rewrite("Sub", sub.formatted(i + 1, declarations[i]));
                awaitBuildScan(portFile, "parity-" + (i + 1));
                String value = values[i];
                String expected = "DISPATCH " + (i + 1) + " " + value + "/" + value + "/" + value
                        + " physical=" + (nativeVm && !declarations[i].isEmpty());
                assertTrue(live.awaits(expected, 25), () -> expected + System.lineSeparator() + live.tail());
                if (!nativeVm && declarations[i].isEmpty()) {
                    live.awaitOrFail("REMOVAL " + (i + 1) + " base/base", "base dispatch during removal publication");
                }
            }
        }
    }

    // Exercise dispatch after each build using the same acknowledged scan as an IDE build.
    private static void awaitBuildScan(Path portFile, String token) throws Exception {
        try (var socket = StatusTestClient.connect(portFile)) {
            socket.setSoTimeout(15000);
            var output = socket.getOutputStream();
            output.write(("SCAN request=" + token + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var json = new ObjectMapper();
            String prefix = "SCAN_RESULT " + token + " ";
            String line;
            while ((line = input.readLine()) != null) {
                String message = json.readTree(line).path("message").asText();
                if (message.startsWith(prefix)) {
                    assertEquals("queued", json.readTree(message.substring(prefix.length())).path("status").asText());
                    return;
                }
            }
            fail("Missing scan receipt for " + token);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void logicalMembersKeepAccessMetadataStorageAndConstructionAcrossSaves(boolean childLoader) throws Exception {
        var builder=WatchedApp.in(tmp).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Dto",dto(0)).with("Mark","""
                    package app; import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.PARAMETER,ElementType.TYPE_USE})
                    public @interface Mark {String value();}
                    """).with("Parent","""
                    package app; public class Parent {
                        public static int calls; public final String parentValue;
                        public Parent(String value){calls++;parentValue=value;if(value.equals("parent-fail"))throw new IllegalStateException("parent");}
                    }
                    """).with("Child","package app; public class Child extends Dto<String> {public String added(String x){return \"child:\"+x;} }")
                .with("App",APP);
        if(childLoader)builder.childClassLoader();
        try(var app=builder.start()) {
            app.awaitOrFail("PORT=","server ready");app.awaitWatcherReady();
            int port=Integer.parseInt(app.latest("PORT=").substring(5));
            assertEquals("BASE",get(port));
            for(int stage=1;stage<=4;stage++) {
                app.rewrite("Dto",dto(stage));String expected=stage==3?"REMOVED":"OK:"+stage;
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);String actual;
                do {actual=get(port);if(expected.equals(actual))break;Thread.sleep(100);}while(System.nanoTime()<end);
                assertEquals(expected,actual,app.tail());assertEquals(expected,get(port),"warm copies and held handles must stay valid");
            }
        }
    }
    private static String get(int port)throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString()).body();
    }
    private static String dto(int stage) {
        return "package app; import java.util.*; public class Dto<T> extends Parent { public String kept=\"base\"; public Dto(){super(\"default\");} public int version(){return "+stage+";}"+
            (stage==0 || stage==3?"":"""
                public int added=31; private String hidden="private"; public static int global=9;
                public long wide=40; public boolean flag=true; public char letter='A';
                public final int locked=Integer.valueOf(5); public static final int CONSTANT=19;
                public List<@Mark("field-argument") T> generic;
                public int readAdded(){return added;} public int readLocked(){return locked;}
                public String added(String value){return "body%d:"+value;}
                private String secret(String value){return "secret:"+value;}
                public static String join(String... values){return String.join("/",values);}
                public <U> @Mark("return") List<@Mark("item") U> annotated(@Mark("parameter") String input) throws java.io.@Mark("exception") IOException {return List.of();}
                public String receiver(@Mark("receiver") Dto<T> this){return "receiver";}
                public Dto(@Mark("constructor") String value,int number){super(value);kept="%d:"+value+":"+number;if(number<0)throw new IllegalArgumentException("body");}
                private Dto(String value){this(value,7);}
                public Dto(long value){this("chain"+value,(int)value);}
                """.formatted(stage,stage))+"}";
    }
    private static final String APP="""
        package app;
        import java.lang.reflect.*;import java.lang.invoke.*;import java.util.*;
        public class App {
            static Dto<String> held=new Dto<>();static Child child=new Child();
            static MethodHandle normal,special,construction;static Constructor<?> oldConstructor;
            interface Throwing{void call()throws Throwable;}
            static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
            static void fails(Class<? extends Throwable> expected,Throwing action)throws Throwable{
                try{action.call();}catch(Throwable t){if(expected.isInstance(t))return;throw t;}throw new AssertionError("Expected "+expected.getSimpleName());
            }
            static Object call(Method method,Object receiver,Object...args)throws Throwable{
                try{return method.invoke(receiver,args);}catch(InvocationTargetException t){throw t.getCause();}
            }
            static String probe()throws Throwable{
                int stage=held.version();if(stage==0)return "BASE";
                if(stage==3){
                    fails(NoSuchFieldException.class,()->Dto.class.getDeclaredField("added"));
                    fails(NoSuchMethodException.class,()->Dto.class.getDeclaredMethod("added",String.class));
                    fails(NoSuchMethodException.class,()->Dto.class.getDeclaredConstructor(String.class,int.class));return "REMOVED";
                }
                Field[] roots=(Field[])Class.class.getMethod("getDeclaredFields").invoke(Dto.class);
                check(Arrays.stream(roots).filter(f->f.getName().equals("added")).count()==1,"field roots and no duplicates");
                Field added=Dto.class.getField("added");check(added.getDeclaringClass()==Dto.class,"logical field owner");
                added.set(held,Byte.valueOf((byte)52));check(added.getInt(held)==52,"primitive widening write");
                check((int)Dto.class.getMethod("readAdded").invoke(held)==52,"reflection and application share storage");
                check(added.getLong(held)==52L,"primitive widening read");
                fails(IllegalArgumentException.class,()->added.getByte(held));fails(IllegalArgumentException.class,()->added.set(held,Long.valueOf(2)));
                fails(IllegalArgumentException.class,()->added.set(held,null));fails(NullPointerException.class,()->added.get(null));
                fails(IllegalArgumentException.class,()->added.get(new Object()));
                Field hidden=Dto.class.getDeclaredField("hidden");fails(IllegalAccessException.class,()->hidden.get(held));hidden.setAccessible(true);
                check(hidden.get(held).equals("private"),"private read");
                fails(IllegalAccessException.class,()->Dto.class.getDeclaredField("hidden").get(held));
                Field wide=Dto.class.getField("wide");wide.setInt(held,62);check(wide.getLong(held)==62,"wide write");
                check(Dto.class.getField("flag").getBoolean(held),"boolean read");
                check(Dto.class.getField("letter").getInt(held)==65,"char widening");
                Field locked=Dto.class.getField("locked");fails(IllegalAccessException.class,()->locked.setInt(held,7));locked.setAccessible(true);locked.setInt(held,7);
                check((int)Dto.class.getMethod("readLocked").invoke(held)==7,"overridden instance final");
                MethodHandles.lookup().unreflectSetter(locked).invokeWithArguments(held,8);
                check((int)Dto.class.getMethod("readLocked").invoke(held)==8,"explicit override permits non-trusted final setter handle");
                Field constant=Dto.class.getField("CONSTANT");constant.setAccessible(true);check(constant.getInt(null)==19,"static final initializer");
                fails(IllegalAccessException.class,()->constant.setInt(null,3));fails(IllegalAccessException.class,()->MethodHandles.lookup().unreflectSetter(constant));
                MethodHandle getter=MethodHandles.lookup().unreflectGetter(added),setter=MethodHandles.lookup().unreflectSetter(added);
                setter.invokeWithArguments(held,73);check(getter.invokeWithArguments(held).equals(73),"field handles use same store");
                VarHandle variable=MethodHandles.lookup().unreflectVarHandle(added);
                check((int)variable.get(held)==73,"VarHandle reads shared field storage");
                variable.set(held,74);check(added.getInt(held)==74,"VarHandle writes shared field storage");
                check(variable.compareAndSet(held,74,75),"CAS succeeds on current value");
                check(!variable.compareAndSet(held,74,76) && added.getInt(held)==75,"stale CAS preserves shared value");
                Field global=Dto.class.getField("global");global.setInt(new Object(),12);check(global.getInt(null)==12,"static ignores receiver");
                Method m=Dto.class.getMethod("added",String.class);
                if(normal==null){normal=MethodHandles.lookup().unreflect(m);special=MethodHandles.privateLookupIn(Dto.class,MethodHandles.lookup()).unreflectSpecial(m,Dto.class);}
                check(normal.invokeWithArguments(held,"x").equals("body"+stage+":x"),"held virtual handle follows saves");
                check(normal.invokeWithArguments(child,"x").equals("child:x"),"virtual dispatch respects physical child override");
                check(special.invokeWithArguments(child,"x").equals("body"+stage+":x"),"special dispatch bypasses child override");
                fails(IllegalAccessException.class,()->MethodHandles.publicLookup().unreflectSpecial(m,Dto.class));
                fails(IllegalAccessException.class,()->MethodHandles.lookup().unreflect(Dto.class.getDeclaredMethod("secret",String.class)));
                Method secret=Dto.class.getDeclaredMethod("secret",String.class);secret.setAccessible(true);
                check(MethodHandles.publicLookup().unreflect(secret).invokeWithArguments(held,"x").equals("secret:x"),"explicit reflection override");
                check(MethodHandles.lookup().unreflect(Dto.class.getMethod("join",String[].class)).isVarargsCollector(),"varargs handle");
                Method annotated=Dto.class.getMethod("annotated",String.class);
                check(annotated.getParameters()[0].getDeclaringExecutable().equals(annotated),"parameter belongs to logical method");
                check(annotated.getParameterAnnotations()[0][0].equals(annotated.getParameters()[0].getAnnotation(Mark.class)),"parameter annotation");
                check(annotated.getParameters()[0].getAnnotation(Mark.class).value().equals("parameter"),"parameter annotation value");
                var returned=(AnnotatedParameterizedType)annotated.getAnnotatedReturnType();
                check(returned.getAnnotation(Mark.class).value().equals("return"),"return annotation");
                check(returned.getAnnotatedActualTypeArguments()[0].getAnnotation(Mark.class).value().equals("item"),"nested type annotation");
                check(((TypeVariable<?>)returned.getAnnotatedActualTypeArguments()[0].getType()).getGenericDeclaration().equals(annotated),"logical method type variable");
                check(annotated.getAnnotatedExceptionTypes()[0].getAnnotation(Mark.class).value().equals("exception"),"throws annotation");
                check(Dto.class.getMethod("receiver").getAnnotatedReceiverType().getAnnotation(Mark.class).value().equals("receiver"),"receiver annotation");
                var generic=(AnnotatedParameterizedType)Dto.class.getField("generic").getAnnotatedType();
                check(generic.getAnnotatedActualTypeArguments()[0].getAnnotation(Mark.class).value().equals("field-argument"),"field type annotation");
                check(((TypeVariable<?>)generic.getAnnotatedActualTypeArguments()[0].getType()).getGenericDeclaration()==Dto.class,"logical class type variable");
                Constructor<?> constructor=Dto.class.getConstructor(String.class,int.class);
                if(oldConstructor==null){oldConstructor=constructor;construction=MethodHandles.lookup().unreflectConstructor(constructor);}
                check(constructor.getParameters()[0].getDeclaringExecutable().equals(constructor),"logical constructor parameter");
                check(constructor.getParameters()[0].getAnnotation(Mark.class).value().equals("constructor"),"constructor annotation");
                int before=Parent.calls;Dto<?> made=(Dto<?>)oldConstructor.newInstance("made",42);
                check(Parent.calls==before+1 && made.parentValue.equals("made") && made.kept.equals(stage+":made:42"),"correct parent/body and held constructor: before="+before+" after="+Parent.calls+" parent="+made.parentValue+" kept="+made.kept);
                check(added.getInt(made)==31,"new-object field initializer");
                Dto<?> mh=(Dto<?>)construction.invokeWithArguments("mh",9);check(mh.kept.equals(stage+":mh:9"),"held constructor handle follows saves");
                Constructor<?> privateCtor=Dto.class.getDeclaredConstructor(String.class);fails(IllegalAccessException.class,()->privateCtor.newInstance("private"));
                privateCtor.setAccessible(true);made=(Dto<?>)privateCtor.newInstance("private");check(made.kept.equals(stage+":private:7"),"this delegation to added constructor");
                made=(Dto<?>)Dto.class.getConstructor(long.class).newInstance(8L);check(made.kept.equals(stage+":chain8:8"),"wide delegation argument");
                try{constructor.newInstance("bad",-1);throw new AssertionError("constructor target exception");}catch(InvocationTargetException t){check(t.getCause() instanceof IllegalArgumentException,"body exception wraps exactly once");}
                try{constructor.newInstance("parent-fail",1);throw new AssertionError("superclass target exception");}catch(InvocationTargetException t){check(t.getCause() instanceof IllegalStateException,"parent exception wraps exactly once");}
                fails(IllegalArgumentException.class,()->constructor.newInstance("bad","wrong"));
                return "OK:"+stage;
            }
            public static void main(String[]args)throws Exception{
                Dto.class.getFields();Child.class.getFields();Dto.class.getConstructors();Dto.class.getMethods();
                var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/",ex->{String result;try{result=probe();}catch(Throwable t){t.printStackTrace();result=t.toString();}
                    byte[] body=result.getBytes(java.nio.charset.StandardCharsets.UTF_8);ex.sendResponseHeaders(200,body.length);try(var out=ex.getResponseBody()){out.write(body);}});
                server.start();System.out.println("PORT="+server.getAddress().getPort());
            }
        }
        """;
}
