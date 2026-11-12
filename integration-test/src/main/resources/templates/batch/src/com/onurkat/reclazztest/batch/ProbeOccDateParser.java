package com.onurkat.reclazztest.batch;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.convert.converter.Converter;

/** Owned request parser; no shared OCC formatter registration. */
public class ProbeOccDateParser implements Converter<String, Date> {
    private final AtomicLong calls = new AtomicLong();

    @Override
    public Date convert(final String value) {
        calls.incrementAndGet();
        return new Date(OffsetDateTime.parse(value).toInstant().toEpochMilli() + 0L);
    }

    public long getCalls() {
        return calls.get();
    }
}
