/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazztest.interceptors;

import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.servicelayer.interceptor.InterceptorContext;
import de.hybris.platform.servicelayer.interceptor.InterceptorException;
import de.hybris.platform.servicelayer.interceptor.ValidateInterceptor;

import org.springframework.stereotype.Component;

@Component("testValidateInterceptor")
public class TestValidateInterceptor implements ValidateInterceptor<ProductModel> {

    private static volatile String lastValidation = "none";

    @Override
    public void onValidate(ProductModel model, InterceptorContext ctx) throws InterceptorException {
        lastValidation = "validated-v1:" + model.getCode();
    }

    public static String getLastValidation() {
        return lastValidation;
    }
}
