package com.onurkat.reclazztest.batch;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ProbeValidator.class)
public @interface ProbeConstraint {
    int max() default 3;
    String message() default "limit-v1";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
