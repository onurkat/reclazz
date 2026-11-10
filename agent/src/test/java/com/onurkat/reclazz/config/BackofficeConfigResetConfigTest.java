package com.onurkat.reclazz.config;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BackofficeConfigResetConfigTest {
    @Test void nativeF4ResetIsDefaultOnAndRecognizedBesideExistingKeys() {
        assertTrue(AgentConfig.parse(null).isBackofficeConfigReset());
        var disabled=AgentConfig.parse("autoCompile=true,backofficeConfigReset=false,verbose=true");
        assertFalse(disabled.isBackofficeConfigReset());assertTrue(disabled.isAutoCompile());assertTrue(disabled.isVerbose());assertTrue(disabled.getUnknownKeys().isEmpty());
        assertTrue(AgentConfig.parse("backofficeConfigReset=true").isBackofficeConfigReset());
    }
}
