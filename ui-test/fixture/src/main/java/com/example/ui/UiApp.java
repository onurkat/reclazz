package com.example.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class UiApp {
    public static void main(String[] args) {
        SpringApplication.run(UiApp.class, args);
    }

    @GetMapping("/value")
    public String value() {
        return ProcessHandle.current().pid() + ":baseline";
    }
}
