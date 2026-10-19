/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.bootstrap.RabbitConsumerBridge;
import com.onurkat.reclazz.transform.RabbitConsumerTransformer;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class SpringRabbitDependencyRefreshTest {
    static com.onurkat.reclazz.e2e.harness.RabbitBroker broker;
    static RabbitTemplate producer;
    static int next;
    static BlockingQueue<String> received;
    static CountDownLatch entered, release;
    static boolean block;
    public static class Helper { final int seed = next; }
    public static class Owner {
        final int seed;
        public Owner(Helper helper) { seed = helper.seed; }
        @RabbitListener(id="target", queues="rf14-guard-target")
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
        @RabbitListener(id="other", queues="rf14-guard-other")
        public void receive(String value) { received.add("other:" + value); }
    }
    @Configuration(proxyBeanMethods=false) @EnableRabbit
    static class Config {
        @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory() {
            var f=new SimpleRabbitListenerContainerFactory();f.setConnectionFactory(broker.connectionFactory());return f;
        }
    }
    static class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final RabbitListenerEndpointRegistry registry;
        final SimpleMessageListenerContainer target, other;
        final Object helper, owner;
        final List<SimpleMessageListenerContainer> initialWorkers=new ArrayList<>();
        void captureWorkers(MessageListenerContainer container,List<SimpleMessageListenerContainer> workers) {
            assertTrue(container instanceof SimpleMessageListenerContainer);workers.add((SimpleMessageListenerContainer)container);
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
            registry = context.getBean(RabbitListenerEndpointRegistry.class);
            target = (SimpleMessageListenerContainer) registry.getListenerContainer("target");
            other = (SimpleMessageListenerContainer) registry.getListenerContainer("other");
            helper = context.getBean("helper"); owner = context.getBean("owner");
            assertEquals(org.springframework.amqp.core.AcknowledgeMode.AUTO,target.getAcknowledgeMode());assertFalse(target.isChannelTransacted());ready(target); ready(other); captureWorkers(target,initialWorkers); captureWorkers(other,initialWorkers);
        }
        SpringRabbitReloader.DependencyRefresh prepare(Object... contexts) throws Exception {
            return SpringRabbitReloader.prepareDependencyRefresh(List.of(contexts), context, "helper");
        }
        void unchanged() {
            var f = context.getBeanFactory();
            assertSame(helper, f.getSingleton("helper")); assertSame(owner, f.getSingleton("owner"));
            assertSame(target, registry.getListenerContainer("target")); assertTrue(target.isActive()); assertTrue(target.isRunning());
            assertSame(other, registry.getListenerContainer("other")); assertTrue(other.isActive());
        }
        void message(String queue, String value, String expected) throws Exception {
            producer.convertAndSend(queue,value);
            assertEquals(expected, received.poll(5, TimeUnit.SECONDS));
        }
        @Override public void close() throws Exception {
            release.countDown(); var containers=new ArrayList<>(registry.getListenerContainers());
            containers.add(target); containers.add(other);
            var workers=new ArrayList<>(initialWorkers);for(var c: containers)captureWorkers(c,workers);
            context.close();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            for(var worker: workers){while(tracked(worker)!=0&&System.nanoTime()<end)Thread.sleep(10);assertEquals(0,tracked(worker),"native Rabbit worker must really terminate");}
            assertTrue(containers.stream().noneMatch(MessageListenerContainer::isRunning));
        }
    }
    private static int tracked(Object c)throws Exception {
        var field=RabbitConsumerBridge.class.getDeclaredField("containers");field.setAccessible(true);var states=(Map<?,?>)field.get(null);
        synchronized(states){Object state=states.get(c);if(state==null)return 0;var count=state.getClass().getDeclaredField("workers");count.setAccessible(true);return count.getInt(state);}
    }
    private static void ready(MessageListenerContainer container)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<end){if(((SimpleMessageListenerContainer)container).getActiveConsumerCount()>0){assertTrue(tracked(container)>0);return;}Thread.sleep(25);}
        fail("native Rabbit consumer missing");
    }
    @BeforeAll static void start()throws Exception {
        net.bytebuddy.agent.ByteBuddyAgent.install().addTransformer(new RabbitConsumerTransformer());
        Class<?> worker=Class.forName(RabbitConsumerTransformer.TARGET.replace('/','.'));assertTrue(RabbitConsumerBridge.isHooked(worker));
        broker=com.onurkat.reclazz.e2e.harness.RabbitBroker.start();producer=new RabbitTemplate(broker.connectionFactory());
        var admin=new RabbitAdmin(broker.connectionFactory());
        for(String q:List.of("rf14-guard-target","rf14-guard-other"))admin.declareQueue(new org.springframework.amqp.core.Queue(q,true));
    }
    @BeforeEach void cleanQueues(){var admin=new RabbitAdmin(broker.connectionFactory());for(String q:List.of("rf14-guard-target","rf14-guard-other"))admin.purgeQueue(q,false);}
    @AfterAll static void stop()throws Exception {broker.close();}
    @AfterEach void reset() { RestartLedger.clear(); }
    @Test void readOnlyPreflightAndRepeatedCurrentReceivers() throws Exception {
        try (var s = new Scope()) {
            assertNotNull(s.prepare(s.context)); s.unchanged();
            Object untouched = s.context.getBeanFactory().getMergedBeanDefinition("other");
            for (int seed : new int[]{20,30,40}) {
                next=seed; var old=(SimpleMessageListenerContainer)s.registry.getListenerContainer("target");
                s.prepare(s.context).recreate();
                assertFalse(old.isRunning()); assertEquals(0,tracked(old));
                assertEquals(seed,((Owner)s.context.getBeanFactory().getSingleton("owner")).seed);
                assertArrayEquals(new String[]{"owner"},s.context.getBeanFactory().getDependentBeans("helper"));
                assertSame(s.other,s.registry.getListenerContainer("other"));
                assertSame(untouched,s.context.getBeanFactory().getMergedBeanDefinition("other"));
                s.message("rf14-guard-target","save"+seed,"save"+seed+":"+seed);
                s.message("rf14-guard-other","save"+seed,"other:save"+seed);
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"ownerSupplier","helperSupplier","helperFactory","helperArguments","extraDependent","transitiveDependent","multipleContexts","ownerFactory","helperDependency","foreignBroker","nonAutoAck","transacted","factoryNonAuto","factoryTransacted"})
    void unsupportedClosureRefusesBeforeAnyConsumerOrSingletonChange(String policy)throws Exception {
        try(var s=new Scope()) {
            var f=s.context.getDefaultListableBeanFactory();var root=(AbstractBeanDefinition)s.context.getBeanDefinition("helper");var owner=(AbstractBeanDefinition)s.context.getBeanDefinition("owner");
            var factory=s.context.getBean(SimpleRabbitListenerContainerFactory.class);
            switch(policy){
                case "ownerSupplier" -> owner.setInstanceSupplier(()->s.owner);
                case "helperSupplier" -> root.setInstanceSupplier(()->s.helper);
                case "helperFactory" -> root.setFactoryMethodName("make");
                case "helperArguments" -> root.getConstructorArgumentValues().addIndexedArgumentValue(0,10);
                case "ownerFactory" -> owner.setFactoryMethodName("make");
                case "helperDependency" -> {f.registerSingleton("prior",new Object());f.registerDependentBean("prior","helper");}
                case "foreignBroker" -> f.registerSingleton("foreignKafkaProcessor",Class.forName("org.springframework.kafka.annotation.KafkaListenerAnnotationBeanPostProcessor").getConstructor().newInstance());
                case "extraDependent" -> {f.registerSingleton("holder",new Object());f.registerDependentBean("helper","holder");}
                case "transitiveDependent" -> {f.registerSingleton("holder",new Object());f.registerDependentBean("owner","holder");}
                case "nonAutoAck" -> s.target.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
                case "transacted" -> s.target.setChannelTransacted(true);
                case "factoryNonAuto" -> factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
                case "factoryTransacted" -> factory.setChannelTransacted(true);
                case "multipleContexts" -> { }
                default -> fail(policy);
            }
            Object merged=f.getMergedBeanDefinition("owner");Object[] contexts=policy.equals("multipleContexts")?new Object[]{s.context,new Object()}:new Object[]{s.context};
            assertThrows(Exception.class,()->s.prepare(contexts));s.unchanged();assertSame(merged,f.getMergedBeanDefinition("owner"));
            s.target.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.AUTO);s.target.setChannelTransacted(false);factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.AUTO);factory.setChannelTransacted(false);
            s.message("rf14-guard-target",policy,policy+":10");s.message("rf14-guard-other",policy,"other:"+policy);
        }
    }
    @Test void ownedConsumerWithSecondProcessorRefusesBeforeDestruction() throws Exception {
        try(var s=new Scope()) {
            s.message("rf14-guard-target","warm-extra","warm-extra:10");
            assertNotNull(s.prepare(s.context),"owned callback must be relevant before the unsupported processor is added");
            var factory=s.context.getDefaultListableBeanFactory();
            factory.registerSingleton("extraRabbitProcessor",new RabbitListenerAnnotationBeanPostProcessor());
            assertEquals(2,s.context.getBeanNamesForType(RabbitListenerAnnotationBeanPostProcessor.class).length);
            Exception failure=assertThrows(Exception.class,()->s.prepare(s.context));
            assertTrue(com.onurkat.reclazz.ui.Failures.describe(failure).contains("multiple Rabbit listener processors"));
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
            var target=(SimpleMessageListenerContainer)child.getBean(RabbitListenerEndpointRegistry.class).getListenerContainer("target");
            assertArrayEquals(new String[0],parent.getBeanFactory().getDependentBeans("helper"));
            assertArrayEquals(new String[]{"owner"},child.getBeanFactory().getDependentBeans("helper"));
            assertThrows(Exception.class,()->SpringRabbitReloader.prepareDependencyRefresh(List.of(parent,child),parent,"helper"));
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
        @RabbitListener(id="target", queues="rf14-guard-target")
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
            assertTrue(RestartLedger.digest().stream().anyMatch(x -> x.contains("direct Rabbit")));
        }
    }

    @Test void registeredConsumerIsRetiredWhenLaterInitializationFails() throws Exception {
        try (var s = new Scope()) {
            var prepared = s.prepare(s.context); assertNotNull(prepared);
            var created = new java.util.concurrent.atomic.AtomicReference<SimpleMessageListenerContainer>();
            s.context.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (name.equals("owner")) {
                        created.set((SimpleMessageListenerContainer) s.registry.getListenerContainer("target"));
                        throw new IllegalStateException("injected failure after native callback registration");
                    }
                    return bean;
                }
            });
            next = 20; assertThrows(Exception.class, prepared::recreate);
            assertNotNull(created.get(), "failure must occur after a real replacement container was registered");
            assertNotSame(s.target, created.get());
            assertEquals(0,tracked(created.get())); assertFalse(created.get().isRunning());
            assertNull(s.registry.getListenerContainer("target"));
            assertNull(s.context.getBeanFactory().getSingleton("owner"));
            assertEquals(20, ((Helper) s.context.getBeanFactory().getSingleton("helper")).seed);
            assertSame(s.other, s.registry.getListenerContainer("other")); assertTrue(s.other.isRunning());
            assertTrue(RestartLedger.digest().stream().anyMatch(x -> x.contains("partially applied")));
            s.message("rf14-guard-other", "registered-then-failed", "other:registered-then-failed");
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void blockedShutdownNeverRecreatesHelperAndRefusesConcurrentRetirement(boolean closeConnection)throws Exception {
        try(var s=new Scope()) {
            var prepared=s.prepare(s.context);assertNotNull(prepared);block=true;
            s.target.setShutdownTimeout(25);
            // Use the existing stop coordinator's package-private bounded-time constructor for research.
            var field=prepared.getClass().getDeclaredField("coordinator");field.setAccessible(true);field.set(prepared,new SpringRabbitReloader(null,200));
            producer.convertAndSend("rf14-guard-target","held");assertTrue(entered.await(5,TimeUnit.SECONDS));
            if(closeConnection){broker.connectionFactory().createConnection().getDelegate().close();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(s.target.getActiveConsumerCount()!=0&&System.nanoTime()<end)Thread.sleep(10);assertEquals(0,s.target.getActiveConsumerCount());}
            assertTrue(tracked(s.target)>0,"actual callback still owns its native worker");
            assertThrows(Exception.class,prepared::recreate);
            assertSame(s.helper,s.context.getBeanFactory().getSingleton("helper"));assertSame(s.owner,s.context.getBeanFactory().getSingleton("owner"));assertSame(s.target,s.registry.getListenerContainer("target"));
            assertThrows(Exception.class,()->s.prepare(s.context));assertSame(s.other,s.registry.getListenerContainer("other"));
            assertTrue(RestartLedger.digest().stream().anyMatch(x->x.contains("shutdown incomplete")));
            release.countDown();assertTrue(RabbitConsumerBridge.awaitStopped(s.target,System.nanoTime()+TimeUnit.SECONDS.toNanos(10)));
            assertEquals("held:10",received.poll(5,TimeUnit.SECONDS));
        }
    }
}
