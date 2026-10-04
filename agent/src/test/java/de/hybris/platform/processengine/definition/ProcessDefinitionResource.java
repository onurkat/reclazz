/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package de.hybris.platform.processengine.definition;

/** Synthetic contract fixture; SDK probes independently check the genuine class. */
public class ProcessDefinitionResource {
    private org.springframework.core.io.Resource resource;
    public void setResource(org.springframework.core.io.Resource resource) { this.resource = resource; }
    public org.springframework.core.io.Resource getResource() { return resource; }
}
