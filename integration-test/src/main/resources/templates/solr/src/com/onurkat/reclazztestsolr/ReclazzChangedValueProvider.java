package com.onurkat.reclazztestsolr;

import de.hybris.platform.solrfacetsearch.config.IndexConfig;
import de.hybris.platform.solrfacetsearch.config.IndexedProperty;
import de.hybris.platform.solrfacetsearch.provider.FieldNameProvider;
import de.hybris.platform.solrfacetsearch.provider.FieldValue;
import de.hybris.platform.solrfacetsearch.provider.FieldValueProvider;
import java.util.Collection;

/** Synthetic constant provider; no database, external data or runtime dependencies. */
public class ReclazzChangedValueProvider implements FieldValueProvider {
    private FieldNameProvider fieldNameProvider;
    public void setFieldNameProvider(FieldNameProvider provider) { fieldNameProvider = provider; }
    public Collection<FieldValue> getFieldValues(IndexConfig config, IndexedProperty property, Object model) {
        return fieldNameProvider.getFieldNames(property, null).stream().map(name -> new FieldValue(name, "v1" )).toList();
    }
}
