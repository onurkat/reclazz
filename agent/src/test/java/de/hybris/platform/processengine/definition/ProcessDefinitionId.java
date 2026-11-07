/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package de.hybris.platform.processengine.definition;

public record ProcessDefinitionId(String name, String version) {
    public ProcessDefinitionId(String name) { this(name, null); }
    public boolean isActive() { return version == null; }
}
