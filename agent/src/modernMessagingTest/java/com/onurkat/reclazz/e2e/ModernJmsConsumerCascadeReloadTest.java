/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import java.io.File; import java.nio.file.Path; import java.util.*; import java.util.concurrent.TimeUnit;
import org.apache.activemq.broker.BrokerService; import org.apache.activemq.ActiveMQConnectionFactory; import org.springframework.jms.core.JmsTemplate;
class ModernJmsConsumerCascadeReloadTest extends JmsConsumerCascadeContract {
 Backend open()throws Exception{return new Env();}
 static class Env implements Backend {
final BrokerService broker=new BrokerService(); final String url;final JmsTemplate producer;
        Env()throws Exception{broker.setBrokerName("rf14-"+UUID.randomUUID());broker.setPersistent(false);broker.setUseJmx(false);broker.setUseShutdownHook(false);var connector=broker.addConnector("tcp://127.0.0.1:0");broker.start();broker.waitUntilStarted();url=connector.getPublishableConnectString();producer=new JmsTemplate(new ActiveMQConnectionFactory(url));}
        public String[] args(){return new String[]{"-Dtest.brokers="+url};}
        public void prepare(String q,String other){}
        public void send(String q,String value){producer.convertAndSend(q,value);}
        public void close()throws Exception{broker.stop();broker.waitUntilStopped();}
        public String annotation(String id,String q){return "@org.springframework.jms.annotation.JmsListener(id=\""+id+"\",destination=\""+q+"\")";}
public String classpath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String n = Path.of(s).getFileName().toString();
                    return n.endsWith(".jar") && (n.startsWith("spring-") && !n.startsWith("spring-kafka")
                            || n.startsWith("activemq-client-") || n.startsWith("slf4j-api-") || n.startsWith("micrometer-")
                            || n.startsWith("jakarta.jms-api-") || n.startsWith("hawtbuf-"));
                }).toList());
    }

 public String app(){return APP;}
}
 private static final String APP="""
            package app;
            import org.springframework.jms.listener.DefaultMessageListenerContainer;
            import org.springframework.jms.listener.MessageListenerContainer;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.context.annotation.ComponentScan("app")
            @org.springframework.context.annotation.PropertySource("classpath:application.properties")
            @org.springframework.jms.annotation.EnableJms
            public class App {
                @org.springframework.context.annotation.Bean
                public org.springframework.jms.config.DefaultJmsListenerContainerFactory jmsListenerContainerFactory() {
                    var factory = new org.springframework.jms.config.DefaultJmsListenerContainerFactory();
                    factory.setConnectionFactory(new org.apache.activemq.ActiveMQConnectionFactory(System.getProperty("test.brokers")));
                    factory.setReceiveTimeout(100L); factory.setSessionTransacted(true);
                    return factory;
                }
                static org.springframework.jms.config.JmsListenerEndpointRegistry registry;
            static final java.util.concurrent.atomic.AtomicInteger births=new java.util.concurrent.atomic.AtomicInteger();
            static final java.util.concurrent.atomic.AtomicInteger bornWithOldActive=new java.util.concurrent.atomic.AtomicInteger();
            static boolean active(MessageListenerContainer c){return c!=null && (((org.springframework.jms.listener.DefaultMessageListenerContainer)c).isActive() || c.isRunning());}
            static boolean ready(MessageListenerContainer c){return c!=null && (((org.springframework.jms.listener.DefaultMessageListenerContainer)c).isRegisteredWithDestination());}
            public static int born(){int id=births.incrementAndGet();boolean active=registry!=null&&active(registry.getListenerContainer("target"));if(active)bornWithOldActive.incrementAndGet();System.out.println("BORN:"+id+":oldActive="+active);return id;}
            public static void main(String[] args)throws Exception {
                var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                registry=ctx.getBean(org.springframework.jms.config.JmsListenerEndpointRegistry.class);var other=registry.getListenerContainer("other");
                var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<MessageListenerContainer,Boolean>());
                var snapshots=new java.util.ArrayList<MessageListenerContainer>();
                var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/",e->{String text;
                    try{String path=e.getRequestURI().getPath();
                        if(path.equals("/snapshot")){snapshots.clear();var c=registry.getListenerContainer("target");if(c!=null){snapshots.add(c);seen.add(c);}text="saved";}
                        else if(path.equals("/close")){seen.addAll(registry.getListenerContainers());ctx.close();text="allStopped="+seen.stream().noneMatch(App::active);}
                        else {var helper=(Helper)ctx.getBeanFactory().getSingleton("helper");
                            text="targetReady="+ready(registry.getListenerContainer("target"))+";otherReady="+ready(registry.getListenerContainer("other"))+";oldRunning="+snapshots.stream().filter(App::active).count()+";helperSeed="+(helper==null?"missing":helper.seed)+";liveOwner="+(ctx.getBeanFactory().getSingleton("listener")!=null)+";registryCount="+registry.getListenerContainerIds().size()+";otherSame="+(other==registry.getListenerContainer("other"))+";bornWithOldActive="+bornWithOldActive.get()+";helperDependents="+java.util.Arrays.toString(ctx.getBeanFactory().getDependentBeans("helper"))+";listenerDependencies="+java.util.Arrays.toString(ctx.getBeanFactory().getDependenciesForBean("listener"))+";liveSeed="+(ctx.getBeanFactory().getSingleton("listener")==null?"missing":((Listener)ctx.getBeanFactory().getSingleton("listener")).seed)+";liveOwn="+(ctx.getBeanFactory().getSingleton("listener")==null?"missing":((Listener)ctx.getBeanFactory().getSingleton("listener")).own)+";";
                        }
                    }catch(Throwable failure){text="ERROR:"+failure.getClass().getName()+":"+failure.getMessage();}
                    byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
                });server.start();System.out.println("PORT="+server.getAddress().getPort());
            }
        }
""";
}
