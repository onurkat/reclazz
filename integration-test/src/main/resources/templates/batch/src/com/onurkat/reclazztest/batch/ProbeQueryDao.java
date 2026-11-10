package com.onurkat.reclazztest.batch;

import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import java.util.List;
import java.util.Map;

/** Plain XML singleton: the live oracle is a persisted query result. */
public class ProbeQueryDao {
    private final FlexibleSearchService searchService;

    public ProbeQueryDao(FlexibleSearchService searchService) {
        this.searchService = searchService;
    }

    public List<String> find(String run) {
        if (!run.matches("^reclazz-dao-[0-9a-f-]{36}$")) throw new IllegalArgumentException("Invalid owned run");
        return searchService.<ItemModel>search(
                "SELECT {pk} FROM {ReclazzSchemaProbe} WHERE {nonce} LIKE ?p ORDER BY {nonce}",
                Map.of("p", run + "-a-%")).getResult().stream()
                .map(row -> row.<String>getProperty("nonce")).toList();
    }
}
