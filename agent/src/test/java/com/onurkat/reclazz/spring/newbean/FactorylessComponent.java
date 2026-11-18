/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.newbean;
@org.springframework.stereotype.Component
public class FactorylessComponent {
    static { System.setProperty("reclazz.test.factoryless.component", "initialized"); }
}
