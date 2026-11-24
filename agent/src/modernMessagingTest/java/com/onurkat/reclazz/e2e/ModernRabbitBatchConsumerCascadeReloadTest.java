/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

/** R36: {@link RabbitBatchConsumerCascadeReloadTest} on Spring Framework 6 / Spring AMQP 3. */
class ModernRabbitBatchConsumerCascadeReloadTest extends RabbitConsumerCascadeContract {
    Backend open() throws Exception { return new Batch(); }
    static class Batch extends ModernRabbitConsumerCascadeReloadTest.Env {
        Batch() throws Exception { super(); }
        @Override public String app() {
            String app = super.app();
            String anchor = "factory.setConnectionFactory(cf);";
            if (!app.contains(anchor)) throw new IllegalStateException("cascade app changed");
            return app.replace(anchor, anchor + " factory.setBatchListener(true); factory.setConsumerBatchEnabled(true);"
                    + " factory.setBatchSize(5); factory.setReceiveTimeout(200L);");
        }
        @Override public String receiver(String body) {
            return "public void receive(java.util.List<String> values){for(String value:values){" + body + "}}";
        }
    }
}
