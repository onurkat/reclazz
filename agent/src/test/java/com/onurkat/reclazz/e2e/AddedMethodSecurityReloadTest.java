/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AddedMethodSecurityReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void heldNativeSecurityProxyFollowsPolicyBodyRemovalAndRestore(boolean child) throws Exception {
        var builder=WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()).jvmArgs("-Dprobe.dir="+tmp)
                .with("App",APP).with("Store",store(0)).with("Probe",probe(false));
        if(child)builder.childClassLoader();
        try(var app=builder.start()) {
            app.awaitOrFail("READY=true","native security proxy missing");
            app.awaitOrFail("] Watching ","watcher missing");
            if(child)app.awaitOrFail("APP_IN_CHILD_MODULE=true","child loader missing");
            int[] calls={1,2,4,5,5,6};
            for(int stage=1;stage<=6;stage++) {
                if(stage==1)app.rewriteAll(Map.of("Store",store(stage),"Probe",probe(true)));
                else app.rewrite("Store",store(stage));
                await(app,"Store",stage); if(stage==1)await(app,"Probe",1);
                Files.createFile(tmp.resolve("probe"+stage));
                app.awaitOrFail("R"+stage+"=","security probe failed");
                assertEquals("R"+stage+"="+calls[stage-1]+":true:false",app.latest("R"+stage+"="),app.tail());
                System.out.println("[method-security] child="+child+" "+app.latest("R"+stage+"="));
            }
        }
    }
    private static void await(WatchedApp app,String name,int count) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(reloads(app,name)<count&&System.nanoTime()<until)Thread.sleep(25);
        assertEquals(count,reloads(app,name),app.tail());
    }
    private static long reloads(WatchedApp app,String name) {
        return app.output().stream().filter(s->s.contains("Reloaded app."+name+" ")||s.contains("Structural reload: app."+name+" ")).count();
    }
    private static String probe(boolean added) {
        return "package app; public class Probe { public static String call(Store store,String name) { return "
                +(added?"store.work(name)":"\"ready\"")+"; } }";
    }
    private static String store(int version) {
        String annotation=switch(version) {
            case 1,6 -> "@org.springframework.security.access.prepost.PreAuthorize(\"hasAuthority('WRITE') and #p0 == authentication.name\")";
            case 2 -> "@org.springframework.security.access.prepost.PreAuthorize(\"hasAuthority('ADMIN')\")";
            case 3 -> "@org.springframework.security.access.prepost.PostAuthorize(\"returnObject == 'v3:' + authentication.name\")";
            default -> "";
        };
        String added=version==0||version==5?"":annotation+" public String work(String name) { calls++; return \"v"+version+":\"+name; }";
        return """
                package app;
                public class Store {
                    private int calls;
                    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('WRITE')")
                    public String original() { return "control"; }
                    public int calls() { return calls; }
                    %s
                }
                """.formatted(added);
    }
    private static final String APP="""
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
            import org.springframework.security.core.context.SecurityContextHolder;
            import org.springframework.security.authentication.*;
            import org.springframework.security.access.AccessDeniedException;
            @Configuration(proxyBeanMethods=false)
            @EnableGlobalMethodSecurity(prePostEnabled=true,proxyTargetClass=true)
            public class App {
                @Bean public Store store() { return new Store(); }
                static void login(String role) {
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice","unused",
                        org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(role)));
                }
                static void denied(Store held,String name,Class<? extends Throwable> type) {
                    try { Probe.call(held,name); throw new AssertionError("denied body returned"); }
                    catch(Throwable failure) { if(!type.isInstance(failure))throw new AssertionError(failure); }
                }
                static void allowed(Store held,int stage) {
                    String expected="v"+stage+":alice",actual=Probe.call(held,"alice");
                    if(!expected.equals(actual))throw new AssertionError(actual);
                }
                public static void main(String[] args) throws Exception {
                    try(var context=new AnnotationConfigApplicationContext(App.class)) {
                        Store held=context.getBean(Store.class);
                        System.out.println("READY="+(held instanceof org.springframework.aop.framework.Advised));
                        for(int i=1;i<=6;i++) {
                            while(!Files.exists(Path.of(System.getProperty("probe.dir"),"probe"+i)))Thread.sleep(20);
                            try {
                                if(i==1) {
                                    SecurityContextHolder.clearContext(); denied(held,"alice",AuthenticationCredentialsNotFoundException.class);
                                    login("READ"); denied(held,"alice",AccessDeniedException.class);
                                    login("WRITE"); allowed(held,i); denied(held,"bob",AccessDeniedException.class);
                                } else if(i==2) {
                                    login("WRITE"); denied(held,"alice",AccessDeniedException.class);
                                    login("ADMIN"); allowed(held,i);
                                } else if(i==3) {
                                    login("READ"); denied(held,"bob",AccessDeniedException.class); allowed(held,i);
                                } else if(i==4) {
                                    SecurityContextHolder.clearContext(); allowed(held,i);
                                } else if(i==5) {
                                    denied(held,"alice",IllegalStateException.class);
                                } else {
                                    login("READ"); denied(held,"alice",AccessDeniedException.class);
                                    login("WRITE"); allowed(held,i);
                                }
                                login("WRITE"); if(!"control".equals(held.original()))throw new AssertionError("native control");
                                boolean reflected=java.util.Arrays.stream(Store.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("work"));
                                System.out.println("R"+i+"="+held.calls()+":"+(held==context.getBean(Store.class))+":"+reflected);
                            } finally { SecurityContextHolder.clearContext(); }
                        }
                    }
                }
            }
            """;
}
