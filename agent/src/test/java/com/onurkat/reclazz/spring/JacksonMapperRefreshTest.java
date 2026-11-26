/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JacksonMapperRefreshTest {
    static class Consumer { Object mapper; }
    static class Converter {
        Object mapper;
        Object reject;
        public Object getObjectMapper() { return mapper; }
        public void setObjectMapper(Object value) {
            mapper = value;
            if (value == reject) throw new IllegalStateException("setter failed after assignment");
        }
    }
    static class Registry {
        final ConcurrentHashMap<String,Object> instances = new ConcurrentHashMap<>();
        public Object getBean(String name) { return instances.get(name); }
    }

    @Test void failingConverterRestoresTheContextAndAlreadySwitchedConsumer() throws Exception {
        Object old = new Object(), candidate = new Object();
        var registry = new Registry();
        registry.instances.put("mapper", old);
        var converter = new Converter(); converter.mapper = old; converter.reject = candidate;
        var consumer = new Consumer(); consumer.mapper = old;
        var publication = new JacksonMapperRefresh.Publication(registry, "builder", "mapper", registry.instances,
                converter, converter, old, List.of(new JacksonMapperRefresh.Slot(consumer, Consumer.class.getDeclaredField("mapper"))));
        assertThrows(IllegalStateException.class, () -> publication.publish(candidate));
        assertSame(old, converter.mapper);
        assertSame(old, registry.getBean("mapper"));
        assertSame(old, consumer.mapper);
    }
}
