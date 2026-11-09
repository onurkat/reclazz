package com.onurkat.reclazztest.batch;

import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Map;

/** Holds the provider's original Validator across every edit and restore. */
public class ValidationFixture {
    private final Validator held;
    private final ValidatorFactory factory;
    public ValidationFixture(ValidatorFactory factory) { this.factory = factory; held = factory.getValidator(); }
    public Map<String, Object> validate(String value) {
        var violations = held.validate(new ValidatedPayload(value));
        return Map.of("violations", violations.size(),
                "message", violations.isEmpty() ? "none" : violations.iterator().next().getMessage(),
                "provider", held.getClass().getName(), "factoryIdentity", System.identityHashCode(factory),
                "heldIdentity", System.identityHashCode(held));
    }
}
