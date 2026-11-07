/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package de.hybris.platform.processengine.definition;

public class DefaultProcessDefinitionFactory implements ProcessDefinitionFactory {
    public ProcessDefinitionsCache definitionsCache;
    public XMLProcessDefinitionsReader xmlDefinitionsReader;
    public void add(ProcessDefinition definition) { definitionsCache.add(definition); }
}
