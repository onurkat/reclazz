/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
class RecordNativeBootTwoMetadataTest {
    @TempDir Path dir;
    @ParameterizedTest @ValueSource(strings={"generic","annotations"})
    void metadataAndRetainedCodecsFollowSameErasureChanges(String edit)throws Exception{RecordNativeContainerAcceptance.run(dir,edit);}
}
