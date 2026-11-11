package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ProbeLoggerState;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/test/batch/logger")
public class LoggerProbeController {
    @PostMapping("/begin") public Map<String,Object> begin(@RequestParam("run") String run) {
        return ProbeLoggerState.begin(run);
    }
    @PostMapping("/emit") public Map<String,Object> emit(@RequestParam("run") String run,
            @RequestParam("phase") String phase, @RequestParam(value="omit", defaultValue="false") boolean omit) {
        return ProbeLoggerState.emit(run, phase, omit);
    }
    @GetMapping("/snapshot") public Map<String,Object> snapshot(@RequestParam("run") String run) {
        return ProbeLoggerState.observe(run);
    }
    @PostMapping("/cleanup") public Map<String,Object> cleanup(@RequestParam("run") String run) {
        return ProbeLoggerState.cleanup(run);
    }
}
