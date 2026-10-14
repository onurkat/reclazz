/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.File;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LastSecuredMetadataReloadTest {
    @TempDir Path tmp;
    String configuration() {return "@org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity(securedEnabled=true,proxyTargetClass=true)";}
    String marker() {return "";}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void heldOriginalSecuredPolicyMatchesNativeAfterFourSaves(boolean child) throws Exception {
        String cp=WatchedApp.springClasspath();
        var b=WatchedApp.in(tmp).classpath(cp).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .jvmArgs("-Dprobe.dir="+tmp).with("App",appSource()).with("Store",store(0));
        if(child)b.childClassLoader();
        try(var app=b.start()) {
            app.awaitOrFail("READY="+expected(0),"warm native security failed");
            app.awaitOrFail("] Watching ","watcher missing");
            assertEquals(expected(0),nativeFresh(app.classesDir(),cp,0));
            for(int stage=1;stage<=4;stage++) {
                app.rewrite("Store",store(stage));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
                while(reloads(app)<stage&&System.nanoTime()<until)Thread.sleep(25);
                assertEquals(stage,reloads(app),app.tail());
                assertEquals(expected(stage),nativeFresh(app.classesDir(),cp,stage),"fresh agentless native control");
                Files.createFile(tmp.resolve("probe"+stage));
                app.awaitOrFail("R"+stage+"=","held policy observation missing");
                assertEquals("R"+stage+"="+expected(stage),app.latest("R"+stage+"="),app.tail());
                System.out.println("[secured] child="+child+" "+app.latest("R"+stage+"="));
            }
        }
    }
    private String nativeFresh(Path classes,String cp,int stage)throws Exception {
        Path log=tmp.resolve("native-"+stage+".log");
        Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classes+File.pathSeparator+cp,"app.App","native",""+stage).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(p.waitFor(30,TimeUnit.SECONDS),"native timeout");String data=Files.readString(log);assertEquals(0,p.exitValue(),data);
            String actual=data.lines().filter(x->x.startsWith("NATIVE=")).findFirst().orElseThrow().substring(7);
            System.out.println("[native secured] stage="+stage+" "+actual);return actual;
        }finally {if(p.isAlive())p.destroyForcibly();}
    }
    private static long reloads(WatchedApp app){return app.output().stream().filter(x->x.contains("Reloaded app.Store ")||x.contains("Structural reload: app.Store ")).count();}
    private static String expected(int v) {
        if(v==3)return "3:anon=ALLOW/1:user=ALLOW/1:admin=ALLOW/1:control=ok";
        return v+":anon=NOAUTH/0:user="+((v==0||v==4)?"ALLOW/1":"DENY/0")+":admin="+(v==1?"ALLOW/1":"DENY/0")+":control=ok";
    }
    private String store(int v) {
        String role=v==1?"ROLE_ADMIN":v==2?"ROLE_BLOCK":"ROLE_USER";
        String policy=v==3?"":"@org.springframework.security.access.annotation.Secured(\""+role+"\")";
        return "package app; "+marker()+" public class Store { private int calls; public int calls(){return calls;} "
                +"public String control(){return \"ok\";} "
                +policy+" public String work(){calls++;return \"v"+v+"\";} }";
    }
    private String appSource(){return APP.replace("CONFIGURATION",configuration());}
    private static final String APP="""
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.security.core.context.SecurityContextHolder;
            import org.springframework.security.authentication.*;
            import org.springframework.security.access.AccessDeniedException;
            @Configuration(proxyBeanMethods=false) CONFIGURATION
            public class App {
                @Bean public Store store(){return new Store();}
                static void login(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice","unused",org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(role)));}
                static String invoke(Store held,int stage,String role){
                    SecurityContextHolder.clearContext();if(role!=null)login(role);int before=held.calls();String result;
                    try {String value=held.work();if(!value.equals("v"+stage))throw new AssertionError("stale body "+value);result="ALLOW";}
                    catch(AuthenticationCredentialsNotFoundException denied){result="NOAUTH";}
                    catch(AccessDeniedException denied){result="DENY";}
                    return result+"/"+(held.calls()-before);
                }
                static String observe(Store held,int stage){
                    String result=stage+":anon="+invoke(held,stage,null)+":user="+invoke(held,stage,"ROLE_USER")+":admin="+invoke(held,stage,"ROLE_ADMIN");
                    login("ROLE_CONTROL");return result+":control="+held.control();
                }
                public static void main(String[] args)throws Exception {
                    try(var c=new AnnotationConfigApplicationContext(App.class)) {
                        Store held=c.getBean(Store.class);if(!(held instanceof org.springframework.aop.framework.Advised) && !(args.length>0 && args[1].equals("3")))throw new AssertionError("native proxy missing");
                        if(args.length>0){System.out.println("NATIVE="+observe(held,Integer.parseInt(args[1])));return;}
                        System.out.println("READY="+observe(held,0));
                        for(int stage=1;stage<=4;stage++){
                            while(!Files.exists(Path.of(System.getProperty("probe.dir"),"probe"+stage)))Thread.sleep(20);
                            System.out.println("R"+stage+"="+observe(held,stage));
                        }
                    }finally{SecurityContextHolder.clearContext();}
                }
            }
            """;
}
