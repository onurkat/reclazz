/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.jms.annotation.*;
import org.springframework.jms.config.*;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ModernJmsDependencyRefreshTest {
    static BrokerService broker;
    static String url;
    static int next;
    static BlockingQueue<String> received;
    static CountDownLatch entered, release;
    static boolean block;
    public static class Helper { final int seed = next; }
    public static class Owner {
        final int seed;
        public Owner(Helper helper) { seed = helper.seed; }
        @JmsListener(id="target", destination="rf14-guard-target")
        public void receive(String value) {
            if (block) {
                entered.countDown();
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
            }
            received.add(value + ":" + seed);
        }
    }
    public static class Other {
        @JmsListener(id="other", destination="rf14-guard-other")
        public void receive(String value) { received.add("other:" + value); }
    }
    @Configuration(proxyBeanMethods=false) @EnableJms
    static class Config {
        @Bean DefaultJmsListenerContainerFactory jmsListenerContainerFactory() {
            var f = new DefaultJmsListenerContainerFactory();
            f.setConnectionFactory(new ActiveMQConnectionFactory(url));
            f.setReceiveTimeout(50L); f.setSessionTransacted(true); return f;
        }
    }
    static class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final JmsListenerEndpointRegistry registry;
        final DefaultMessageListenerContainer target, other;
        final Object helper, owner;
        Scope() { this(false); }
        Scope(boolean propertyOwner) {
            next = 10; received = new LinkedBlockingQueue<>(); block = false;
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
            if (propertyOwner) context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("native-owner-value", Map.of("owner.seed", "10")));
            context.register(Config.class);
            context.registerBean("helper", Helper.class);
            if (propertyOwner) context.registerBean("owner", PropertyOwner.class);
            else context.registerBean("owner", Owner.class);
            context.registerBean("other", Other.class); context.refresh();
            registry = context.getBean(JmsListenerEndpointRegistry.class);
            target = (DefaultMessageListenerContainer) registry.getListenerContainer("target");
            other = (DefaultMessageListenerContainer) registry.getListenerContainer("other");
            helper = context.getBean("helper"); owner = context.getBean("owner");
        }
        SpringJmsReloader.DependencyRefresh prepare(Object... contexts) throws Exception {
            return SpringJmsReloader.prepareDependencyRefresh(List.of(contexts), context, "helper");
        }
        void unchanged() {
            var f = context.getBeanFactory();
            assertSame(helper, f.getSingleton("helper")); assertSame(owner, f.getSingleton("owner"));
            assertSame(target, registry.getListenerContainer("target")); assertTrue(target.isActive()); assertTrue(target.isRunning());
            assertSame(other, registry.getListenerContainer("other")); assertTrue(other.isActive());
        }
        void message(String queue, String value, String expected) throws Exception {
            new JmsTemplate(new ActiveMQConnectionFactory(url)).convertAndSend(queue, value);
            assertEquals(expected, received.poll(5, TimeUnit.SECONDS));
        }
        @Override public void close() { release.countDown(); context.close(); }
    }
    @BeforeAll static void start() throws Exception {
        broker = new BrokerService(); broker.setBrokerName("jms-cascade-guard-"+UUID.randomUUID());
        broker.setPersistent(false); broker.setUseJmx(false); broker.setUseShutdownHook(false);
        var c = broker.addConnector("tcp://127.0.0.1:0"); broker.start(); broker.waitUntilStarted(); url=c.getPublishableConnectString();
    }
    @AfterAll static void stop() throws Exception { broker.stop(); broker.waitUntilStopped(); }
    @AfterEach void reset() { RestartLedger.clear(); }
    @Test void readOnlyPreflightAndRepeatedCurrentReceivers() throws Exception {
        try (var s = new Scope()) {
            assertNotNull(s.prepare(s.context)); s.unchanged();
            Object untouched = s.context.getBeanFactory().getMergedBeanDefinition("other");
            for (int seed : new int[]{20,30,40}) {
                next=seed; var old=(DefaultMessageListenerContainer)s.registry.getListenerContainer("target");
                s.prepare(s.context).recreate();
                assertFalse(old.isActive()); assertEquals(0,old.getActiveConsumerCount()); assertEquals(0,old.getScheduledConsumerCount());
                assertEquals(seed,((Owner)s.context.getBeanFactory().getSingleton("owner")).seed);
                assertArrayEquals(new String[]{"owner"},s.context.getBeanFactory().getDependentBeans("helper"));
                assertSame(s.other,s.registry.getListenerContainer("other"));
                assertSame(untouched,s.context.getBeanFactory().getMergedBeanDefinition("other"));
                s.message("rf14-guard-target","save"+seed,"save"+seed+":"+seed);
                s.message("rf14-guard-other","save"+seed,"other:save"+seed);
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"ownerSupplier","helperSupplier","helperFactory","helperArguments","extraDependent","transitiveDependent","multipleContexts","ownerFactory","helperDependency","foreignBroker"})
    void unsupportedClosureRefusesBeforeAnyConsumerOrSingletonChange(String policy) throws Exception {
        try (var s=new Scope()) {
            var f=s.context.getDefaultListableBeanFactory();
            var root=(AbstractBeanDefinition)s.context.getBeanDefinition("helper");
            var owner=(AbstractBeanDefinition)s.context.getBeanDefinition("owner");
            switch(policy) {
                case "ownerSupplier" -> owner.setInstanceSupplier(()->s.owner);
                case "helperSupplier" -> root.setInstanceSupplier(()->s.helper);
                case "helperFactory" -> root.setFactoryMethodName("make");
                case "helperArguments" -> root.getConstructorArgumentValues().addIndexedArgumentValue(0,10);
                case "ownerFactory" -> owner.setFactoryMethodName("make");
                case "helperDependency" -> {f.registerSingleton("prior",new Object());f.registerDependentBean("prior","helper");}
                case "foreignBroker" -> f.registerSingleton("foreignKafkaProcessor",Class.forName("org.springframework.kafka.annotation.KafkaListenerAnnotationBeanPostProcessor").getConstructor().newInstance());
                case "extraDependent" -> {f.registerSingleton("holder",new Object());f.registerDependentBean("helper","holder");}
                case "transitiveDependent" -> {f.registerSingleton("holder",new Object());f.registerDependentBean("owner","holder");}
                case "multipleContexts" -> { }
                default -> fail(policy);
            }
            Object merged=f.getMergedBeanDefinition("owner");
            Object[] contexts=policy.equals("multipleContexts")?new Object[]{s.context,new Object()}:new Object[]{s.context};
            assertThrows(Exception.class,()->s.prepare(contexts));
            s.unchanged(); assertSame(merged,f.getMergedBeanDefinition("owner"));
            s.message("rf14-guard-target",policy,policy+":10");
        }
    }
    @Test void parentHelperWithOnlyChildConsumerRefusesBeforeParentDestruction() throws Exception {
        next=10;received=new LinkedBlockingQueue<>();block=false;release=new CountDownLatch(1);
        try(var parent=new AnnotationConfigApplicationContext();var child=new AnnotationConfigApplicationContext()) {
            parent.registerBean("helper",Helper.class);parent.refresh();
            child.setParent(parent);child.register(Config.class);child.registerBean("owner",Owner.class);child.refresh();
            Object helper=parent.getBean("helper"),owner=child.getBean("owner");
            var target=(DefaultMessageListenerContainer)child.getBean(JmsListenerEndpointRegistry.class).getListenerContainer("target");
            assertArrayEquals(new String[0],parent.getBeanFactory().getDependentBeans("helper"));
            assertArrayEquals(new String[]{"owner"},child.getBeanFactory().getDependentBeans("helper"));
            assertThrows(Exception.class,()->SpringJmsReloader.prepareDependencyRefresh(List.of(parent,child),parent,"helper"));
            assertSame(helper,parent.getBeanFactory().getSingleton("helper"));assertSame(owner,child.getBeanFactory().getSingleton("owner"));
            assertTrue(target.isActive());assertTrue(target.isRunning());
        }
    }
    @Test void changedOwnerDefinitionAfterPreflightDoesNotRetireHealthyConsumers() throws Exception {
        try(var s=new Scope()) {
            var prepared=s.prepare(s.context);assertNotNull(prepared);
            ((AbstractBeanDefinition)s.context.getBeanDefinition("owner")).setInstanceSupplier(()->s.owner);
            assertThrows(Exception.class,prepared::recreate);s.unchanged();
            s.message("rf14-guard-target","changed","changed:10");
        }
    }
    @Test void inactiveConsumerRefusesBeforeDestruction() throws Exception {
        try(var s=new Scope()) {
            s.target.stop();
            assertThrows(Exception.class,()->s.prepare(s.context));
            assertSame(s.helper,s.context.getBeanFactory().getSingleton("helper"));
            assertSame(s.owner,s.context.getBeanFactory().getSingleton("owner"));
            assertSame(s.target,s.registry.getListenerContainer("target"));
            assertSame(s.other,s.registry.getListenerContainer("other"));assertTrue(s.other.isRunning());
        }
    }
    @Test void failedOwnerCreationIsPartialAndNeverRemovesUnrelatedWorker() throws Exception {
        try(var s=new Scope()) {
            var prepared=s.prepare(s.context);assertNotNull(prepared);
            s.context.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor(){
                @Override public Object postProcessBeforeInitialization(Object bean,String name){
                    if(name.equals("owner"))throw new IllegalStateException("injected creation failure");return bean;
                }
            });
            next=20;assertThrows(Exception.class,prepared::recreate);
            assertFalse(s.target.isActive());assertNull(s.registry.getListenerContainer("target"));
            assertEquals(20,((Helper)s.context.getBeanFactory().getSingleton("helper")).seed);
            assertNull(s.context.getBeanFactory().getSingleton("owner"));
            assertSame(s.other,s.registry.getListenerContainer("other"));assertTrue(s.other.isRunning());
            assertTrue(RestartLedger.digest().stream().anyMatch(x->x.contains("partially applied")));
            s.message("rf14-guard-other","failure","other:failure");
        }
    }
    public static class PropertyOwner {
        final int seed, own;
        public PropertyOwner(Helper helper, @org.springframework.beans.factory.annotation.Value("${owner.seed:10}") int value) {
            seed = helper.seed; own = value;
        }
        @JmsListener(id="target", destination="rf14-guard-target")
        public void receive(String value) { received.add(value + ":" + seed + ":" + own); }
    }

    @Test void directPropertyConsumerRefusesBeforeSingletonOrWorkerDestruction() throws Exception {
        try (var s = new Scope(true)) {
            s.message("rf14-guard-target", "warm-property", "warm-property:10:10");
            var accepted = new java.util.concurrent.atomic.AtomicBoolean();
            var result = new SpringPropertyRebinder(List.of(s.context)).apply(Map.of("owner.seed", "20"), Runnable::run, () -> accepted.set(true));
            assertEquals(PropertyChangeOutcome.State.PARTIAL, result.state());
            assertFalse(accepted.get(), "an unsupported direct consumer property must remain pending");
            s.unchanged();
            s.message("rf14-guard-target", "held-property", "held-property:10:10");
            s.message("rf14-guard-other", "held-property", "other:held-property");
            assertTrue(RestartLedger.digest().stream().anyMatch(x -> x.contains("direct JMS")));
        }
    }

    @Test void registeredConsumerIsRetiredWhenLaterInitializationFails() throws Exception {
        try (var s = new Scope()) {
            var prepared = s.prepare(s.context); assertNotNull(prepared);
            var created = new java.util.concurrent.atomic.AtomicReference<DefaultMessageListenerContainer>();
            s.context.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (name.equals("owner")) {
                        created.set((DefaultMessageListenerContainer) s.registry.getListenerContainer("target"));
                        throw new IllegalStateException("injected failure after native callback registration");
                    }
                    return bean;
                }
            });
            next = 20; assertThrows(Exception.class, prepared::recreate);
            assertNotNull(created.get(), "failure must occur after a real replacement container was registered");
            assertNotSame(s.target, created.get());
            assertFalse(created.get().isActive()); assertFalse(created.get().isRunning());
            assertEquals(0, created.get().getActiveConsumerCount());
            assertEquals(0, created.get().getScheduledConsumerCount());
            assertNull(s.registry.getListenerContainer("target"));
            assertNull(s.context.getBeanFactory().getSingleton("owner"));
            assertEquals(20, ((Helper) s.context.getBeanFactory().getSingleton("helper")).seed);
            assertSame(s.other, s.registry.getListenerContainer("other")); assertTrue(s.other.isRunning());
            assertTrue(RestartLedger.digest().stream().anyMatch(x -> x.contains("partially applied")));
            s.message("rf14-guard-other", "registered-then-failed", "other:registered-then-failed");
        }
    }

    @Test void blockedShutdownNeverRecreatesHelperAndRefusesConcurrentRetirement() throws Exception {
        try(var s=new Scope()) {
            var prepared=s.prepare(s.context);assertNotNull(prepared);block=true;
            new JmsTemplate(new ActiveMQConnectionFactory(url)).convertAndSend("rf14-guard-target","held");
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(Exception.class,prepared::recreate);
            assertSame(s.helper,s.context.getBeanFactory().getSingleton("helper"));
            assertSame(s.owner,s.context.getBeanFactory().getSingleton("owner"));
            assertSame(s.target,s.registry.getListenerContainer("target"));
            assertThrows(Exception.class,()->s.prepare(s.context));
            assertSame(s.other,s.registry.getListenerContainer("other"));assertTrue(s.other.isRunning());
        }
    }
}
