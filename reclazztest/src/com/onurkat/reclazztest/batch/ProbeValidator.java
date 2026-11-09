package com.onurkat.reclazztest.batch;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ProbeValidator implements ConstraintValidator<ProbeConstraint, String> {
    private int max;
    private String message;
    @Override public void initialize(ProbeConstraint constraint) {
        max = constraint.max(); message = constraint.message();
    }
    @Override public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.length() <= max) return true;
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("validator-v1:" + message).addConstraintViolation();
        return false;
    }
}
