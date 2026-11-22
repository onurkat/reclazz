/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import com.onurkat.reclazz.e2e.harness.JavaLauncher;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Shared real HTTP/config/retained-codec assertion, with framework versions supplied by each isolated graph. */
public final class RecordNativeContainerAcceptance {
    private RecordNativeContainerAcceptance() { }
    private record Edit(String before,String after,String oldJson,String newJson,String oldArgs,String newArgs) { }
    private static final Map<String,Edit> edits=Map.of(
        "add",new Edit("int a","int a,String b","{\"a\":1}","{\"a\":2,\"b\":\"new\"}","1","2,\"new\""),
        "remove",new Edit("int a,String b","int a","{\"a\":1,\"b\":\"old\"}","{\"a\":2}","1,\"old\"","2"),
        "retype",new Edit("int a","String a","{\"a\":1}","{\"a\":\"new\"}","1","\"new\""),
        "reorder",new Edit("int a,String b","String b,int a","{\"a\":1,\"b\":\"old\"}","{\"a\":2,\"b\":\"new\"}","1,\"old\"","\"new\",2"),
        "generic",new Edit("java.util.List<java.lang.String> a","java.util.List<java.lang.Integer> a","{\"a\":[\"old\"]}","{\"a\":[2]}","java.util.List.of(\"old\")","java.util.List.of(2)"),
        "annotations",new Edit("@app.ComponentTag(\"before\") int a","@app.ComponentTag(\"after\") @com.fasterxml.jackson.annotation.JsonIgnore int a","{\"a\":1}","{}","1","0"));
    public static void run(Path dir,String shape) throws Exception { run(dir,shape,false); }
    public static void run(Path dir,String shape,boolean configFailure) throws Exception {run(dir,shape,configFailure,false);}
    public static void runStock(Path dir,String shape,boolean configFailure) throws Exception {run(dir,shape,configFailure,true);}
    private static void run(Path dir,String shape,boolean configFailure,boolean stock) throws Exception {
        String java=System.getProperty(stock?"reclazz.record.stock.java":"reclazz.record.java");
        assumeTrue(java!=null&&!java.isBlank(),"configure "+(stock?"stock":"enhanced")+" record child");java=JavaLauncher.resolve(Path.of(java)).toString();assertTrue(Files.isExecutable(Path.of(java)));
        Class<?> boot=Class.forName("org.springframework.boot.SpringBootVersion");String version=(String)boot.getMethod("getVersion").invoke(null);
        boolean jackson3;
        try{Class.forName("tools.jackson.databind.ObjectMapper");jackson3=true;}catch(ClassNotFoundException absent){jackson3=false;}
        if(version.startsWith("3."))assertFalse(jackson3,"Boot3 native acceptance must not acquire Jackson3 sidecars");
        if(version.startsWith("4."))assertTrue(jackson3,"Boot4 acceptance needs its actual Jackson3 converter graph");
        Edit edit=edits.get(shape);String app=APP.replace("OLDJSON",literal(edit.oldJson)).replace("NEWJSON",literal(edit.newJson))
                .replace("EXPECTEDCOMPONENTS",literal(shape.equals("generic")?"List a":shape.equals("annotations")?"int a":edit.after))
                .replace("EXPECTEDGENERIC",literal(shape.equals("generic")?"java.util.List<java.lang.Integer>":""))
                .replace("EXPECTEDTAG",literal(shape.equals("annotations")?"after":"")).replace("EXPECTJACKSON3",Boolean.toString(jackson3)).replace("FAULT_CONFIG",Boolean.toString(configFailure))
                .replace("STOCK_ROUTE",Boolean.toString(stock)).replace("CONSTRUCTOR_PROOF",stock?"recordLogicalConstructorBodiesUnchanged":"constructorBodiesUnchanged");
        var builder=WatchedApp.in(dir).javaExecutable(Path.of(java)).classpath(frameworkClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=250,verbose=true,autoCompile=false,portFile="+dir.resolve("agent.port"))
                .jvmArgs("-Dprobe.dir="+dir)
                .with("App",WRAPPER).with("ContainerRunner",app)
                .with("ComponentTag","package app;@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @java.lang.annotation.Target(java.lang.annotation.ElementType.RECORD_COMPONENT) public @interface ComponentTag{String value();}")
                .with("P",record(edit.before,version)).with("Caller",caller(edit.oldArgs,0));
        if(!stock)builder.jvmArgs("-XX:+AllowEnhancedClassRedefinition");
        if(stock&&Boolean.getBoolean("reclazz.record.unsafe.deny"))builder.jvmArgs("--sun-misc-unsafe-memory-access=deny","-Dreclazz.record.unsafe.deny=true");
        WatchedApp child=builder.start();
        try(child) {
            child.awaitOrFail("] Watching 1 director","watcher before Boot loads record");Files.writeString(dir.resolve("load"),"go");
            child.awaitOrFail("READY|"+version+"|"+jackson3,"real Boot and converter graph ready");
            assertFalse(child.output().stream().anyMatch(l->l.contains("CHECK|")&&l.contains("|ERROR|")),child.tail());
            command(dir,"RELOAD pause request=paused","RELOAD_STATE paused pause ");
            Files.writeString(dir.resolve("properties"),"go");child.awaitOrFail("PROPERTIES_READY","new environment ready before rebind");
            child.rewriteAll(Map.of("P",record(edit.after,version),"Caller",caller(edit.newArgs,1)));
            command(dir,"SCAN\nRELOAD status request=scanned","RELOAD_STATE scanned status ");
            command(dir,"RELOAD resume request=resumed","RELOAD_STATE resumed resume ");
            receipt(dir,child,"P",configFailure?"unverified":"applied");receipt(dir,child,"Caller",configFailure?"unverified":"applied");Files.writeString(dir.resolve("observe"),"go");
            if(configFailure) {
                child.awaitOrFail("FAULT_DONE|","post-native config failure observations");
                assertTrue(child.output().contains("CONFIG_REPAIR_FAULT_ARMED"),child.tail());
                assertTrue(child.output().contains("CHECK|nativeCommitted|OK|true"),child.tail());
                assertTrue(child.output().contains("CHECK|configurationFailureLedger|OK|true"),child.tail());
                assertTrue(child.output().contains("CHECK|constructorReceipt|OK|true"),child.tail());
                assertTrue(child.output().contains("CHECK|failedBindPreservesConfigAndLifecycle|OK|true"),child.tail());
                assertFalse(child.output().stream().anyMatch(l->l.startsWith("CHECK|")&&l.contains("|ERROR|")),child.tail());
                assertFalse(child.output().stream().anyMatch(l->l.contains("Reloaded app.P ")||l.contains("Structural reload: app.P ")),child.tail());
                return;
            }
            child.awaitOrFail("DONE|","HTTP/config/all retained root witnesses finished");
            List<String> checks=child.output().stream().filter(l->l.startsWith("CHECK|")).toList();
            assertTrue(checks.size()>=(jackson3?47:28),"missing acceptance checks: "+checks.size()+"\n"+child.tail());
            assertFalse(checks.stream().anyMatch(l->l.contains("|ERROR|")),String.join("\n",checks.stream().filter(l->l.contains("|ERROR|")).toList()));
            assertTrue(checks.contains("CHECK|holderCurrent|OK|true"),child.tail());
            assertTrue(checks.contains("CHECK|originalHolderRetained|OK|true"),child.tail());
            assertTrue(checks.contains("CHECK|constructorReceipt|OK|true"),child.tail());
            assertTrue(checks.contains("CHECK|ordinaryConfigPrepare|OK|declined"),child.tail());
            assertFalse(child.output().stream().anyMatch(l->l.contains("Constructor bodies not refreshed for app.P")),child.tail());
        } finally {
            Path output=stock?Path.of(System.getProperty("reclazz.record.evidenceDir",Path.of("build","record-stock-evidence").toString()),"boot-"+version+"-"+shape+(configFailure?"-config-failure":""))
                    :Path.of("build","record-native-evidence","boot-"+version+"-"+shape+(configFailure?"-config-failure":""));
            Files.createDirectories(output);Files.write(output.resolve("child.log"),child.output());
        }
    }
    private static String literal(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String record(String components,String version) {
        String fields=String.join(",",Arrays.stream(components.split(",")).map(c->"@com.fasterxml.jackson.annotation.JsonView(Object.class) "+c).toList());
        return "package app;@org.springframework.boot.context.properties.ConfigurationProperties(\"p\") "+(version.startsWith("2.")?"@org.springframework.boot.context.properties.ConstructorBinding ":"")+"public record P("+fields+"){}";
    }
    private static String caller(String args,int version){return "package app;public class Caller {public static int version(){return "+version+";}public static Object make(){return new P("+args+");}}";}
    private static String frameworkClasspath(){
        List<String> jars=new ArrayList<>();
        for(String entry:System.getProperty("java.class.path").split(File.pathSeparator)) {
            String name=Path.of(entry).getFileName().toString();
            if(name.endsWith(".jar")&&(name.startsWith("spring-")||name.startsWith("jackson-")||name.startsWith("tomcat-")
                    ||name.startsWith("commons-logging-")||name.startsWith("micrometer-")||name.startsWith("jspecify-")||name.startsWith("jakarta.")||name.startsWith("javax.")
                    ||name.startsWith("snakeyaml-")||name.startsWith("jboss-logging-")||name.startsWith("hibernate-validator-")
                    ||name.startsWith("classmate-")||name.startsWith("expressly-")||name.startsWith("el-api-")||name.startsWith("validation-api-")))jars.add(entry);
        }
        return String.join(File.pathSeparator,jars);
    }
    private static String command(Path dir,String command,String prefix)throws Exception {
        ObjectMapper json=new ObjectMapper();try(Socket socket=new Socket("127.0.0.1",Integer.parseInt(Files.readString(dir.resolve("agent.port")).trim()))) {
            socket.setSoTimeout(15000);var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            socket.getOutputStream().write((command+"\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            for(;;){String line=input.readLine();assertNotNull(line);String message=json.readTree(line).path("message").asText();if(message.startsWith(prefix))return message.substring(prefix.length());}
        }
    }
    private static void receipt(Path dir,WatchedApp app,String type,String expected)throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(app.classesDir().resolve("app/"+type+".class"))));
        ObjectMapper json=new ObjectMapper();JsonNode result=null;long end=System.nanoTime()+30_000_000_000L;
        while(System.nanoTime()<end){String token="verify"+System.nanoTime();result=json.readTree(command(dir,"VERIFY "+token+" app."+type+" "+hash,"VERIFY_RESULT "+token+" "));
            if(List.of("applied","failed","unverified").contains(result.path("status").asText())){assertEquals(expected,result.path("status").asText(),result+"\n"+app.tail());assertEquals(hash,result.path("observedSha256").asText());return;}Thread.sleep(50);}
        fail("No terminal record container receipt: "+result+"\n"+app.tail());
    }
    private static final String WRAPPER="""
        package app;public class App {public static void main(String[] args)throws Exception {
        java.nio.file.Path dir=java.nio.file.Path.of(System.getProperty("probe.dir"));while(!java.nio.file.Files.exists(dir.resolve("load")))Thread.sleep(20);
        Class.forName("app.ContainerRunner").getMethod("main",String[].class).invoke(null,(Object)args);}}
        """;
    private static final String APP="""
        package app;
        import java.nio.file.*;import java.lang.reflect.*;import java.util.*;import java.net.*;import java.net.http.*;
        import org.springframework.boot.*;import org.springframework.boot.context.properties.*;import org.springframework.context.annotation.*;
        import org.springframework.web.bind.annotation.*;import org.springframework.web.servlet.config.annotation.EnableWebMvc;
        import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;import org.springframework.web.servlet.DispatcherServlet;
        public class ContainerRunner {
          interface Action{Object run()throws Throwable;}static int count;
          static void check(String label,Action action){count++;try{System.out.println("CHECK|"+label+"|OK|"+action.run());}catch(Throwable e){while(e instanceof InvocationTargetException&&e.getCause()!=null)e=e.getCause();System.out.println("CHECK|"+label+"|ERROR|"+e);}System.out.flush();}
          public static class Holder implements org.springframework.context.SmartLifecycle,org.springframework.beans.factory.DisposableBean {
            public final P config;public volatile boolean running;public int destroyed;public Holder(P p){config=p;}
            public void start(){running=true;}public void stop(){running=false;}public void stop(Runnable callback){stop();callback.run();}
            public boolean isRunning(){return running;}public boolean isAutoStartup(){return true;}public int getPhase(){return 0;}
            public void destroy(){destroyed++;running=false;}
          }
          public static class Nested{public P value;}
          @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(P.class)
          public static class BootConfig{@Bean Holder holder(P p){return new Holder(p);}}
          @Configuration(proxyBeanMethods=false) @EnableWebMvc
          public static class WebConfig{@Bean Endpoint endpoint(Holder h){return new Endpoint(h);}}
          @RestController public static class Endpoint{public final Holder holder;Endpoint(Holder h){holder=h;}
            @GetMapping("/config")public Object config(){return holder.config;}@PostMapping("/body")public Object body(@RequestBody P p){return p;}
            @GetMapping("/model")public Object model(@ModelAttribute P p){return p;}}
          static Object call(Object target,String method,Class<?> signature,Object value)throws Exception{return target.getClass().getMethod(method,signature).invoke(target,value);}
          static Object javaType(Object mapper,java.lang.reflect.Type type)throws Exception{return call(mapper,"constructType",java.lang.reflect.Type.class,type);}
          static Object typed(Object mapper,String method,Object type)throws Exception{for(Method m:mapper.getClass().getMethods())if(m.getName().equals(method)&&m.getParameterCount()==1&&m.getParameterTypes()[0].getName().endsWith(".JavaType"))return m.invoke(mapper,type);throw new NoSuchMethodException(method);}
          static java.lang.reflect.Type parameterized(Class<?> raw,java.lang.reflect.Type... args){return new ParameterizedType(){public java.lang.reflect.Type[]getActualTypeArguments(){return args;}public java.lang.reflect.Type getRawType(){return raw;}public java.lang.reflect.Type getOwnerType(){return null;}};}
          static Object parse(Object mapper,String json)throws Exception{return mapper.getClass().getMethod("readValue",String.class,Class.class).invoke(mapper,json,Object.class);}
          static Object read(Object reader,String json)throws Exception{return call(reader,"readValue",String.class,json);}
          static String write(Object writer,Object value)throws Exception{return(String)call(writer,"writeValueAsString",Object.class,value);}
          static Object config(Object codec)throws Exception{return codec.getClass().getMethod("getConfig").invoke(codec);}
          static String request(int port,String path,String json)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(5));if(json!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json));else b.GET();var response=HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalStateException("HTTP "+response.statusCode()+" "+response.body().substring(0,Math.min(180,response.body().length())));return response.body();}
          static String query(Map<String,Object> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(k+"="+URLEncoder.encode(v instanceof Collection<?> values?String.join(",",values.stream().map(Object::toString).toList()):v.toString(),java.nio.charset.StandardCharsets.UTF_8)));return String.join("&",parts);}
          static Map<String,Object> components(Object record)throws Exception{Map<String,Object> values=new LinkedHashMap<>();for(var component:record.getClass().getRecordComponents())values.put(component.getName(),component.getAccessor().invoke(record));return values;}
          static Object agent(String type,String method,Class<?>[] signature,Object... args)throws Exception{Method m=Class.forName(type).getDeclaredMethod(method,signature);m.setAccessible(true);return m.invoke(null,args);}
          static void same(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(actual+" != "+expected);}
          public static void main(String[]args)throws Exception {
            Path dir=Path.of(System.getProperty("probe.dir"));String oldJson=OLDJSON,newJson=NEWJSON;
            if(STOCK_ROUTE)check("stockRuntime",()->{
              var arguments=java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
              if(arguments.stream().anyMatch(a->a.contains("AllowEnhancedClassRedefinition")))throw new AssertionError("stock route used enhanced redefinition flag");
              if(Boolean.getBoolean("reclazz.record.unsafe.deny")&&!arguments.contains("--sun-misc-unsafe-memory-access=deny"))throw new AssertionError("missing actual deny JVM flag");
              return "runtime="+Runtime.version()+" unsafeDeny="+Boolean.getBoolean("reclazz.record.unsafe.deny");
            });
            var legacy=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());Map<String,Object> properties=new LinkedHashMap<>();
            ((Map<String,Object>)parse(legacy,oldJson)).forEach((k,v)->properties.put("p."+k,v));
            var boot=new SpringApplication(BootConfig.class);boot.setWebApplicationType(WebApplicationType.NONE);
            properties.put("spring.main.banner-mode","off");properties.put("logging.level.root","WARN");boot.setDefaultProperties(properties);var root=boot.run();
            // The live environment source is mutable and owned by this fixture.
            root.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("recordAcceptance",properties));
            P originalConfig=root.getBean(P.class);Holder originalHolder=root.getBean(Holder.class);Caller.version();
            byte[] originalPhysical=Files.readAllBytes(Path.of(P.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("app/P.class"));
            Map<String,Class<?>> originalTypes=new LinkedHashMap<>();Map<String,Object> originalValues=new LinkedHashMap<>();
            for(RecordComponent component:P.class.getRecordComponents()){originalTypes.put(component.getName(),component.getType());originalValues.put(component.getName(),component.getAccessor().invoke(originalConfig));}
            var tomcat=new org.apache.catalina.startup.Tomcat();tomcat.setBaseDir(Files.createDirectories(dir.resolve("tomcat")).toString());tomcat.setPort(0);tomcat.getConnector().setProperty("address","127.0.0.1");
            var context=tomcat.addContext("",dir.toString());context.setParentClassLoader(ContainerRunner.class.getClassLoader());
            var web=new AnnotationConfigWebApplicationContext();web.setParent(root);web.setClassLoader(ContainerRunner.class.getClassLoader());web.register(WebConfig.class);
            var servlet=org.apache.catalina.startup.Tomcat.addServlet(context,"mvc",new DispatcherServlet(web));servlet.setLoadOnStartup(1);context.addServletMappingDecoded("/","mvc");tomcat.start();int port=tomcat.getConnector().getLocalPort();
            List<Object> mappers=new ArrayList<>();mappers.add(legacy);boolean modern;
            try{Class<?> mapper=Class.forName("tools.jackson.databind.json.JsonMapper");Object builder=mapper.getMethod("builder").invoke(null);mappers.add(builder.getClass().getMethod("build").invoke(builder));modern=true;}catch(ClassNotFoundException absent){modern=false;}
            if(modern!=EXPECTJACKSON3)throw new AssertionError("wrong Jackson graph");
            java.lang.reflect.Type[] types={P.class,parameterized(List.class,P.class),parameterized(Map.class,String.class,P.class),parameterized(Optional.class,P.class),P[].class,Nested.class};
            String[] labels={"direct","list","map","optional","array","nested"};
            String[] oldRoots={oldJson,"["+oldJson+"]","{\\"p\\":"+oldJson+"}",oldJson,"["+oldJson+"]","{\\"value\\":"+oldJson+"}"};
            String[] newRoots={newJson,"["+newJson+"]","{\\"p\\":"+newJson+"}",newJson,"["+newJson+"]","{\\"value\\":"+newJson+"}"};
            List<Object[]> codecs=new ArrayList<>();List<Object[]> derived=new ArrayList<>();
            for(Object mapper:mappers){for(int i=0;i<types.length;i++){Object type=javaType(mapper,types[i]);Object reader=typed(mapper,"readerFor",type),writer=typed(mapper,"writerFor",type);
                Object value=read(reader,oldRoots[i]);write(writer,value);codecs.add(new Object[]{mapper,reader,writer,config(reader),config(writer),newRoots[i],labels[i]});}
              Object r=call(mapper,"readerFor",Class.class,P.class),w=call(mapper,"writerFor",Class.class,P.class);
              for(String mode:List.of("view","locale")){Object reader=mode.equals("view")?call(r,"withView",Class.class,Object.class):call(r,"with",Locale.class,Locale.CANADA);
                Object writer=mode.equals("view")?call(w,"withView",Class.class,Object.class):call(w,"with",Locale.class,Locale.JAPAN);
                write(writer,read(reader,oldJson));derived.add(new Object[]{mapper,reader,writer,config(reader),config(writer),mode});}
              Object iterator=call(r,"readValues",String.class,"["+newJson+"]");derived.add(new Object[]{mapper,iterator});
            }
            check("beforeConfig",()->{same(parse(legacy,oldJson),parse(legacy,request(port,"/config",null)));return true;});
            check("beforeBody",()->{same(parse(legacy,oldJson),parse(legacy,request(port,"/body",oldJson)));return true;});
            check("beforeModel",()->{same(parse(legacy,oldJson),parse(legacy,request(port,"/model?"+query(components(Caller.make())),null)));return true;});
            String configName=root.getBeanNamesForType(P.class)[0];check("ordinaryConfigPrepare",()->agent("com.onurkat.reclazz.spring.SpringConstructorDependencies","prepare",new Class<?>[]{List.class,Object.class,String.class},List.of(root,web),root,configName)==null?"declined":"prepared");
            if(FAULT_CONFIG){
              Class<?> agentType=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");Field field=agentType.getDeclaredField("instrumentation");field.setAccessible(true);
              var real=(java.lang.instrument.Instrumentation)field.get(null);
              var observed=java.lang.reflect.Proxy.newProxyInstance(ContainerRunner.class.getClassLoader(),new Class<?>[]{java.lang.instrument.Instrumentation.class},(proxy,method,arguments)->{
                boolean cohort=method.getName().equals("redefineClasses")&&Arrays.stream((java.lang.instrument.ClassDefinition[])arguments[0]).anyMatch(d->d.getDefinitionClass()==P.class);
                try{Object result=method.invoke(real,arguments);if(cohort){
                  properties.put("p.a","not-an-int");
                  System.out.println("CONFIG_REPAIR_FAULT_ARMED");System.out.flush();
                }return result;}catch(InvocationTargetException failure){throw failure.getCause();}
              });field.set(null,observed);
            }
            System.out.println("READY|"+SpringBootVersion.getVersion()+"|"+modern);System.out.flush();boolean changed=false;
            while(!Files.exists(dir.resolve("observe"))){if(!changed&&Files.exists(dir.resolve("properties"))){properties.keySet().removeIf(k->k.startsWith("p."));((Map<String,Object>)parse(legacy,newJson)).forEach((k,v)->properties.put("p."+k,v));if(!EXPECTEDTAG.isEmpty())properties.put("p.a",0);changed=true;System.out.println("PROPERTIES_READY");System.out.flush();}Thread.sleep(20);}
            if(FAULT_CONFIG){
              check("nativeCommitted",()->{same(1,Caller.version());same(EXPECTEDCOMPONENTS,String.join(",",Arrays.stream(P.class.getRecordComponents()).map(c->c.getType().getSimpleName()+" "+c.getName()).toList()));return true;});
              check("configurationFailureLedger",()->{Object digest=agent("com.onurkat.reclazz.ui.RestartLedger","digest",new Class<?>[0]);if(!digest.toString().contains("configuration-properties")||!digest.toString().contains("Failed to bind")&&!digest.toString().contains("not-an-int"))throw new AssertionError(digest);return true;});
              check("constructorReceipt",()->{byte[] raw=Files.readAllBytes(Path.of(P.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("app/P.class"));same(true,agent("com.onurkat.reclazz.transform.KeepBaseline","CONSTRUCTOR_PROOF",new Class<?>[]{Class.class,byte[].class},P.class,raw));return true;});
              if(STOCK_ROUTE)check("physicalConstructorReceipt",()->{same(true,agent("com.onurkat.reclazz.transform.KeepBaseline","constructorBodiesUnchanged",new Class<?>[]{Class.class,byte[].class},P.class,originalPhysical));return true;});
              check("failedBindPreservesConfigAndLifecycle",()->{same(originalConfig,root.getBean(P.class));if(originalHolder!=root.getBean(Holder.class)||web.getBean(Endpoint.class).holder!=originalHolder||!originalHolder.running||originalHolder.destroyed!=0)throw new AssertionError("failed bind changed/stopped dependent");return true;});
              check("oldInstanceCallable",()->originalConfig.toString());
              System.out.println("FAULT_DONE|"+count);System.out.flush();tomcat.stop();tomcat.destroy();web.close();root.close();return;
            }
            check("afterConfig",()->{same(parse(legacy,newJson),parse(legacy,request(port,"/config",null)));return true;});
            check("afterBody",()->{same(parse(legacy,newJson),parse(legacy,request(port,"/body",newJson)));return true;});
            check("afterModel",()->{same(parse(legacy,newJson),parse(legacy,request(port,"/model?"+query(components(Caller.make())),null)));return true;});
            check("holderCurrent",()->{if(web.getBean(Endpoint.class).holder.config!=root.getBean(P.class))throw new AssertionError("stale config in endpoint holder");return true;});
            check("originalHolderRetained",()->{if(originalHolder!=web.getBean(Endpoint.class).holder||!originalHolder.running||originalHolder.destroyed!=0)throw new AssertionError("existing lifecycle holder was replaced/stopped");return true;});
            check("recordComponents",()->{String layout=String.join(",",Arrays.stream(P.class.getRecordComponents()).map(c->c.getType().getSimpleName()+" "+c.getName()).toList());same(EXPECTEDCOMPONENTS,layout);return true;});
            check("caller",()->{same(1,Caller.version());return Caller.make();});
            check("genericMetadata",()->{if(!EXPECTEDGENERIC.isEmpty())same(EXPECTEDGENERIC,P.class.getRecordComponents()[0].getGenericType().getTypeName());return true;});
            check("componentAnnotation",()->{if(!EXPECTEDTAG.isEmpty()){var annotation=Arrays.stream(P.class.getRecordComponents()[0].getDeclaredAnnotations()).filter(a->a.annotationType().getName().equals("app.ComponentTag")).findFirst().orElseThrow();same(EXPECTEDTAG,annotation.annotationType().getMethod("value").invoke(annotation));}return true;});
            check("oldRecordObjectMethods",()->{var components=P.class.getRecordComponents();Class<?>[] parameters=Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);Object[] values=new Object[components.length];for(int i=0;i<values.length;i++){values[i]=components[i].getAccessor().invoke(originalConfig);Object expected=originalTypes.get(components[i].getName())==components[i].getType()?originalValues.get(components[i].getName()):java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(components[i].getType(),1),0);same(expected,values[i]);}Object equivalent=P.class.getDeclaredConstructor(parameters).newInstance(values);same(true,originalConfig.equals(equivalent));same(originalConfig.hashCode(),equivalent.hashCode());same(originalConfig.toString(),equivalent.toString());if(!new HashSet<>(List.of(equivalent)).contains(originalConfig))throw new AssertionError("new collection ObjectMethods contract");return true;});
            for(Object[] row:codecs){Object mapper=row[0],reader=row[1],writer=row[2];String expected=(String)row[5];String label=mapper.getClass().getName()+"/"+row[6];
              check("reader/"+label,()->{if(config(reader)!=row[3])throw new AssertionError("reader config replaced");Object value=read(reader,expected);same(parse(mapper,expected),parse(mapper,write(writer,value)));return true;});
              check("writer/"+label,()->{if(config(writer)!=row[4])throw new AssertionError("writer config replaced");Object fresh=read(typed(mapper,"readerFor",javaType(mapper,types[Arrays.asList(labels).indexOf(row[6])])),expected);same(parse(mapper,expected),parse(mapper,write(writer,fresh)));return true;});}
            check("concurrentRetainedRootsAfterRepair",()->{
              var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
              try{List<java.util.concurrent.Callable<Boolean>> jobs=new ArrayList<>();
                for(int worker=0;worker<2;worker++)jobs.add(()->{for(int repeat=0;repeat<20;repeat++)for(Object[] row:codecs){String expected=(String)row[5];same(parse(row[0],expected),parse(row[0],write(row[2],read(row[1],expected))));}return true;});
                for(var future:executor.invokeAll(jobs,15,java.util.concurrent.TimeUnit.SECONDS))same(true,future.get());
                return codecs.size()*40;
              }finally{executor.shutdownNow();}
            });
            for(Object[] row:derived){Object mapper=row[0];if(row.length==2){check("iterator/"+mapper.getClass().getName(),()->{Object value=row[1].getClass().getMethod("nextValue").invoke(row[1]);same(parse(mapper,newJson),parse(mapper,write(call(mapper,"writerFor",Class.class,P.class),value)));row[1].getClass().getMethod("close").invoke(row[1]);return true;});}
              else{check("derived-reader/"+mapper.getClass().getName()+"/"+row[5],()->{if(config(row[1])!=row[3])throw new AssertionError("derived reader config");same(parse(mapper,newJson),parse(mapper,write(row[2],read(row[1],newJson))));return true;});
                check("derived-writer/"+mapper.getClass().getName()+"/"+row[5],()->{if(config(row[2])!=row[4])throw new AssertionError("derived writer config");Object fresh=read(call(mapper,"readerFor",Class.class,P.class),newJson);same(parse(mapper,newJson),parse(mapper,write(row[2],fresh)));return true;});}}
            check("constructorReceipt",()->{byte[] raw=Files.readAllBytes(Path.of(P.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("app/P.class"));same(true,agent("com.onurkat.reclazz.transform.KeepBaseline","CONSTRUCTOR_PROOF",new Class<?>[]{Class.class,byte[].class},P.class,raw));return true;});
            if(STOCK_ROUTE)check("physicalConstructorReceipt",()->{same(true,agent("com.onurkat.reclazz.transform.KeepBaseline","constructorBodiesUnchanged",new Class<?>[]{Class.class,byte[].class},P.class,originalPhysical));return true;});
            check("restartLedger",()->{Object digest=agent("com.onurkat.reclazz.ui.RestartLedger","digest",new Class<?>[0]);if(!digest.toString().contains("Nothing from this session"))throw new AssertionError(digest);return true;});
            System.out.println("DONE|"+count);System.out.flush();tomcat.stop();tomcat.destroy();web.close();root.close();
          }
        }
        """;
}
