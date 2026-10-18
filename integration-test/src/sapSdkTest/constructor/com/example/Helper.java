package com.example;

/** Synthetic plain constructor dependency; only seedValue's body is edited. */
@org.springframework.stereotype.Component
public class Helper {
    public final int seed = seedValue();
    public int seedValue() { return 10; }
}
