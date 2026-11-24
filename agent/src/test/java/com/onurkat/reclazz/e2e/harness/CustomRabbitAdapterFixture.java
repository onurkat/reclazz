/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e.harness;

public final class CustomRabbitAdapterFixture {
    private CustomRabbitAdapterFixture() { }

    public static String application(String source) {
        return source.replace("return factory;", """
                factory.setTransactionManager(new org.springframework.amqp.rabbit.transaction.RabbitTransactionManager(cf));
                factory.setTaskExecutor(new org.springframework.core.task.SimpleAsyncTaskExecutor("native-rabbit-worker-"));
                factory.setContainerCustomizer(container -> {
                    var delegate = (org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter) container.getMessageListener();
                    var wrapper = new org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter() {
                        @Override public void onMessage(org.springframework.amqp.core.Message message, com.rabbitmq.client.Channel channel) throws Exception {
                            boolean worker = Thread.currentThread().getName().startsWith("native-rabbit-worker-");
                            boolean transaction = org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(cf);
                            String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                            System.out.println("CUSTOM:" + body + ":" + worker + ":" + transaction);
                            super.onMessage(message, channel);
                        }
                    };
                    try {
                        var getter = org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter.class.getDeclaredMethod("getHandlerAdapter");
                        getter.setAccessible(true);
                        wrapper.setHandlerAdapter((org.springframework.amqp.rabbit.listener.adapter.HandlerAdapter) getter.invoke(delegate));
                    } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                    container.setMessageListener(wrapper);
                });
                return factory;""");
    }
}
