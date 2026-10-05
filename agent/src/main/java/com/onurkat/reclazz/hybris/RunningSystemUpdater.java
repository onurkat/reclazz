/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

/**
 * Applies the platform's running-system schema update in place: the same operation
 * as HAC's "Update Running System", which is a live runtime action, not a restart.
 * After an items.xml change regenerates and reloads the model bytecode, running this
 * adds the new attribute's database column and refreshes the type system, so the
 * attribute is usable without a restart or a manual HAC click.
 *
 * <p>Opt-in only (the {@code autoUpdateRunningSystem} agent argument), because it runs
 * DDL against the live database. Implementations do a schema-only, non-destructive
 * update: they add missing columns and tables and never drop, create essential or
 * project data, or localize.
 */
public interface RunningSystemUpdater {

    /**
     * Run the schema-only running-system update.
     *
     * @return true if the update completed; false if it could not run (the facade is
     *         unavailable, an update is already in progress) or reported a failure, in
     *         which case the caller keeps the "apply it in HAC" guidance.
     */
    boolean updateSchema();
}
