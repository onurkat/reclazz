/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQTopic;
import org.springframework.jms.core.JmsTemplate;
import java.util.concurrent.TimeUnit;

/**
 * R36: the helper/property cascade of {@link ModernJmsConsumerCascadeReloadTest} on a topic (pub-sub) factory. A plain topic only delivers to
 * existing subscribers, so each send first waits until the broker shows exactly one subscriber on that topic.
 */
class ModernJmsTopicConsumerCascadeReloadTest extends JmsConsumerCascadeContract {
    Backend open() throws Exception { return new Topic(); }
    static class Topic extends ModernJmsConsumerCascadeReloadTest.Env {
        final JmsTemplate topics;
        Topic() throws Exception { super(); topics = new JmsTemplate(new ActiveMQConnectionFactory(url)); topics.setPubSubDomain(true); }
        @Override public String app() {
            String app = super.app();
            String anchor = "factory.setReceiveTimeout(100L);";
            if (app.indexOf(anchor) != app.lastIndexOf(anchor) || !app.contains(anchor)) throw new IllegalStateException("cascade app changed");
            return app.replace(anchor, anchor + " factory.setPubSubDomain(true);");
        }
        @Override public void send(String topic, String value) {
            try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < until) {
                    var destination = broker.getDestination(new ActiveMQTopic(topic));
                    if (destination != null && destination.getConsumers().size() == 1) { topics.convertAndSend(topic, value); return; }
                    Thread.sleep(25);
                }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            throw new AssertionError("no single subscriber on topic " + topic);
        }
    }
}
