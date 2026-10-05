/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import com.sun.net.httpserver.HttpServer;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Local Bean Validation acceptance: genuine Hibernate Validator, provider-held validator instance. */
public class ValidatorApp {
    public static int inits, validations, releases;
    static final Dependency dependency = new Dependency();

    /** Injects the dependency, so the provider holds a validator with a collaborator. */
    static final class Factory implements ConstraintValidatorFactory {
        @Override public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
            if (key == ProbeValidator.class) return key.cast(new ProbeValidator(dependency));
            try {
                return key.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
        @Override public void releaseInstance(ConstraintValidator<?, ?> instance) { releases++; }
    }

    public static void main(String[] args) throws Exception {
        ValidatorFactory factory = Validation.byProvider(HibernateValidator.class).configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .constraintValidatorFactory(new Factory())
                .buildValidatorFactory();
        Validator validator = factory.getValidator();

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String nonce = exchange.getRequestURI().getRawQuery();
            int status = 200;
            String response;
            try {
                if (!"GET".equals(exchange.getRequestMethod()) || nonce == null || !nonce.matches("[a-z0-9-]{1,60}"))
                    throw new IllegalArgumentException("Invalid synthetic request");
                validations = 0;
                Set<ConstraintViolation<Bean>> violations = validator.validate(new Bean(nonce));
                String message = violations.isEmpty() ? "none" : violations.iterator().next().getMessage();
                response = "nonce=" + nonce + ";pid=" + ProcessHandle.current().pid()
                        + ";violations=" + violations.size() + ";message=" + message
                        + ";inits=" + inits + ";validations=" + validations;
            } catch (Throwable failed) {
                status = 500;
                response = failed.getClass().getSimpleName() + ": " + failed.getMessage();
                failed.printStackTrace();
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        System.out.println("VALIDATOR_PORT=" + server.getAddress().getPort());
    }
}
