/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import de.hybris.platform.solrfacetsearch.provider.ValueResolver;
import de.hybris.platform.solrfacetsearch.indexer.spi.InputDocument;
import de.hybris.platform.solrfacetsearch.indexer.IndexerBatchContext;
import de.hybris.platform.solrfacetsearch.config.IndexedProperty;
import de.hybris.platform.solrfacetsearch.config.exceptions.FieldValueProviderException;
import de.hybris.platform.core.model.ItemModel;
import java.util.Collection;

public class ProbeResolver implements ValueResolver<ItemModel> {
    public void resolve(InputDocument document, IndexerBatchContext context,
                        Collection<IndexedProperty> properties, ItemModel model) throws FieldValueProviderException {
        document.addField("resolver", "resolver-v1");
    }
}
