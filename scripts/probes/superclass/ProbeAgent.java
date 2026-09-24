// Copyright 2026 Onur Kat
// SPDX-License-Identifier: Apache-2.0
import java.lang.instrument.*;
public class ProbeAgent {
 public static Instrumentation inst;
 public static void premain(String args, Instrumentation value) { inst=value; }
}
