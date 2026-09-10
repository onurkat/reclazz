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
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class XmlSingletonLifecycleReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void xmlRecreatesConstructorAndFactoryProductsAndRepairsHeldConsumer(boolean child) throws Exception {
        Path xml=Files.createDirectories(tmp.resolve("classes")).resolve("service-spring.xml");
        Files.writeString(xml,xml("1","make","init","stop"));
        var builder=WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if(child) builder.childClassLoader();
        try(var app=builder.jvmArgs("-Dxml.file="+xml,"-Dtest.dir="+tmp)
                .with("App",APP).with("Product",PRODUCT).with("Factory",FACTORY).with("Holder",HOLDER).start()) {
            app.awaitOrFail("READY=1:1:1:3:0", "initial products missing");
            app.awaitOrFail("] Watching ","watcher did not start");
            save(app,xml,1,xml("2","make","init","stop"),"2:2:2:6:3");
            save(app,xml,2,xml("3","other","initOther","stopOther"),"3:13:13:36:6");
            save(app,xml,3,xml("3","other","initOther","stopOther"),"3:13:13:36:6");
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=36", "current destruction methods must run once");
        }
    }
    private void save(WatchedApp app,Path file,int n,String content,String expected) throws Exception {
        Files.writeString(file,content);
        long until=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<until && reports(app)<n) Thread.sleep(25);
        assertEquals(n,reports(app),app.tail());
        Files.createFile(tmp.resolve("probe"+n)); app.awaitOrFail("XML"+n+"=","missing probe");
        String line=app.latest("XML"+n+"=");
        assertEquals("XML"+n+"="+expected,line.substring(line.indexOf("XML")),app.tail());
        System.out.println("[xml-lifecycle] "+line);
    }
    private long reports(WatchedApp app) {
        return app.output().stream().filter(s->s.contains("Spring XML service-spring.xml")
                && (s.contains(" reloaded:")||s.contains("parsed OK"))).count();
    }
    private static String xml(String value,String factory,String init,String destroy) {
        return """
            <beans xmlns="http://www.springframework.org/schema/beans">
              <bean id="direct" class="app.Product" init-method="%s" destroy-method="%s"><constructor-arg value="%s"/></bean>
              <bean id="staticProduct" class="app.Factory" factory-method="%s" init-method="%s" destroy-method="%s"><constructor-arg value="%s"/></bean>
              <bean id="factory" class="app.Factory"/>
              <bean id="instanceProduct" factory-bean="factory" factory-method="%sInstance" init-method="%s" destroy-method="%s"><constructor-arg value="%s"/></bean>
              <bean id="holder" class="app.Holder"><property name="direct" ref="direct"/><property name="staticProduct" ref="staticProduct"/><property name="instanceProduct" ref="instanceProduct"/></bean>
            </beans>
            """.formatted(init,destroy,value,factory,init,destroy,value,factory,init,destroy,value);
    }
    private static final String PRODUCT="""
        package app;
        public class Product {
            public static int initialized,stopped;
            public final int value;
            public Product(int value) { this.value=value; }
            public void init() { initialized++; }
            public void initOther() { initialized+=10; }
            public void stop() { stopped++; }
            public void stopOther() { stopped+=10; }
        }
        """;
    private static final String FACTORY="""
        package app;
        public class Factory {
            public static Product make(int value) { return new Product(value); }
            public static Product other(int value) { return new Product(value+10); }
            public Product makeInstance(int value) { return make(value); }
            public Product otherInstance(int value) { return other(value); }
        }
        """;
    private static final String HOLDER="""
        package app;
        public class Holder {
            private Product direct,staticProduct,instanceProduct;
            public void setDirect(Product p) { direct=p; }
            public void setStaticProduct(Product p) { staticProduct=p; }
            public void setInstanceProduct(Product p) { instanceProduct=p; }
            public String text() { return direct.value+":"+staticProduct.value+":"+instanceProduct.value+":"+Product.initialized+":"+Product.stopped; }
        }
        """;
    private static final String APP="""
        package app;
        public class App {
            public static void main(String[] args) throws Exception {
                var context=new org.springframework.context.support.GenericApplicationContext();
                var reader=new org.springframework.beans.factory.xml.XmlBeanDefinitionReader(context);
                reader.setValidating(false);
                reader.loadBeanDefinitions(new org.springframework.core.io.FileSystemResource(System.getProperty("xml.file")));
                context.refresh(); Holder held=context.getBean("holder",Holder.class);
                System.out.println("READY="+held.text());
                var dir=java.nio.file.Path.of(System.getProperty("test.dir"));
                for(int i=1;i<=3;i++) {
                    while(!java.nio.file.Files.exists(dir.resolve("probe"+i))) Thread.sleep(25);
                    System.out.println("XML"+i+"="+held.text());
                }
                while(!java.nio.file.Files.exists(dir.resolve("close"))) Thread.sleep(25);
                context.close(); System.out.println("CLOSED="+Product.stopped);
            }
        }
        """;
}
