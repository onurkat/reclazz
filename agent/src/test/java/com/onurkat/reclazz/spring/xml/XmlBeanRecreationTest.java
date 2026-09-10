/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlBeanRecreationTest {
    @TempDir Path tmp;
    public static class Product {
        static int made, initialized, destroyed;
        final int value;
        public Product(int value) { made++; this.value=value; }
        public void init() { initialized++; }
        public void stop() { destroyed++; }
        public void broken() { throw new IllegalStateException("init refused"); }
    }
    public static class Holder {
        Product target;
        String label;
        public void setTarget(Product value) { target=value; }
        public void setLabel(String value) { label=value; }
        public void init() { }
    }
    public static class FinalHolder { final Product target; FinalHolder(Product p) { target=p; } }
    public static class CollectionHolder { List<Product> targets; CollectionHolder(Product p) { targets=List.of(p); } }
    public static class Resource extends Product implements AutoCloseable {
        public Resource(int value) { super(value); }
        public void close() { }
    }
    public static class Producer {
        static boolean allowed;
        public static Product make(int value) { if(!allowed) throw new IllegalStateException("not ready"); return new Product(value); }
    }
    public static class RefProduct {
        final Product source;
        public RefProduct(Product source) { this.source=source; }
    }
    @BeforeEach void reset() {
        ApplicationContextHolder.clear(); RestartLedger.clear();
        Product.made=Product.initialized=Product.destroyed=0; Producer.allowed=true;
    }
    @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @Test void repeatedConstructorSavesRepairTheOriginalHeldConsumerAndPreserveAliases() throws Exception {
        try(var scope=new Scope(xml(1)+holder(""))) {
            Holder held=scope.context.getBean("holder",Holder.class);
            Product first=held.target;
            for(int n=2;n<=4;n++) {
                scope.reload(xml(n)+holder(""));
                assertEquals(n,held.target.value);
                assertSame(held.target,scope.context.getBean("alias"));
                assertSame(held.target,scope.context.getBean("holder",Holder.class).target);
            }
            assertNotSame(first,held.target); assertEquals(4,Product.made);
            assertEquals(4,Product.initialized); assertEquals(3,Product.destroyed);
            scope.reload(xml(4)+holder("")); assertEquals(4,Product.made);
        }
        assertEquals(4,Product.destroyed);
    }

    @Test void conversionFailureRestoresDefinitionsAndRepairsHoldersThenIdenticalSaveCanRetry() throws Exception {
        try(var scope=new Scope(xml(1)+holder(""))) {
            Holder held=scope.context.getBean("holder",Holder.class);
            scope.context.getBeanFactory().addEmbeddedValueResolver(value->value.replace("${next}",Producer.allowed ? "bad" : "2"));
            String next=xml("${next}","init","stop")+holder("");
            scope.reload(next);
            assertEquals(1,held.target.value); assertSame(held.target,scope.context.getBean("target"));
            assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("previous definitions restored")),RestartLedger.digest().toString());
            Producer.allowed=false; scope.reload(next);
            assertEquals(2,held.target.value); assertEquals(3,Product.initialized); assertEquals(2,Product.destroyed);
        }
    }

    @Test void initFailureReportsSideEffectsAndRestoresThePreviousDefinition() throws Exception {
        try(var scope=new Scope(xml(1)+holder(""))) {
            Holder held=scope.context.getBean("holder",Holder.class);
            scope.reload(xml("2","broken","stop")+holder(""));
            assertEquals(1,held.target.value); assertSame(held.target,scope.context.getBean("target"));
            assertEquals("init",scope.context.getBeanFactory().getBeanDefinition("target").getInitMethodName());
            assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("init refused")));
            scope.reload(xml(3)+holder("")); assertEquals(3,held.target.value);
        }
    }

    @Test void failedRestorationIsExplicitAndDoesNotReportSuccess() throws Exception {
        String body="<bean id='target' class='"+Producer.class.getName()+"' factory-method='make'><constructor-arg value='1'/></bean>";
        try(var scope=new Scope(body)) {
            Producer.allowed=false; scope.reload(body.replace("value='1'","value='2'"));
            assertFalse(scope.context.getBeanFactory().containsSingleton("target"));
            assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("restoration failed")),RestartLedger.digest().toString());
        }
    }

    @Test void lazyDefinitionChangesDoNotInstantiateOrRunLifecycle() throws Exception {
        try(var scope=new Scope(xml(1).replace("id='target'","id='target' lazy-init='true'"))) {
            assertEquals(0,Product.made);
            scope.reload(xml(2).replace("id='target'","id='target' lazy-init='true'"));
            assertEquals(0,Product.made);
            assertEquals(2,scope.context.getBean("target",Product.class).value); assertEquals(1,Product.initialized);
        }
    }

    @Test void explicitConstructorReferencesResolveAgainstTheLiveFactory() throws Exception {
        String root="<bean id='target' class='"+RefProduct.class.getName()+"'><constructor-arg ref='%s'/></bean>";
        String providers="<bean id='one' class='"+Product.class.getName()+"'><constructor-arg value='1'/></bean>"
                +"<bean id='two' class='"+Product.class.getName()+"'><constructor-arg value='2'/></bean>";
        try(var scope=new Scope(providers+root.formatted("one"))) {
            scope.reload(providers+root.formatted("two"));
            assertSame(scope.context.getBean("two"),scope.context.getBean("target",RefProduct.class).source);
        }
    }

    @Test void sameNamedBeansInADifferentResourceAreNotRecreated() throws Exception {
        try(var scope=new Scope(xml(1))) {
            Object old=scope.context.getBean("target");
            Path other=Files.createDirectories(tmp.resolve("other")).resolve("service-spring.xml");
            Files.writeString(other,envelope(xml(9))); scope.reloader.reload(other);
            assertSame(old,scope.context.getBean("target")); assertEquals(0,Product.destroyed);
            assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("exact XML resource")));
        }
    }

    @Test void allOwningContextsGetTheirOwnReplacement() throws Exception {
        try(var scope=new Scope(xml(1)); var second=new GenericApplicationContext()) {
            load(second,scope.file); second.refresh(); ApplicationContextHolder.register(second);
            Product a=scope.context.getBean("target",Product.class),b=second.getBean("target",Product.class);
            scope.reload(xml(2));
            assertNotSame(a,scope.context.getBean("target")); assertNotSame(b,second.getBean("target"));
            assertEquals(2,second.getBean("target",Product.class).value); assertEquals(2,Product.destroyed);
        }
    }

    @Test void contextsResolveTheSamePlaceholderIndependently() throws Exception {
        try(var scope=new Scope(xml(1)); var second=new GenericApplicationContext()) {
            load(second,scope.file); second.refresh(); ApplicationContextHolder.register(second);
            scope.context.getBeanFactory().addEmbeddedValueResolver(s->s.replace("${next}","2"));
            second.getBeanFactory().addEmbeddedValueResolver(s->s.replace("${next}","3"));
            scope.reload(xml("${next}","init","stop"));
            assertEquals(2,scope.context.getBean("target",Product.class).value);
            assertEquals(3,second.getBean("target",Product.class).value);
        }
    }

    @Test void lifecycleDependentIsRefusedBeforeAnythingIsDestroyed() throws Exception {
        try(var scope=new Scope(xml(1)+holder("init-method='init'"))) {
            Object old=scope.context.getBean("target"); scope.reload(xml(2)+holder("init-method='init'"));
            assertSame(old,scope.context.getBean("target")); assertEquals(0,Product.destroyed);
            assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("dependent constructor/factory/lifecycle")));
        }
    }

    @Test void finalDirectHolderFieldIsRefusedBeforeDestruction() throws Exception {
        try(var scope=new Scope(xml(1))) {
            Product old=scope.context.getBean("target",Product.class);
            scope.context.getBeanFactory().registerSingleton("fixed",new FinalHolder(old));
            scope.reload(xml(2)); assertSame(old,scope.context.getBean("target")); assertEquals(0,Product.destroyed);
            assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("final holder field")));
        }
    }

    @Test void containerHolderIsRefusedBeforeDestruction() throws Exception {
        try(var scope=new Scope(xml(1))) {
            Product old=scope.context.getBean("target",Product.class);
            scope.context.getBeanFactory().registerSingleton("collection",new CollectionHolder(old));
            scope.reload(xml(2)); assertSame(old,scope.context.getBean("target")); assertEquals(0,Product.destroyed);
            assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("collection holder")));
        }
    }

    @Test void resourceAndProxyProductsRemainUntouched() throws Exception {
        try(var scope=new Scope(xml(1).replace(Product.class.getName(),Resource.class.getName()))) {
            Object old=scope.context.getBean("target"); scope.reload(xml(2).replace(Product.class.getName(),Resource.class.getName()));
            assertSame(old,scope.context.getBean("target")); assertEquals(0,Product.destroyed);
        }
        try(var scope=new Scope(xml(1))) {
            Product target=scope.context.getBean("target",Product.class);
            var proxy=new org.springframework.aop.framework.ProxyFactory(target); proxy.setProxyTargetClass(true);
            Object wrapped=proxy.getProxy(); scope.context.getDefaultListableBeanFactory().destroySingleton("target");
            scope.context.getBeanFactory().registerSingleton("target",wrapped); int before=Product.destroyed;
            scope.reload(xml(2)); assertSame(wrapped,scope.context.getBean("target")); assertEquals(before,Product.destroyed);
        }
    }

    @Test void unrelatedMetadataAndDerivedDefinitionsAreRefused() throws Exception {
        try(var scope=new Scope(xml(1))) {
            Object old=scope.context.getBean("target");
            for(String extra:List.of("primary='true'","scope='prototype'","depends-on='other'","autowire='byType'")) {
                scope.reload(xml(2).replace("id='target'","id='target' "+extra));
                assertSame(old,scope.context.getBean("target")); assertEquals(0,Product.destroyed);
            }
            var child=new org.springframework.beans.factory.support.GenericBeanDefinition(); child.setParentName("target");
            child.setAbstract(true); scope.context.registerBeanDefinition("child",child);
            scope.reload(xml(2)); assertSame(old,scope.context.getBean("target")); assertEquals(0,Product.destroyed);
        }
    }

    @Test void disabledOverridingAndAliasEditsAreRefusedBeforeDestruction() throws Exception {
        try(var scope=new Scope(xml(1))) {
            Object old=scope.context.getBean("target");
            scope.reload(xml(2).replace("name='alias'","name='different'")); assertSame(old,scope.context.getBean("target"));
            scope.context.getDefaultListableBeanFactory().setAllowBeanDefinitionOverriding(false);
            scope.reload(xml(2)); assertSame(old,scope.context.getBean("target")); assertEquals(0,Product.destroyed);
        }
    }

    @Test void ordinaryPropertyMutationStillPreservesIdentity() throws Exception {
        String target="<bean id='target' class='"+Product.class.getName()+"'><constructor-arg value='1'/></bean>";
        try(var scope=new Scope(target+holder(""))) {
            Holder old=scope.context.getBean("holder",Holder.class);
            scope.reload(target+holder("").replace("</bean>","<property name='label' value='updated'/></bean>"));
            assertSame(old,scope.context.getBean("holder")); assertEquals("updated",old.label);
        }
    }

    private class Scope implements AutoCloseable {
        final Path file; final GenericApplicationContext context=new GenericApplicationContext(); final SpringXmlReloader reloader;
        Scope(String body) throws Exception {
            file=Files.createDirectories(tmp.resolve(UUID.randomUUID().toString())).resolve("service-spring.xml");
            Files.writeString(file,envelope(body)); load(context,file); context.refresh();
            PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (p,m,a)->m.getName().equals("getApplicationContext") ? context : m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader=new SpringXmlReloader(platform);
        }
        void reload(String body) throws Exception { Files.writeString(file,envelope(body)); reloader.reload(file); }
        public void close() { context.close(); }
    }
    private static void load(GenericApplicationContext context,Path file) {
        var reader=new XmlBeanDefinitionReader(context); reader.setValidating(false); reader.loadBeanDefinitions(new FileSystemResource(file));
    }
    private static String envelope(String body) { return "<beans xmlns='http://www.springframework.org/schema/beans'>"+body+"</beans>"; }
    private static String xml(int value) { return xml(Integer.toString(value),"init","stop"); }
    private static String xml(String value,String init,String destroy) {
        return "<bean id='target' name='alias' class='"+Product.class.getName()+"' init-method='"+init+"' destroy-method='"+destroy
                +"'><constructor-arg value='"+value+"'/></bean>";
    }
    private static String holder(String extra) {
        return "<bean id='holder' class='"+Holder.class.getName()+"' "+extra+"><property name='target' ref='target'/></bean>";
    }
}
