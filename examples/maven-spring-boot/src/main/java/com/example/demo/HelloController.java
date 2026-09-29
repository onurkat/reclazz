package com.example.demo;

import java.lang.management.ManagementFactory;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    @GetMapping("/hello")
    public Map<String, Object> hello() {
        return Map.of("message", "Hello before reload",
                "marker", System.getProperty("demo.marker", "missing"),
                "pid", ProcessHandle.current().pid(),
                "startedAt", ManagementFactory.getRuntimeMXBean().getStartTime());
    }
}
