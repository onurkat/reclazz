/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazztest.services;

import org.springframework.stereotype.Service;

@Service("reclazzHelperService")
public class HelperService {

    public String getHelperVersion() {
        return "helper-v1";
    }
}
