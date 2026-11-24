/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.MethodRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * A second broker in the same context as a dependency refresh under test, on
 * Spring 5. Its containers never start, so no broker is needed; the refresh
 * must leave them alone, or refuse when the refreshed owner is one of their
 * receivers.
 */
public final class ForeignBrokers {
    private ForeignBrokers() { }

    @Configuration(proxyBeanMethods = false) @EnableRabbit
    public static class Rabbit {
        @Bean public CachingConnectionFactory rabbitConnectionFactory() { return new CachingConnectionFactory("127.0.0.1", 1); }
        @Bean public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(CachingConnectionFactory cf) {
            var f = new SimpleRabbitListenerContainerFactory();
            f.setConnectionFactory(cf); f.setAutoStartup(false); return f;
        }
    }
    public static class RabbitNeighbour {
        @RabbitListener(id = "neighbour-rabbit", queues = "neighbour") public void receive(String value) { }
    }

    @Configuration(proxyBeanMethods = false) @EnableKafka
    public static class Kafka {
        @Bean public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
            var f = new ConcurrentKafkaListenerContainerFactory<String, String>();
            f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.<String, Object>of("bootstrap.servers", "127.0.0.1:1",
                    "key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class,
                    "value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class)));
            f.setAutoStartup(false); return f;
        }
    }
    public static class KafkaNeighbour {
        @KafkaListener(id = "neighbour-kafka", topics = "neighbour", groupId = "neighbour") public void receive(String value) { }
    }

    /** Before refresh: the broker's infrastructure plus one unrelated listener bean. */
    public static void register(AnnotationConfigApplicationContext context, String broker) {
        switch (broker) {
            case "rabbit" -> { context.register(Rabbit.class); context.registerBean("neighbourRabbit", RabbitNeighbour.class); }
            case "kafka" -> { context.register(Kafka.class); context.registerBean("neighbourKafka", KafkaNeighbour.class); }
            default -> throw new IllegalArgumentException(broker);
        }
    }

    /** The unrelated listener's container, to check that it kept its identity. */
    public static Object neighbour(ApplicationContext context, String broker) {
        return switch (broker) {
            case "rabbit" -> context.getBean(RabbitListenerEndpointRegistry.class).getListenerContainer("neighbour-rabbit");
            case "kafka" -> context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("neighbour-kafka");
            default -> throw new IllegalArgumentException(broker);
        };
    }

    /** After refresh: makes {@code bean} the receiver of a never-started container on that broker. */
    public static void listenOn(ApplicationContext context, String broker, Object bean, Method method) throws Exception {
        var handler = new DefaultMessageHandlerMethodFactory(); handler.afterPropertiesSet();
        switch (broker) {
            case "rabbit" -> {
                var endpoint = new MethodRabbitListenerEndpoint();
                endpoint.setId("owner-rabbit"); endpoint.setQueueNames("neighbour"); endpoint.setBean(bean); endpoint.setMethod(method);
                endpoint.setMessageHandlerMethodFactory(handler);
                context.getBean(RabbitListenerEndpointRegistry.class).registerListenerContainer(endpoint,
                        context.getBean(SimpleRabbitListenerContainerFactory.class), false);
            }
            case "kafka" -> {
                var endpoint = new MethodKafkaListenerEndpoint<String, String>();
                endpoint.setId("owner-kafka"); endpoint.setGroupId("neighbour"); endpoint.setTopics("neighbour");
                endpoint.setBean(bean); endpoint.setMethod(method); endpoint.setMessageHandlerMethodFactory(handler);
                context.getBean(KafkaListenerEndpointRegistry.class).registerListenerContainer(endpoint,
                        context.getBean(ConcurrentKafkaListenerContainerFactory.class), false);
            }
            default -> throw new IllegalArgumentException(broker);
        }
    }
}
