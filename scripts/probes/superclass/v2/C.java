// Copyright 2026 Onur Kat
// SPDX-License-Identifier: Apache-2.0
public class C extends B {
 public int state = 7;
 public String value() { try { return (String) Router.target.invokeExact(this); }
 catch (Throwable t) { throw new RuntimeException(t); } }
}
