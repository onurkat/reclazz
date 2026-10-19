/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import java.io.File; import java.nio.file.Path; import java.util.*; import java.util.concurrent.TimeUnit;
import com.onurkat.reclazz.e2e.harness.RabbitBroker; import org.springframework.amqp.rabbit.core.*;
class RabbitConsumerCascadeReloadTest extends RabbitConsumerCascadeContract {
 Backend open()throws Exception{return new Env();}
 static class Env implements Backend {
final RabbitBroker broker;final RabbitTemplate producer; final RabbitAdmin admin;
        Env()throws Exception{broker=RabbitBroker.start();producer=new RabbitTemplate(broker.connectionFactory());admin=new RabbitAdmin(broker.connectionFactory());}
        public String[] args(){return new String[]{"-Dtest.rabbit.port="+broker.port(),"-Dtest.rabbit.password="+broker.password()};}
        public void prepare(String q,String other){for(String name:List.of(q,other))admin.declareQueue(new org.springframework.amqp.core.Queue(name,true,false,false));}
        public void send(String q,String value){producer.convertAndSend(q,value);}
        public void close()throws Exception{broker.close();}
        public String annotation(String id,String q){return "@org.springframework.amqp.rabbit.annotation.RabbitListener(id=\""+id+"\",queues=\""+q+"\")";}
public String classpath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka") && !n.startsWith("spring-jms")
                            || n.startsWith("amqp-client-") || n.startsWith("slf4j-api-") || n.startsWith("spring-retry-"));
                }).toList());
    }

 public String app(){return APP;}
}
 private static final String APP="""
            package app;
            import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
            import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.context.annotation.ComponentScan("app")
            @org.springframework.context.annotation.PropertySource("classpath:application.properties")
            @org.springframework.amqp.rabbit.annotation.EnableRabbit
            public class App {
                @org.springframework.context.annotation.Bean
                public org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnectionFactory() {
                    var cf = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("test.rabbit.port")));
                    cf.setUsername("reclazz"); cf.setPassword(System.getProperty("test.rabbit.password")); return cf;
                }
                @org.springframework.context.annotation.Bean
                public org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(org.springframework.amqp.rabbit.connection.CachingConnectionFactory cf) {
                    var factory = new org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory();
                    factory.setConnectionFactory(cf);
                    return factory;
                }
                static org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry registry;
            static final java.util.concurrent.atomic.AtomicInteger births=new java.util.concurrent.atomic.AtomicInteger();
            static final java.util.concurrent.atomic.AtomicInteger bornWithOldActive=new java.util.concurrent.atomic.AtomicInteger();
            // Read-only observation of the committed complete-worker tracker; never retire from an observer.
            static int workers(MessageListenerContainer container) {
                try {
                    Class<?> bridge=Class.forName("com.onurkat.reclazz.bootstrap.RabbitConsumerBridge",false,null);
                    var field=bridge.getDeclaredField("containers");field.setAccessible(true);
                    var states=(java.util.Map<?,?>)field.get(null);
                    synchronized(states) {
                        Object state=states.get(container);if(state==null)return 0;
                        var count=state.getClass().getDeclaredField("workers");count.setAccessible(true);return count.getInt(state);
                    }
                }catch(ClassNotFoundException nativeOnly){return 0;}
                catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            }
            static volatile java.util.List<MessageListenerContainer> retiring=java.util.List.of();
            static boolean active(MessageListenerContainer c){return c!=null && (((SimpleMessageListenerContainer)c).isActive() || c.isRunning() || ((SimpleMessageListenerContainer)c).getActiveConsumerCount()!=0 || workers(c)!=0);}
            static boolean close(org.springframework.context.annotation.AnnotationConfigApplicationContext ctx,java.util.Set<MessageListenerContainer> seen)throws Exception {
                seen.addAll(registry.getListenerContainers());ctx.close();
                long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while(seen.stream().anyMatch(App::active)&&System.nanoTime()<end)Thread.sleep(10);
                return seen.stream().noneMatch(App::active);
            }
            static boolean ready(MessageListenerContainer c){return c!=null && (((org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer)c).getActiveConsumerCount()>0);}
            public static int born(){int id=births.incrementAndGet();boolean active=retiring.stream().anyMatch(App::active)||(registry!=null&&active(registry.getListenerContainer("target")));if(active)bornWithOldActive.incrementAndGet();System.out.println("BORN:"+id+":oldActive="+active);return id;}
            public static void main(String[] args)throws Exception {
                var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                registry=ctx.getBean(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class);var other=registry.getListenerContainer("other");
                var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<MessageListenerContainer,Boolean>());
                var snapshots=new java.util.ArrayList<MessageListenerContainer>();
                var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/",e->{String text;
                    try{String path=e.getRequestURI().getPath();
                        if(path.equals("/snapshot")){snapshots.clear();var c=registry.getListenerContainer("target");if(c!=null){snapshots.add(c);seen.add(c);}retiring=java.util.List.copyOf(snapshots);text="saved";}
                        else if(path.equals("/close")){text="allStopped="+close(ctx,seen);}
                        else {var helper=(Helper)ctx.getBeanFactory().getSingleton("helper");
                            text="targetReady="+ready(registry.getListenerContainer("target"))+";otherReady="+ready(registry.getListenerContainer("other"))+";oldRunning="+snapshots.stream().filter(App::active).count()+";helperSeed="+(helper==null?"missing":helper.seed)+";liveOwner="+(ctx.getBeanFactory().getSingleton("listener")!=null)+";registryCount="+registry.getListenerContainerIds().size()+";otherSame="+(other==registry.getListenerContainer("other"))+";bornWithOldActive="+bornWithOldActive.get()+";helperDependents="+java.util.Arrays.toString(ctx.getBeanFactory().getDependentBeans("helper"))+";listenerDependencies="+java.util.Arrays.toString(ctx.getBeanFactory().getDependenciesForBean("listener"))+";ackMode="+((SimpleMessageListenerContainer)registry.getListenerContainer("other")).getAcknowledgeMode()+";channelTransacted="+((SimpleMessageListenerContainer)registry.getListenerContainer("other")).isChannelTransacted()+";trackedWorkers="+workers(registry.getListenerContainer("target"))+";liveOwn="+(ctx.getBeanFactory().getSingleton("listener")==null?"missing":((Listener)ctx.getBeanFactory().getSingleton("listener")).own)+";liveSeed="+(ctx.getBeanFactory().getSingleton("listener")==null?"missing":((Listener)ctx.getBeanFactory().getSingleton("listener")).seed)+";";
                        }
                    }catch(Throwable failure){text="ERROR:"+failure.getClass().getName()+":"+failure.getMessage();}
                    byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
                });server.start();System.out.println("PORT="+server.getAddress().getPort());
            }
        }
""";
}
