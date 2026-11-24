/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Where unprefixed imports of a file-based config document resolve, as Boot's StandardConfigDataLocationResolver does. */
class SpringConfigImportsTest {
    private static final Path CWD = Path.of(System.getProperty("user.dir"));

    @Test void aLocationWithASlashResolvesNextToTheDocument() {
        Path file = Path.of("/srv/app/conf/application.yml");
        assertEquals(new SpringConfigImports.Base(file.getParent(), null), SpringConfigImports.fileBase(file, "file:/srv/app/conf/application.yml"));
        assertEquals(new SpringConfigImports.Base(file.getParent(), null), SpringConfigImports.fileBase(file, "optional:file:./conf/"));
    }

    @Test void aBareNameImportedNextToADocumentKeepsThatDirectory() {
        Path file = Path.of("/srv/app/conf/sub/cc.properties");
        assertEquals(new SpringConfigImports.Base(file.getParent(), null), SpringConfigImports.fileBase(file, "cc.properties"));
    }

    @Test void aLocationWithoutASlashThatNamesTheFileResolvesAgainstTheWorkingDirectory() {
        // Boot takes the text up to the last '/': none here, so a Windows file:C:\conf\application.yml resolves its
        // imports against the working directory. The same rule, with a backslash in a name on this file system:
        Path file = CWD.resolve("conf\\application.yml");
        assertEquals(new SpringConfigImports.Base(null, null), SpringConfigImports.fileBase(file, "file:conf\\application.yml"));
        assertEquals(new SpringConfigImports.Base(null, null), SpringConfigImports.fileBase(CWD.resolve("application.yml"), "application.yml"));
    }
}
