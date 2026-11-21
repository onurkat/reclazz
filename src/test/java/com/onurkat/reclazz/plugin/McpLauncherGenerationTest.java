/* Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin;
import com.onurkat.reclazz.plugin.agent.McpLauncherStaging;
import java.nio.file.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class McpLauncherGenerationTest {
 @TempDir Path home;
 @Test void olderLauncherGenerationWithSameMcpMustNotBlockNewBuild() throws Exception {
  Path jar=home.resolve("mcp.jar");Manifest m=new Manifest();m.getMainAttributes().putValue("Manifest-Version","1.0");m.getMainAttributes().putValue("Implementation-Version","1.0");
  try(var out=new JarOutputStream(Files.newOutputStream(jar),m)){out.putNextEntry(new JarEntry("payload"));out.write(1);out.closeEntry();}
  String hash=McpLauncherStaging.INSTANCE.digest(Files.readAllBytes(jar));
  Path old=home.resolve(".reclazz/mcp/1.0-"+hash.substring(0,12));Files.createDirectories(old);
  Files.copy(jar,old.resolve("reclazz-mcp.jar"));
  Files.writeString(old.resolve("reclazz-mcp"),"#!/bin/sh\nexec java -jar \"${0%/*}/reclazz-mcp.jar\" \"$@\"\n");
  Files.writeString(old.resolve("reclazz-mcp.cmd"),"@echo off\r\njava -jar \"%~dp0reclazz-mcp.jar\" %*\r\n");
  old.resolve("reclazz-mcp").toFile().setExecutable(true);
  var staged=McpLauncherStaging.INSTANCE.stage(jar,jar,"1.0",McpLauncherStagingTest.Companion.javaPath(),home,null);
  assertNotEquals(old,staged.getDirectory(),"A new launcher generation must use another immutable identity");
  assertTrue(Files.exists(old.resolve("reclazz-mcp.jar")),"Older client references remain usable");
 }
}
