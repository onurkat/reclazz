package com.onurkat.reclazztest.batch;

public class ValidatedPayload {
    @ProbeConstraint(max = 3, message = "limit-v1")
    private final String value;
    public ValidatedPayload(String value) { this.value = value; }
}
