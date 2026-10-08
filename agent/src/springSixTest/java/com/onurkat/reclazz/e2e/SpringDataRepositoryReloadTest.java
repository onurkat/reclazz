/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class SpringDataRepositoryReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void watchedQueryEditReachesExistingHolderWithoutRestart(boolean childLoader, boolean annotated) throws Exception {
        String dependencies=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->p.endsWith(".jar") && !Path.of(p).getFileName().toString().startsWith("agent-"))
                .collect(Collectors.joining(File.pathSeparator));
        var builder=WatchedApp.in(tmp).classpath(dependencies).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("App",APP).with("Row",ROW).with("Records",records(1,annotated));
        if(childLoader)builder.childClassLoader();
        try(var app=builder.start()) {
            app.awaitOrFail("PORT=","application not ready");app.awaitOrFail("] Watching ","watcher not ready");
            int port=Integer.parseInt(app.latest("PORT=").substring(5));
            var client=HttpClient.newHttpClient();
            String initial=get(client,port);assertTrue(initial.endsWith("|before|before"),app.tail());
            String identity=initial.substring(0,initial.indexOf('|'));
            for(int id:new int[]{2,1,2}) {
                app.rewrite("Records",records(id,annotated));
                String expected=identity+"|"+(id==1?"before|before":"after|after");
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);String observed="";
                while(System.nanoTime()<deadline) {
                    observed=get(client,port);if(expected.equals(observed))break;Thread.sleep(50);
                }
                assertEquals(expected,observed,app.tail());
            }
        }
    }

    private static String get(HttpClient client,int port) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"))
                .timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString()).body();
    }
    private static String records(int id,boolean annotated) {
        return """
            package app;
            %s
            public interface Records extends org.springframework.data.jpa.repository.JpaRepository<Row,Long> {
                @org.springframework.data.jpa.repository.Query("select r.label from ProofRow r where r.id = %d")
                String label();
            }
            """.formatted(annotated ? "@org.springframework.stereotype.Repository" : "", id);
    }
    private static final String ROW="""
        package app;
        @jakarta.persistence.Entity(name="ProofRow") @jakarta.persistence.Table(name="proof_row")
        public class Row { @jakarta.persistence.Id public long id; public String label; }
        """;
    private static final String APP="""
        package app;
        import org.springframework.context.support.GenericApplicationContext;
        import org.springframework.orm.jpa.*;
        import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
        import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
        import org.springframework.jdbc.datasource.DriverManagerDataSource;
        import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
        import jakarta.persistence.EntityManagerFactory;
        public class App {
            public static class Holder { public Records records; }
            public static void main(String[] args)throws Exception {
                var ctx=new GenericApplicationContext();
                ctx.registerBean("entityManagerFactory",LocalContainerEntityManagerFactoryBean.class,()->{
                    var f=new LocalContainerEntityManagerFactoryBean();
                    f.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:watched;DB_CLOSE_DELAY=-1","sa",""));
                    f.setManagedTypes(PersistenceManagedTypes.of(Row.class.getName()));
                    f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                    f.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto","create-drop","hibernate.archive.autodetection","none"));
                    return f;
                });
                ctx.registerBean("transactionManager",JpaTransactionManager.class,()->new JpaTransactionManager(ctx.getBean(EntityManagerFactory.class)));
                ctx.registerBean("records",JpaRepositoryFactoryBean.class,()->{
                    var f=new JpaRepositoryFactoryBean<Records,Row,Long>(Records.class);
                    f.setEntityManager(SharedEntityManagerCreator.createSharedEntityManager(ctx.getBean(EntityManagerFactory.class)));
                    f.setEntityPathResolver(ctx.getBeanProvider(org.springframework.data.querydsl.EntityPathResolver.class));return f;
                });
                ctx.registerBean("holder",Holder.class);ctx.refresh();
                try(var em=ctx.getBean(EntityManagerFactory.class).createEntityManager()) {
                    em.getTransaction().begin();
                    for(int id=1;id<=2;id++){var r=new Row();r.id=id;r.label=id==1?"before":"after";em.persist(r);}
                    em.getTransaction().commit();
                }
                var holder=ctx.getBean(Holder.class);holder.records=ctx.getBean(Records.class);
                String identity=ProcessHandle.current().pid()+":"+java.util.UUID.randomUUID()+":"+System.identityHashCode(holder);
                var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/",exchange->{
                    String reply;try{reply=identity+"|"+holder.records.label()+"|"+ctx.getBean(Records.class).label();}
                    catch(Throwable failure){reply="TRANSIENT:"+failure.getClass().getSimpleName()+":"+failure.getMessage();}
                    byte[] body=reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
                });
                server.start();System.out.println("PORT="+server.getAddress().getPort());
            }
        }
        """;
}
