/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import java.io.File; import java.nio.file.Path; import java.util.*; import java.util.concurrent.TimeUnit;
import org.springframework.kafka.test.EmbeddedKafkaBroker; import org.springframework.kafka.test.utils.KafkaTestUtils; import org.springframework.kafka.core.*;
class KafkaConsumerCascadeReloadTest extends KafkaConsumerCascadeContract {
 Backend open()throws Exception{return new Env();}
 static class Env implements Backend {
final EmbeddedKafkaBroker broker=new EmbeddedKafkaBroker(1,false,1);
        final DefaultKafkaProducerFactory<Integer,String> factory;final KafkaTemplate<Integer,String> producer;
        Env(){broker.afterPropertiesSet();factory=new DefaultKafkaProducerFactory<>(KafkaTestUtils.producerProps(broker));producer=new KafkaTemplate<>(factory);}
        public String[] args(){return new String[]{"-Dtest.brokers="+broker.getBrokersAsString()};}
        public void prepare(String q,String other){broker.addTopics(q,other);}
        public void send(String q,String value)throws Exception{producer.send(q,value).get(10,TimeUnit.SECONDS);}
        public void close(){factory.destroy();broker.destroy();}
        public String annotation(String id,String q){return "@org.springframework.kafka.annotation.KafkaListener(id=\""+id+"\",topics=\""+q+"\")";}
public String classpath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka-test")
                            || n.startsWith("kafka-clients-") || n.startsWith("slf4j-api-") || n.startsWith("lz4-java-")
                            || n.startsWith("snappy-java-") || n.startsWith("zstd-jni-"));
                }).toList());
    }

 public String app(boolean nativeDefaultBatch){
    // No setAckMode call for this case: exercise the actual native default.
    return nativeDefaultBatch ? APP.replace("factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);", "") : APP;
 }
}
 private static final String APP="""
            package app;
            import org.springframework.kafka.listener.MessageListenerContainer;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.context.annotation.ComponentScan("app")
            @org.springframework.context.annotation.PropertySource("classpath:application.properties")
            @org.springframework.kafka.annotation.EnableKafka
            public class App {
                @org.springframework.context.annotation.Bean
                public org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String> kafkaListenerContainerFactory() {
                    var props = new java.util.HashMap<String,Object>();
                    props.put("bootstrap.servers", System.getProperty("test.brokers"));
                    props.put("key.deserializer", org.apache.kafka.common.serialization.IntegerDeserializer.class);
                    props.put("value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
                    props.put("auto.offset.reset", "earliest"); props.put("enable.auto.commit", false);
                    var factory = new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String>();
                    factory.setConsumerFactory(new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(props));
                    factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
                    factory.getContainerProperties().setPollTimeout(100);
                    return factory;
                }
                static org.springframework.kafka.config.KafkaListenerEndpointRegistry registry;
            static final java.util.concurrent.atomic.AtomicInteger births=new java.util.concurrent.atomic.AtomicInteger();
            static final java.util.concurrent.atomic.AtomicInteger bornWithOldActive=new java.util.concurrent.atomic.AtomicInteger();
            static volatile java.util.List<MessageListenerContainer> retiring=java.util.List.of();
            static final java.util.Map<MessageListenerContainer,java.util.List<java.util.concurrent.Future<?>>> workers=java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
            static void capture(MessageListenerContainer c)throws Exception {
                if(c==null)return;
                var all=workers.computeIfAbsent(c,k->new java.util.ArrayList<>());
                for(var child:((org.springframework.kafka.listener.ConcurrentMessageListenerContainer<?,?>)c).getContainers()) {
                    var field=child.getClass().getDeclaredField("listenerConsumerFuture");field.setAccessible(true);
                    Object value=field.get(child);
                    if(!(value instanceof java.util.concurrent.Future<?>))throw new IllegalStateException("native completion future missing");
                    if(!all.contains(value))all.add((java.util.concurrent.Future<?>)value);
                }
            }
            static boolean active(MessageListenerContainer c){return c!=null && (c.isRunning() || workers.getOrDefault(c,java.util.List.of()).stream().anyMatch(f->!f.isDone()));}
            static boolean ready(MessageListenerContainer c){return c!=null && (c.getAssignedPartitions()!=null && !c.getAssignedPartitions().isEmpty());}
            public static int born(){int id=births.incrementAndGet();boolean active=retiring.stream().anyMatch(App::active) || (registry!=null&&active(registry.getListenerContainer("target")));if(active)bornWithOldActive.incrementAndGet();System.out.println("BORN:"+id+":oldActive="+active);return id;}
            public static void main(String[] args)throws Exception {
                var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                registry=ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);var other=registry.getListenerContainer("other");
                var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<MessageListenerContainer,Boolean>());
                var snapshots=new java.util.ArrayList<MessageListenerContainer>();
                var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/",e->{String text;
                    try{String path=e.getRequestURI().getPath();
                        if(path.equals("/snapshot")){snapshots.clear();retiring=java.util.List.of();var c=registry.getListenerContainer("target");if(c!=null){capture(c);snapshots.add(c);retiring=java.util.List.of(c);seen.add(c);}text="saved";}
                        else if(path.equals("/close")){seen.addAll(registry.getListenerContainers());for(var c:seen)capture(c);ctx.close();for(var values:workers.values())for(var future:values)future.get(20,java.util.concurrent.TimeUnit.SECONDS);text="allStopped="+seen.stream().noneMatch(App::active);}
                        else {for(var c:registry.getListenerContainers())capture(c);var helper=(Helper)ctx.getBeanFactory().getSingleton("helper");
                            text="targetAck="+registry.getListenerContainer("target").getContainerProperties().getAckMode()+";factoryAck="+ctx.getBean(org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class).getContainerProperties().getAckMode()+";targetReady="+ready(registry.getListenerContainer("target"))+";otherReady="+ready(registry.getListenerContainer("other"))+";oldRunning="+snapshots.stream().filter(App::active).count()+";helperSeed="+(helper==null?"missing":helper.seed)+";liveOwner="+(ctx.getBeanFactory().getSingleton("listener")!=null)+";registryCount="+registry.getListenerContainerIds().size()+";otherSame="+(other==registry.getListenerContainer("other"))+";bornWithOldActive="+bornWithOldActive.get()+";helperDependents="+java.util.Arrays.toString(ctx.getBeanFactory().getDependentBeans("helper"))+";listenerDependencies="+java.util.Arrays.toString(ctx.getBeanFactory().getDependenciesForBean("listener"))+";liveSeed="+(ctx.getBeanFactory().getSingleton("listener")==null?"missing":((Listener)ctx.getBeanFactory().getSingleton("listener")).seed)+";liveOwn="+(ctx.getBeanFactory().getSingleton("listener")==null?"missing":((Listener)ctx.getBeanFactory().getSingleton("listener")).own)+";";
                        }
                    }catch(Throwable failure){text="ERROR:"+failure.getClass().getName()+":"+failure.getMessage();}
                    byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
                });server.start();System.out.println("PORT="+server.getAddress().getPort());
            }
        }
""";
}
