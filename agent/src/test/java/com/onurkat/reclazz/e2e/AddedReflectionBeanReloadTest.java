/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AddedReflectionBeanReloadTest {
    @TempDir Path tmp;

    @Test void metaReflectionAndWarmJavaBeansFollowAdditionBodyChangeRemovalAndRestore() throws Exception {
        Path framework=java.nio.file.Files.createDirectories(tmp.resolve("framework"));
        Path utility=tmp.resolve("FrameworkProbe.java");
        java.nio.file.Files.writeString(utility,"""
            package org.springframework.example;
            public class FrameworkProbe {
                public static boolean hasLabel(Class<?> type) {
                    return java.util.Arrays.stream(type.getMethods()).anyMatch(m->m.getName().equals("getLabel"));
                }
            }
            """);
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,
                "-source","17","-target","17","-d",framework.toString(),utility.toString()));
        try (var app = WatchedApp.in(tmp).classpath(framework.toString()).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("App", APP).with("Dto", dto(0)).with("Child", "package app; public class Child extends Dto {}")
                .start()) {
            app.awaitOrFail("PORT=", "server did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            assertEquals("absent", get(port), app.tail());
            for (int stage=1;stage<=4;stage++) {
                app.rewrite("Dto", dto(stage));
                String expected = stage==3 ? "absent" : "label"+stage+"|label"+stage+"|label"+stage+"|42|true|static"+stage+"|wrapped|private"+stage;
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
                String actual;
                // A request that lands while the reload publishes members may see them before the JavaBeans
                // flush; only the settled state is the contract, and every request after it must agree.
                do { actual=fetch(port);if(actual.equals(expected))break;Thread.sleep(50); } while(System.nanoTime()<end);
                assertEquals(expected,actual,app.tail());
                assertEquals(expected,get(port),"warm reflection and bean caches must remain current");
            }
        }
    }

    private String get(int port) throws Exception {
        var response=send(port);
        assertEquals(200,response.statusCode(),response.body());return response.body();
    }

    private String fetch(int port) throws Exception {
        var response=send(port);
        return response.statusCode()==200 ? response.body() : response.statusCode()+": "+response.body();
    }

    private HttpResponse<String> send(int port) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }

    private static String dto(int stage) {
        return "package app; public class Dto<T> { public String baseline(){return \"base"+stage+"\";} " +
            (stage==0||stage==3 ? "" : """
                private long count;
                @Deprecated public String getLabel(){return "label%d";}
                public long getCount(){return count;}
                public void setCount(long value){count=value;}
                public boolean isActive(){return true;}
                public static String addedStatic(){return "static%d";}
                private String secret(){return "private%d";}
                public java.util.List<String> getTags(){return java.util.List.of("tag");}
                public T getValue(){return (T) "typed";}
                public String fail() throws java.io.IOException {throw new IllegalStateException("target");}
                """.formatted(stage,stage,stage)) + "}";
    }

    private static final String APP = """
        package app;
        import java.lang.reflect.*;
        import java.beans.*;
        public class App {
            static Method held;
            public static void main(String[] args) throws Exception {
                var dto=new Dto(); var child=new Child();
                // Prime both caches before the first addition. Never flush in the app.
                Introspector.getBeanInfo(Dto.class);Introspector.getBeanInfo(Child.class);
                Dto.class.getMethods();Child.class.getMethods();
                var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/",ex->{
                    String text;int status=200;
                    try {
                        // A native framework scan must not pollute the application's public-method cache.
                        if(org.springframework.example.FrameworkProbe.hasLabel(Dto.class)
                                || org.springframework.example.FrameworkProbe.hasLabel(Child.class))
                            throw new AssertionError("native framework scan saw a virtual callback");
                        // Meta-reflection executes Class itself, including unrewritten JDK consumers.
                        Method[] methods=(Method[])Class.class.getMethod("getDeclaredMethods").invoke(Dto.class);
                        Method label=java.util.Arrays.stream(methods).filter(m->m.getName().equals("getLabel")).findFirst().orElse(null);
                        if(label==null) {
                            boolean stale=java.util.Arrays.stream(Introspector.getBeanInfo(Dto.class).getPropertyDescriptors()).anyMatch(p->p.getName().equals("label"));
                            text=stale?"stale-beans":"absent";
                        } else {
                            if(held==null)held=label;
                            if(!label.isAnnotationPresent(Deprecated.class))throw new AssertionError("annotation metadata");
                            if(label.getDeclaringClass()!=Dto.class || label.getReturnType()!=String.class)throw new AssertionError("method identity");
                            var childProperty=java.util.Arrays.stream(Introspector.getBeanInfo(Child.class).getPropertyDescriptors())
                                    .filter(p->p.getName().equals("label")).findFirst().orElseThrow();
                            if(!childProperty.getReadMethod().invoke(child).equals(label.invoke(dto)))throw new AssertionError("inherited bean cache");
                            var props=Introspector.getBeanInfo(Dto.class).getPropertyDescriptors();
                            var bean=java.util.Arrays.stream(props).filter(p->p.getName().equals("label")).findFirst().orElseThrow();
                            var count=java.util.Arrays.stream(props).filter(p->p.getName().equals("count")).findFirst().orElseThrow();
                            var generic=(java.lang.reflect.ParameterizedType)Dto.class.getMethod("getTags").getGenericReturnType();
                            if(generic.getActualTypeArguments()[0]!=String.class)throw new AssertionError("generic return type");
                            var variable=(java.lang.reflect.TypeVariable<?>)Dto.class.getMethod("getValue").getGenericReturnType();
                            if(variable.getGenericDeclaration()!=Dto.class)throw new AssertionError("class type variable");
                            if(Dto.class.getMethod("fail").getExceptionTypes()[0]!=java.io.IOException.class)throw new AssertionError("declared exceptions");
                            if(count.getWriteMethod().getParameters().length!=1 || count.getWriteMethod().getParameters()[0].getType()!=long.class)
                                throw new AssertionError("parameter metadata");
                            if(label.getAnnotatedReturnType().getType()!=String.class)throw new AssertionError("annotated return type");
                            if(count.getWriteMethod().getAnnotatedParameterTypes()[0].getType()!=long.class)throw new AssertionError("annotated parameter type");
                            if(!java.lang.invoke.MethodHandles.lookup().unreflect(label).invokeWithArguments(dto).equals(label.invoke(dto)))
                                throw new AssertionError("unreflect disagrees with Method.invoke");
                            count.getWriteMethod().invoke(dto,Integer.valueOf(42));
                            try {count.getWriteMethod().invoke(dto,"wrong");throw new AssertionError("wrong argument accepted");}catch(IllegalArgumentException expected){}
                            try {label.invoke(new Object());throw new AssertionError("wrong receiver accepted");}catch(IllegalArgumentException expected){}
                            String wrapped;
                            try {Dto.class.getMethod("fail").invoke(dto);throw new AssertionError("target did not throw");}
                            catch(InvocationTargetException e){if(!(e.getCause() instanceof IllegalStateException))throw e;wrapped="wrapped";}
                            var secret=Dto.class.getDeclaredMethod("secret");secret.setAccessible(true);
                            var untouched=Dto.class.getDeclaredMethod("secret");
                            if(untouched.canAccess(dto))throw new AssertionError("setAccessible leaked to another Method copy");
                            try{untouched.invoke(dto);throw new AssertionError("private access leaked");}catch(IllegalAccessException expected){}
                            if(!Dto.class.getMethod("getLabel",(Class<?>[])null).invoke(dto).equals(label.invoke(dto)))throw new AssertionError("null varargs");
                            text=label.invoke(dto)+"|"+held.invoke(dto)+"|"+Child.class.getMethod("getLabel").invoke(child)+"|"+count.getReadMethod().invoke(dto)+"|"+Dto.class.getMethod("isActive").invoke(dto)+"|"+Dto.class.getMethod("addedStatic").invoke(null)+"|"+wrapped+"|"+secret.invoke(dto);
                            if(!bean.getReadMethod().invoke(dto).equals(label.invoke(dto)))throw new AssertionError("bean invocation");
                            if(java.util.Arrays.stream(methods).anyMatch(m->m.getName().startsWith("__reclazz$")))throw new AssertionError("injected member leak");
                        }
                    }catch(Throwable e){e.printStackTrace();status=500;text=e.toString();}
                    byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);ex.sendResponseHeaders(status,bytes.length);
                    try(var out=ex.getResponseBody()){out.write(bytes);}
                });server.start();System.out.println("PORT="+server.getAddress().getPort());
            }
        }
        """;
}
