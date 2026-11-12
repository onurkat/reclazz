package com.onurkat.reclazz.inttest.tests

import java.nio.file.Path

/** The disposable SAP install these profile tests may write to: RECLAZZ_SAP_HOME, or reclazz-sap beside the repository. */
internal fun cleanSapHybris(projects: Path): Path =
    (System.getenv("RECLAZZ_SAP_HOME")?.let { Path.of(it) } ?: projects.resolve("reclazz-sap")).resolve("hybris")
