package com.onurkat.reclazztest.batch;

import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import java.util.List;
import java.util.Map;

/** Native restrictions select user/catalog visibility; the Java edit selects a suffix. */
public class ProbeSearchRestrictionDao {
    private final FlexibleSearchService searchService;
    public ProbeSearchRestrictionDao(FlexibleSearchService searchService) { this.searchService=searchService; }
    public List<ProductModel> find(String run) {
        if(!run.matches("^reclazz-restriction-[0-9a-f-]{36}$"))throw new IllegalArgumentException("Invalid owned run");
        return searchService.<ProductModel>search(
                "SELECT {pk} FROM {Product} WHERE {code} LIKE ?owned ORDER BY {pk}",
                Map.of("owned",run + "-%-one")).getResult();
    }
}
