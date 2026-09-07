/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hibernate.validator.internal;

import javax.validation.ClockProvider;
import javax.validation.ConstraintValidatorFactory;
import javax.validation.MessageInterpolator;
import javax.validation.ParameterNameProvider;
import javax.validation.TraversableResolver;
import javax.validation.Validator;
import javax.validation.ValidatorContext;
import javax.validation.ValidatorFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * The shape of a Hibernate Validator factory as a Spring 5 or Boot 2
 * application holds it: the {@code javax.validation} interface, a manager,
 * and the metadata cache under its one name. In the validator's package
 * because the flush only walks objects it owns.
 */
public final class JavaxValidatorFactoryShape implements ValidatorFactory {

    public static final class Manager {
        public final Map<Object, Object> beanMetaDataCache = new HashMap<>(Map.of("demo.Order", "constraints"));
    }

    public final Manager beanMetaDataManager = new Manager();

    @Override public Validator getValidator() { return null; }
    @Override public ValidatorContext usingContext() { return null; }
    @Override public MessageInterpolator getMessageInterpolator() { return null; }
    @Override public TraversableResolver getTraversableResolver() { return null; }
    @Override public ConstraintValidatorFactory getConstraintValidatorFactory() { return null; }
    @Override public ParameterNameProvider getParameterNameProvider() { return null; }
    @Override public ClockProvider getClockProvider() { return null; }
    @Override public <T> T unwrap(Class<T> type) { return null; }
    @Override public void close() { }
}
