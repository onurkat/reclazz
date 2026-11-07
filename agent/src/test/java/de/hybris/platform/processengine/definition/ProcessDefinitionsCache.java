/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package de.hybris.platform.processengine.definition;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import com.onurkat.reclazz.platform.SapProcessDefinitions;

public class ProcessDefinitionsCache {
    public ProcessDefinitionsProvider processDefinitionsProvider;
    public Map<ProcessDefinitionId,ProcessDefinition> definitions;
    public final ReadWriteLock definitionsLock = new ReentrantReadWriteLock();
    public int publications;
    /** Models advice entry/exit for unit tests; SDK tests apply the actual transformer. */
    public void initialise(boolean observed) throws Exception {
        if (observed) { SapProcessDefinitions.enterCache(this); SapProcessDefinitions.enterProvider(processDefinitionsProvider); }
        boolean success = false;
        try {
            definitions = new HashMap<>();
            for (var definition : processDefinitionsProvider.getInitialActiveDefinitions()) add(definition);
            success = true;
        } finally {
            if (observed) { SapProcessDefinitions.exitProvider(success); SapProcessDefinitions.exitCache(success); }
        }
    }
    public void add(ProcessDefinition definition) {
        if (definitions == null) throw new IllegalStateException("No native initialization in fixture add");
        definitions.put(new ProcessDefinitionId(definition.name()), definition);
        definitions.put(definition.getId(), definition);
        publications++;
    }
}
