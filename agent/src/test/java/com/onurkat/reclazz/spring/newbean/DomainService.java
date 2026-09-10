/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.newbean;

import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Service;
import java.lang.annotation.*;

/** A custom, meta-annotated stereotype for the new-bean registrar test. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Service
public @interface DomainService {
    @AliasFor(annotation = Service.class, attribute = "value")
    String value() default "";
}
