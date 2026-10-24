/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

import java.nio.file.Path;

/** A classified change on the existing reload executor, not the watcher thread. */
@FunctionalInterface
public interface PlatformChangeHandler {
    Outcome handle(Change change);

    record Change(Capability capability, Path path, String eventType,
                  String moduleName, String sourceRoot) { }

    enum Outcome { HANDLED, ADVICE, IGNORED }
}
