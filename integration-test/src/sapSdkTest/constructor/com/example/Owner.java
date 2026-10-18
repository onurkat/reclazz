package com.example;

/** No callback, proxy or factory product: one unchanged native constructor. */
@org.springframework.stereotype.Component
public class Owner {
    public final Helper helper;
    public final int captured;
    public Owner(Helper helper) { this.helper = helper; this.captured = helper.seed; }
    public String marker() { return "owner-v1"; }
}
