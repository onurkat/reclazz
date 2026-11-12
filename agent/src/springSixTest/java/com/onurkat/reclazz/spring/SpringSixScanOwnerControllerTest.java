package com.onurkat.reclazz.spring;
import org.junit.jupiter.api.Test;
class SpringSixScanOwnerControllerTest {
    @Test void nativeSpringSixMappingUsesCapturedOwnerAndKeepsUnrelatedHandler() throws Exception {
        SpringNewBeanRegistrarTest.sharedLoaderScanOwnerEnrollment();
    }
}
