// Copyright 2026 Onur Kat
// SPDX-License-Identifier: Apache-2.0
public class NewBehavior extends B {
 public String apply(C original) { return "v2:"+super.who()+":"+original.state; }
}
