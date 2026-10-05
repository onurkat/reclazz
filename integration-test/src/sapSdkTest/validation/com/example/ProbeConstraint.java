/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A synthetic Bean Validation constraint validated by a genuine ConstraintValidator. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ProbeValidator.class)
public @interface ProbeConstraint {
    String message() default "probe";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
