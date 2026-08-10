package com.onurkat.reclazztest.services;

import org.springframework.stereotype.Component;

@Component("reclazzTestDao")
public class TestDao {

    public String query() {
        return "dao-v1";
    }
}
