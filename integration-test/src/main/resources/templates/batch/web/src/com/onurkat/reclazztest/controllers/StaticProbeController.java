package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.StaticProbe;
import org.springframework.web.bind.annotation.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.LinkedHashMap;

/** Retains a getter warmed before the edit, alongside the ordinary Java reader. */
@RestController
@RequestMapping("/test/batch/statics")
public class StaticProbeController {
    private static final MethodHandle GETTER = getter();
    private static volatile Object sink;
    private static MethodHandle getter() {
        try { return MethodHandles.lookup().findStaticGetter(StaticProbe.class, "CODES", Map.class); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    @GetMapping
    public Map<String, Object> read() throws Throwable {
        var result = new LinkedHashMap<String, Object>();
        result.put("direct", StaticProbe.CODES.get("key"));
        result.put("retainedGetter", ((Map<?, ?>) GETTER.invoke()).get("key"));
        result.put("version", StaticProbe.version());
        result.put("refused", StaticProbe.REFUSED);
        result.put("count", StaticProbe.class.getField("COUNT").get(null));
        result.put("initializerCalls", StaticProbe.initializerCalls);
        result.put("runtimeValue", StaticProbe.runtimeValue);
        result.put("identity", System.identityHashCode(StaticProbe.IDENTITY));
        return result;
    }
    @PostMapping("/warm")
    public Map<String, Object> warm(@RequestParam("nonce") String nonce) throws Throwable {
        if (!nonce.matches("reclazz-statics-[a-z0-9-]{1,60}")) throw new IllegalArgumentException("Invalid synthetic nonce");
        if (!"startup".equals(StaticProbe.runtimeValue)) throw new IllegalStateException("Already warmed on this boot");
        for (int i = 0; i < 200000; i++) { sink = StaticProbe.CODES.get("key"); sink = GETTER.invoke(); }
        StaticProbe.runtimeValue = nonce;
        return read();
    }
}
