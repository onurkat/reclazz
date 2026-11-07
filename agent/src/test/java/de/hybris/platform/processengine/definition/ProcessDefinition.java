/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package de.hybris.platform.processengine.definition;

public record ProcessDefinition(String name, String version, String graph) {
    public String getName() { return name; }
    public String getVersion() { return version; }
    public ProcessDefinitionId getId() { return new ProcessDefinitionId(name, version); }
}
