package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.interceptors.ValidationProbe;
import de.hybris.platform.catalog.model.CatalogModel;
import de.hybris.platform.catalog.model.CatalogVersionModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.session.SessionService;
import de.hybris.platform.tx.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** Test-extension endpoint. Creates only nonce-named fixtures, always rolls back. */
@RestController
@RequestMapping("/test")
public class SapVerificationController {
    @Autowired private ModelService modelService;
    @Autowired private SessionService sessionService;

    @RequestMapping(value = "/interceptor-save", method = RequestMethod.POST)
    public String validateSave(@RequestParam("nonce") String nonce) {
        if (!nonce.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid test nonce");
        Transaction tx = Transaction.current();
        if (tx.isRunning()) throw new IllegalStateException("Test requires its own rollback transaction");
        tx.begin();
        try {
            String code = "reclazz-probe-" + nonce;
            CatalogModel catalog = modelService.create(CatalogModel.class);
            catalog.setId(code);
            modelService.save(catalog);
            CatalogVersionModel version = modelService.create(CatalogVersionModel.class);
            version.setCatalog(catalog);
            version.setVersion("Staged");
            modelService.save(version);
            ProductModel product = modelService.create(ProductModel.class);
            product.setCatalogVersion(version);
            product.setCode(code);
            // Application product interceptors query session.catalogversions;
            // a bare request session has none, so the probe supplies its own.
            sessionService.setAttribute("catalogversions", java.util.Collections.singletonList(version));
            ValidationProbe.begin(code);
            modelService.save(product);
            return ValidationProbe.result();
        } finally {
            try { tx.rollback(); }
            finally { ValidationProbe.clear(); modelService.detachAll(); }
        }
    }
}
