/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package de.hybris.platform.processengine.definition;

import org.springframework.context.support.GenericApplicationContext;
import java.util.ArrayList;
import java.util.List;

public class ProcessDefinitionsProvider {
    public GenericApplicationContext applicationContext;
    public XMLProcessDefinitionsReader reader;
    public boolean dynamic, failProvider;
    public int initialCalls, latestCalls;
    public List<ProcessDefinition> getInitialActiveDefinitions() throws Exception {
        initialCalls++;
        var result = new ArrayList<ProcessDefinition>();
        for (Object bean : applicationContext.getBeanFactory().getSingletonNames()) {
            Object value = applicationContext.getBeanFactory().getSingleton((String) bean);
            if (value instanceof ProcessDefinitionResource holder) {
                var parsed = reader.from(holder.getResource());
                com.onurkat.reclazz.platform.SapProcessDefinitions.parsed(holder.getResource(), parsed);
                result.add(parsed);
            }
        }
        return result;
    }
    public ProcessDefinitionId getLatestDefinitionIdFor(ProcessDefinitionId id) {
        latestCalls++;
        if (failProvider) throw new IllegalStateException("example provider failure");
        return dynamic ? new ProcessDefinitionId(id.name(), "db") : null;
    }
}
