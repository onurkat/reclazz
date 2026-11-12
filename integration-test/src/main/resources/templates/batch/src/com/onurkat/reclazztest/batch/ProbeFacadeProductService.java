package com.onurkat.reclazztest.batch;

import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.product.impl.DefaultProductService;

/** Owned facade collaborator; selection still executes the native product DAO. */
public class ProbeFacadeProductService extends DefaultProductService {
    @Override public ProductModel getProductForCode(final String code) {
        if (!code.matches("^reclazz-facade-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-target$"))
            throw new IllegalArgumentException("Not an owned facade target");
        return super.getProductForCode(code + "-one");
    }
}
