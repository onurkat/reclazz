/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

/** R36: the dependency/property cascade of {@link KafkaConsumerCascadeReloadTest} on a batch listener factory. */
class KafkaBatchConsumerCascadeReloadTest extends KafkaConsumerCascadeContract {
    Backend open() throws Exception { return new Batch(); }
    static class Batch extends KafkaConsumerCascadeReloadTest.Env {
        @Override public String app(boolean nativeDefaultBatch) {
            String app = super.app(nativeDefaultBatch);
            String anchor = "factory.getContainerProperties().setPollTimeout(100);";
            if (!app.contains(anchor)) throw new IllegalStateException("cascade app changed");
            // Kafka refuses AckMode.RECORD on a batch listener; the explicit case sets BATCH instead.
            return app.replace(anchor, "factory.setBatchListener(true); " + anchor)
                    .replace("AckMode.RECORD);", "AckMode.BATCH);");
        }
        @Override public String ack(boolean nativeDefaultBatch) { return "BATCH"; }
        @Override public String receiver(String body) {
            return "public void receive(java.util.List<String> values){for(String value:values){" + body + "}}";
        }
    }
}
