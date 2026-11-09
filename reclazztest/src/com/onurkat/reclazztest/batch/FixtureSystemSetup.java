package com.onurkat.reclazztest.batch;

import de.hybris.platform.core.initialization.SystemSetup;
import de.hybris.platform.servicelayer.impex.ImportConfig;
import de.hybris.platform.servicelayer.impex.ImportService;
import de.hybris.platform.servicelayer.impex.impl.StreamBasedImpExResource;
import org.springframework.beans.factory.annotation.Autowired;

/** Reproducible essential data, confined to reserved synthetic fixture identifiers. */
@SystemSetup(extension = "reclazztest")
public class FixtureSystemSetup {
    @Autowired private ImportService importService;

    @SystemSetup(type = SystemSetup.Type.ESSENTIAL, process = SystemSetup.Process.ALL)
    public void createEssentialData() {
        for (String path : java.util.List.of("/impex/reclazz-fixture-essential.impex", "/impex/test-data.impex")) {
            try (var stream = FixtureSystemSetup.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Missing synthetic fixture resource " + path);
                ImportConfig config = new ImportConfig();
                config.setScript(new StreamBasedImpExResource(stream, "UTF-8"));
                config.setSynchronous(true);
                config.setRemoveOnSuccess(true);
                config.setFailOnError(true);
                if (!importService.importData(config).isSuccessful())
                    throw new IllegalStateException("Synthetic fixture essential data import failed: " + path);
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }
    }
}
