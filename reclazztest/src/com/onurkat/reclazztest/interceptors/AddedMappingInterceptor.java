package com.onurkat.reclazztest.interceptors;

import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.servicelayer.interceptor.InterceptorContext;
import de.hybris.platform.servicelayer.interceptor.ValidateInterceptor;

/** Intentionally not a component: only the test's XML save registers a bean. */
public class AddedMappingInterceptor implements ValidateInterceptor<Object> {
    @Override
    public void onValidate(Object model, InterceptorContext context) {
        if (model instanceof ProductModel) AddedMappingProbe.record(((ProductModel) model).getCode(), this);
    }
}
