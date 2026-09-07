/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.hibernate.validator.internal.JavaxValidatorFactoryShape;
import org.springframework.context.support.GenericApplicationContext;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring 5 and Boot 2 validate through {@code javax.validation}; the
 * constraint cache behind such a factory has to be found and emptied the
 * same as behind a {@code jakarta.validation} one.
 */
class ValidatorUnderBothNamesTest {

    @AfterEach
    void forget() {
        ApplicationContextHolder.clear();
    }

    @Test
    void aJavaxValidatorFactorysCacheIsEmptied() {
        GenericApplicationContext ctx = new GenericApplicationContext();
        JavaxValidatorFactoryShape factory = new JavaxValidatorFactoryShape();
        ctx.registerBean("validator", JavaxValidatorFactoryShape.class, () -> factory);
        ctx.refresh();
        ApplicationContextHolder.register(ctx);

        int flushed = SpringValidatorReloader.flush();

        assertEquals(1, flushed, "the factory registered under the javax name is found");
        assertTrue(factory.beanMetaDataManager.beanMetaDataCache.isEmpty(), "and its cache emptied");
        ctx.close();
    }
}
