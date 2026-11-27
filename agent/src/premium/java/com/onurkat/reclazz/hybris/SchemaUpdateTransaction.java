/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

/** The HSQLDB dictionary reader reuses its system tables inside a native transaction. */
final class SchemaUpdateTransaction {
    static <T> T call(String jdbcUrl, Callable<Object> current, Callable<T> update, Predicate<T> success) throws Exception {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:hsqldb:")) return update.call();
        Object tx = current.call();
        var type = tx.getClass();
        var running = type.getMethod("isRunning");
        if (Boolean.TRUE.equals(running.invoke(tx))) return update.call();

        var begin = type.getMethod("begin");
        var commit = type.getMethod("commit");
        var rollback = type.getMethod("rollback");
        begin.invoke(tx);
        try {
            T result = update.call();
            if (success.test(result)) commit.invoke(tx);
            return result;
        } finally {
            if (Boolean.TRUE.equals(running.invoke(tx))) rollback.invoke(tx);
        }
    }

    private SchemaUpdateTransaction() {}
}
