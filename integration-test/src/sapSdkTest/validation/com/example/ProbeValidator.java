/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** A genuine custom ConstraintValidator; isValid is the reload surface. */
public class ProbeValidator implements ConstraintValidator<ProbeConstraint, String> {
    private final Dependency dependency;

    public ProbeValidator(Dependency dependency) { this.dependency = dependency; }

    @Override
    public void initialize(ProbeConstraint annotation) { ValidatorApp.inits++; }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        ValidatorApp.validations++;
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("validator-v1:" + dependency.required() + ":" + value)
                .addConstraintViolation();
        return false;
    }
}
