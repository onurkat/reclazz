/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

@org.springframework.stereotype.Service
public class CacheConsumer {
    @org.springframework.beans.factory.annotation.Autowired GuavaOwner guava;
    @org.springframework.beans.factory.annotation.Autowired TtlOwner ttl;
    @org.springframework.beans.factory.annotation.Autowired UnrelatedCache unrelated;
}
