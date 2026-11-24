/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e.harness;

/** Compiled in the fixture JVM against either javax.jms or jakarta.jms. */
public final class CustomJmsAdapterFixture {
    private CustomJmsAdapterFixture() { }

    public static String application(String source, String api) {
        return source.replace("@org.springframework.jms.annotation.EnableJms", "")
                .replace("public class App {", "public class App {" + """
                    @org.springframework.context.annotation.Bean(name=org.springframework.jms.config.JmsListenerConfigUtils.JMS_LISTENER_ENDPOINT_REGISTRY_BEAN_NAME)
                    public static org.springframework.jms.config.JmsListenerEndpointRegistry jmsRegistry() {
                        return new org.springframework.jms.config.JmsListenerEndpointRegistry();
                    }
                    @org.springframework.context.annotation.Bean(name=org.springframework.jms.config.JmsListenerConfigUtils.JMS_LISTENER_ANNOTATION_PROCESSOR_BEAN_NAME)
                    public static org.springframework.jms.annotation.JmsListenerAnnotationBeanPostProcessor processor() {
                        return new org.springframework.jms.annotation.JmsListenerAnnotationBeanPostProcessor() {
                            @Override protected org.springframework.jms.config.MethodJmsListenerEndpoint createMethodJmsListenerEndpoint() {
                                return new org.springframework.jms.config.MethodJmsListenerEndpoint() {
                                    @Override protected org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter createMessageListenerInstance() {
                                        return new org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter() {
                                            @Override public void onMessage(JMS.Message message, JMS.Session session) throws JMS.JMSException {
                                                System.out.println("CUSTOM:" + ((JMS.TextMessage) message).getText());
                                                super.onMessage(message, session);
                                            }
                                        };
                                    }
                                };
                            }
                        };
                    }
                    """.replace("JMS.", api + "."));
    }
}
