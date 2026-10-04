/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import de.hybris.platform.solrfacetsearch.provider.TypeValueResolver;
import de.hybris.platform.solrfacetsearch.indexer.spi.InputDocument;
import de.hybris.platform.solrfacetsearch.indexer.IndexerBatchContext;
import de.hybris.platform.solrfacetsearch.config.exceptions.FieldValueProviderException;

public class ProbeTypeResolver implements TypeValueResolver<String> {
    public void resolve(InputDocument document, IndexerBatchContext context, String model) throws FieldValueProviderException {
        document.addField("type", "type-v1");
    }
}
