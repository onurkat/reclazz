/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
/** Uses the actual Boot3/Boot4 dependency graph of the selected isolated task. */
class PlainSingletonGainsProxyReloadTest {
    @TempDir Path dir;
    @ParameterizedTest @ValueSource(strings={"transaction","cache"})
    void plainServiceGainsNativeAdviceThroughHttpAndConstructorConsumer(String kind)throws Exception {PlainSingletonGainsProxyAcceptance.run(dir,kind);}
}
