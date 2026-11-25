/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TomcatWebResourceEvictionTest {
    @TempDir Path directory;

    @Test protected void evictsOnlyTheSavedPathInItsDeployedContext() throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(directory.resolve("server").toString());
        Path root = Files.createDirectory(directory.resolve("webroot"));
        Path other = Files.createDirectory(directory.resolve("other"));
        Path target = Files.writeString(root.resolve("page.jsp"), "old");
        Path sibling = Files.writeString(root.resolve("keep.css"), "old");
        Path outside = Files.writeString(other.resolve("page.jsp"), "old");
        Context context = tomcat.addContext("/app", root.toString());
        Context second = tomcat.addContext("/other", other.toString());
        try {
            tomcat.start();
            context.getResources().setCacheTtl(60_000);
            second.getResources().setCacheTtl(60_000);
            assertEquals("old", body(context, "/page.jsp"));
            assertEquals("old", body(context, "/keep.css"));
            assertEquals("old", body(second, "/page.jsp"));
            for (Path file : new Path[]{target, sibling, outside}) Files.writeString(file, "new");
            assertEquals("old", body(context, "/page.jsp"));
            TomcatContextScanner.evictFromContext(second, target);
            TomcatContextScanner.evictFromContext(context, target);
            assertEquals("new", body(context, "/page.jsp"));
            assertEquals("old", body(context, "/keep.css"));
            assertEquals("old", body(second, "/page.jsp"));
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static String body(Context context, String path) {
        return new String(context.getResources().getResource(path).getContent(), StandardCharsets.UTF_8);
    }
}
