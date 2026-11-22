/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import java.util.*;
import java.util.function.Consumer;

/** Complete save-scoped record admission; route implementations own their distinct publication proof. */
public interface RecordBatch extends AutoCloseable {
    boolean active();
    Set<String> recordNames();
    Set<String> names();
    List<RecordNativeBatch.Change> changes();
    Map<String,String> refusals();
    RecordBatch requireComplete(Set<String> accepted);
    RecordNativeBatch.Outcome commit(Consumer<RecordNativeBatch.Change> repair);
    @Override default void close() { }
}
