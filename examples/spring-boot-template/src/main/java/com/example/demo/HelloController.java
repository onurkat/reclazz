package com.example.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Try Reclazz here. With the app running (./gradlew bootRun), change the returned
 * string, recompile (./gradlew classes), and refresh http://localhost:8080/hello.
 * The new text appears without a restart.
 */
@RestController
public class HelloController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello from Reclazz. Edit this line, recompile, and refresh, no restart.";
    }
}
