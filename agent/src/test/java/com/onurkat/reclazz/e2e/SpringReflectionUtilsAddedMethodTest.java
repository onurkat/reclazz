/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Application code that walks a class with Spring's ReflectionUtils sees the
 * methods a reload added, as it would with getDeclaredMethods.
 *
 * <p>SAP Commerce's model converter finds a model's accessors this way. An
 * attribute added to an already loaded model had no setter for it, and
 * {@code ModelService.setAttributeValue} failed with "does not have
 * corresponding setter/getter".
 */
class SpringReflectionUtilsAddedMethodTest {
    @TempDir Path dir;

    @Test
    void anAddedSetterIsFoundThroughEveryWalkAndCalled() throws Exception {
        try (var app = WatchedApp.in(dir).classpath(WatchedApp.springClasspath())
                .with("Model", model(false))
                .with("SubModel", "package app; public class SubModel extends Model { }")
                .with("App", """
                        package app;
                        import java.lang.reflect.Method;
                        import java.util.*;
                        import org.springframework.util.ReflectionUtils;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                SubModel model = new SubModel();
                                while (true) {
                                    String line;
                                    try {
                                        List<Method> setters = new ArrayList<>();
                                        ReflectionUtils.doWithMethods(SubModel.class, setters::add,
                                                m -> m.getName().equals("setNote"));
                                        List<String> local = new ArrayList<>();
                                        ReflectionUtils.doWithLocalMethods(Model.class, m -> local.add(m.getName()));
                                        Method getter = ReflectionUtils.findMethod(SubModel.class, "getNote");
                                        Method setter = ReflectionUtils.findMethod(SubModel.class, "setNote", String.class);
                                        String value = "none";
                                        if (setter != null && getter != null) {
                                            ReflectionUtils.invokeMethod(setters.get(0), model, "walked");
                                            String walked = (String) ReflectionUtils.invokeMethod(getter, model);
                                            ReflectionUtils.invokeMethod(setter, model, "found");
                                            value = walked + "+" + ReflectionUtils.invokeMethod(getter, model);
                                        }
                                        line = "walk=" + setters.size() + ":local=" + local.contains("getNote")
                                                + ":find=" + (setter != null) + ":value=" + value;
                                    } catch (Throwable e) {
                                        line = "error=" + e;
                                    }
                                    System.out.println("LIVE=" + line);
                                    Thread.sleep(100);
                                }
                            }
                        }
                        """).start()) {
            app.awaitOrFail("LIVE=walk=0:local=false:find=false:value=none", "before the reload nothing is there to find");
            app.awaitOrFail("] Watching 1 director", "watcher ready");
            app.rewrite("Model", model(true));
            app.awaitOrFail("LIVE=walk=1:local=true:find=true:value=walked+found",
                    "the added accessors must be found by doWithMethods, doWithLocalMethods and findMethod, and called");
        }
    }

    private static String model(boolean note) {
        return "package app; public class Model { private String code; public String getCode() { return code; }"
                + (note ? " private String note; public String getNote() { return note; }"
                        + " public void setNote(String note) { this.note = note; }" : "")
                + " }";
    }
}
