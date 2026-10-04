/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import de.hybris.platform.solrfacetsearch.provider.*;
import de.hybris.platform.solrfacetsearch.config.*;
import java.util.*;

public class ProbeProvider implements FieldValueProvider {
    public Collection<FieldValue> getFieldValues(IndexConfig config, IndexedProperty property, Object model) {
        return List.of(new FieldValue("provider", "provider-v1"));
    }
}
