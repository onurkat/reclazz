/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.kafka.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.config.*;

import org.springframework.kafka.listener.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ModernKafkaDependencyRefreshTest {
    static EmbeddedKafkaBroker broker;
    static DefaultKafkaProducerFactory<Integer,String> producerFactory;
    static KafkaTemplate<Integer,String> producer;
    static String url;
    static int next;
    static BlockingQueue<String> received;
    static CountDownLatch entered, release;
    static boolean block;
    public static class Helper { final int seed = next; }
    public static class Owner {
        final int seed;
        public Owner(Helper helper) { seed = helper.seed; }
        @KafkaListener(id="target", topics="rf14-guard-target")
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
        @KafkaListener(id="other", topics="rf14-guard-other")
        public void receive(String value) { received.add("other:" + value); }
    }
    @Configuration(proxyBeanMethods=false) @EnableKafka
    static class Config {
        @Bean ConcurrentKafkaListenerContainerFactory<Integer,String> kafkaListenerContainerFactory() {
            var props=new HashMap<String,Object>();
            props.put("bootstrap.servers",url);
            props.put("key.deserializer",org.apache.kafka.common.serialization.IntegerDeserializer.class);
            props.put("value.deserializer",org.apache.kafka.common.serialization.StringDeserializer.class);
            props.put("auto.offset.reset","earliest"); props.put("enable.auto.commit",false);
            var f=new ConcurrentKafkaListenerContainerFactory<Integer,String>();
            f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
            f.getContainerProperties().setPollTimeout(100); return f;
        }
    }
    static class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final KafkaListenerEndpointRegistry registry;
        final ConcurrentMessageListenerContainer<?,?> target, other;
        final Object helper, owner;
        final List<Future<?>> initialWorkers=new ArrayList<>();
        void captureWorkers(MessageListenerContainer container, List<Future<?>> futures) {
            if(container instanceof ConcurrentMessageListenerContainer<?,?> concurrent)
                for(var child: concurrent.getContainers()) {
                    Object f=com.onurkat.reclazz.util.Reflect.readField(child,"listenerConsumerFuture");
                    assertTrue(f instanceof Future<?>,"native Kafka consumer completion future missing");futures.add((Future<?>)f);
                }
        }
        Scope() throws Exception { this(false); }
        Scope(boolean propertyOwner) throws Exception {
            next = 10; received = new LinkedBlockingQueue<>(); block = false;
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
            if (propertyOwner) context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("native-owner-value", Map.of("owner.seed", "10")));
            context.register(Config.class);
            context.registerBean("helper", Helper.class);
            if (propertyOwner) context.registerBean("owner", PropertyOwner.class);
            else context.registerBean("owner", Owner.class);
            context.registerBean("other", Other.class); context.refresh();
            registry = context.getBean(KafkaListenerEndpointRegistry.class);
            target = (ConcurrentMessageListenerContainer<?,?>) registry.getListenerContainer("target");
            other = (ConcurrentMessageListenerContainer<?,?>) registry.getListenerContainer("other");
            helper = context.getBean("helper"); owner = context.getBean("owner");
            ready(target); ready(other); captureWorkers(target,initialWorkers); captureWorkers(other,initialWorkers);
        }
        SpringKafkaReloader.DependencyRefresh prepare(Object... contexts) throws Exception {
            return SpringKafkaReloader.prepareDependencyRefresh(List.of(contexts), context, "helper");
        }
        void unchanged() {
            var f = context.getBeanFactory();
            assertSame(helper, f.getSingleton("helper")); assertSame(owner, f.getSingleton("owner"));
            assertSame(target, registry.getListenerContainer("target")); assertTrue(target.isChildRunning()); assertTrue(target.isRunning());
            assertSame(other, registry.getListenerContainer("other")); assertTrue(other.isChildRunning());
        }
        void message(String queue, String value, String expected) throws Exception {
            producer.send(queue, value).get(10,TimeUnit.SECONDS);
            assertEquals(expected, received.poll(5, TimeUnit.SECONDS));
        }
        @Override public void close() throws Exception {
            release.countDown(); var containers=new ArrayList<>(registry.getListenerContainers());
            containers.add(target); containers.add(other);
            var workers=new ArrayList<>(initialWorkers);for(var c: containers)captureWorkers(c,workers);
            context.close();
            for(var future: workers)future.get(20,TimeUnit.SECONDS);
            assertTrue(workers.stream().allMatch(Future::isDone),"native consumer work must really terminate");
            assertTrue(containers.stream().noneMatch(MessageListenerContainer::isRunning));
        }
    }
    private static void ready(MessageListenerContainer container) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<end){var p=container.getAssignedPartitions();if(p!=null&&!p.isEmpty())return;Thread.sleep(25);}
        fail("native Kafka assignment missing");
    }
    @BeforeAll static void start() throws Exception {
        broker = new org.springframework.kafka.test.EmbeddedKafkaZKBroker(1,false,1,"rf14-guard-target","rf14-guard-other");
        broker.afterPropertiesSet();url=broker.getBrokersAsString();
        producerFactory=new DefaultKafkaProducerFactory<>(KafkaTestUtils.producerProps(broker));
        producer=new KafkaTemplate<>(producerFactory);
    }
    @AfterAll static void stop() { producerFactory.destroy();broker.destroy(); }
    @AfterEach void reset() { RestartLedger.clear(); }
    @Test void readOnlyPreflightAndRepeatedCurrentReceivers() throws Exception {
        try (var s = new Scope()) {
            assertNotNull(s.prepare(s.context)); s.unchanged();
            Object untouched = s.context.getBeanFactory().getMergedBeanDefinition("other");
            for (int seed : new int[]{20,30,40}) {
                next=seed; var old=(ConcurrentMessageListenerContainer<?,?>)s.registry.getListenerContainer("target");
                s.prepare(s.context).recreate();
                assertFalse(old.isRunning()); assertFalse(old.isChildRunning());
                assertEquals(seed,((Owner)s.context.getBeanFactory().getSingleton("owner")).seed);
                assertArrayEquals(new String[]{"owner"},s.context.getBeanFactory().getDependentBeans("helper"));
                assertSame(s.other,s.registry.getListenerContainer("other"));
                assertSame(untouched,s.context.getBeanFactory().getMergedBeanDefinition("other"));
                s.message("rf14-guard-target","save"+seed,"save"+seed+":"+seed);
                s.message("rf14-guard-other","save"+seed,"other:save"+seed);
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"ownerSupplier","helperSupplier","helperFactory","helperArguments","extraDependent","transitiveDependent","multipleContexts","ownerFactory","helperDependency","foreignBroker","nonRecordAck","transactionManager","factoryNonRecord","factoryTransaction"})
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
                case "foreignBroker" -> f.registerSingleton("foreignRabbitProcessor",Class.forName("org.springframework.amqp.rabbit.annotation.RabbitListenerAnnotationBeanPostProcessor").getConstructor().newInstance());
                case "extraDependent" -> {f.registerSingleton("holder",new Object());f.registerDependentBean("helper","holder");}
                case "transitiveDependent" -> {f.registerSingleton("holder",new Object());f.registerDependentBean("owner","holder");}
                case "nonRecordAck" -> s.target.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
                case "transactionManager" -> s.target.getContainerProperties().setTransactionManager(
                    (org.springframework.transaction.PlatformTransactionManager)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{org.springframework.transaction.PlatformTransactionManager.class},(proxy,method,args)->null));
                case "factoryNonRecord" -> s.context.getBean(ConcurrentKafkaListenerContainerFactory.class).getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
                case "factoryTransaction" -> s.context.getBean(ConcurrentKafkaListenerContainerFactory.class).getContainerProperties().setTransactionManager(
                    (org.springframework.transaction.PlatformTransactionManager)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{org.springframework.transaction.PlatformTransactionManager.class},(proxy,method,args)->null));
                case "multipleContexts" -> { }
                default -> fail(policy);
            }
            Object merged=f.getMergedBeanDefinition("owner");
            Object[] contexts=policy.equals("multipleContexts")?new Object[]{s.context,new Object()}:new Object[]{s.context};
            assertThrows(Exception.class,()->s.prepare(contexts));
            s.unchanged(); assertSame(merged,f.getMergedBeanDefinition("owner"));
            s.target.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
            s.target.getContainerProperties().setTransactionManager(null);
            s.context.getBean(ConcurrentKafkaListenerContainerFactory.class).getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
            s.context.getBean(ConcurrentKafkaListenerContainerFactory.class).getContainerProperties().setTransactionManager(null);
            s.message("rf14-guard-target",policy,policy+":10");
        }
    }
    @Test void ownedConsumerWithSecondProcessorRefusesBeforeDestruction() throws Exception {
        try(var s=new Scope()) {
            s.message("rf14-guard-target","warm-extra","warm-extra:10");
            assertNotNull(s.prepare(s.context),"owned callback must be relevant before the unsupported processor is added");
            var factory=s.context.getDefaultListableBeanFactory();
            factory.registerSingleton("extraKafkaProcessor",new KafkaListenerAnnotationBeanPostProcessor<>());
            assertEquals(2,s.context.getBeanNamesForType(KafkaListenerAnnotationBeanPostProcessor.class).length);
            Exception failure=assertThrows(Exception.class,()->s.prepare(s.context));
            assertTrue(com.onurkat.reclazz.ui.Failures.describe(failure).contains("multiple Kafka listener processors"));
            s.unchanged();
            s.message("rf14-guard-target","held-extra","held-extra:10");
            s.message("rf14-guard-other","held-extra","other:held-extra");
        }
    }

    @Test void parentHelperWithOnlyChildConsumerRefusesBeforeParentDestruction() throws Exception {
        next=10;received=new LinkedBlockingQueue<>();block=false;release=new CountDownLatch(1);
        try(var parent=new AnnotationConfigApplicationContext();var child=new AnnotationConfigApplicationContext()) {
            parent.registerBean("helper",Helper.class);parent.refresh();
            child.setParent(parent);child.register(Config.class);child.registerBean("owner",Owner.class);child.refresh();
            Object helper=parent.getBean("helper"),owner=child.getBean("owner");
            var target=(ConcurrentMessageListenerContainer<?,?>)child.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("target");
            assertArrayEquals(new String[0],parent.getBeanFactory().getDependentBeans("helper"));
            assertArrayEquals(new String[]{"owner"},child.getBeanFactory().getDependentBeans("helper"));
            assertThrows(Exception.class,()->SpringKafkaReloader.prepareDependencyRefresh(List.of(parent,child),parent,"helper"));
            assertSame(helper,parent.getBeanFactory().getSingleton("helper"));assertSame(owner,child.getBeanFactory().getSingleton("owner"));
            assertTrue(target.isChildRunning());assertTrue(target.isRunning());
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
            assertFalse(s.target.isRunning());assertNull(s.registry.getListenerContainer("target"));
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
        @KafkaListener(id="target", topics="rf14-guard-target")
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
            assertTrue(RestartLedger.digest().stream().anyMatch(x -> x.contains("direct Kafka")));
        }
    }

    @Test void registeredConsumerIsRetiredWhenLaterInitializationFails() throws Exception {
        try (var s = new Scope()) {
            var prepared = s.prepare(s.context); assertNotNull(prepared);
            var created = new java.util.concurrent.atomic.AtomicReference<ConcurrentMessageListenerContainer<?,?>>();
            s.context.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (name.equals("owner")) {
                        created.set((ConcurrentMessageListenerContainer<?,?>) s.registry.getListenerContainer("target"));
                        throw new IllegalStateException("injected failure after native callback registration");
                    }
                    return bean;
                }
            });
            next = 20; assertThrows(Exception.class, prepared::recreate);
            assertNotNull(created.get(), "failure must occur after a real replacement container was registered");
            assertNotSame(s.target, created.get());
            assertFalse(created.get().isChildRunning()); assertFalse(created.get().isRunning());
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
            producer.send("rf14-guard-target","held").get(10,TimeUnit.SECONDS);
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
