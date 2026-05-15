package com.example.aisport.controller;

import com.example.aisport.experiment.ExperimentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody RunRequest req) {
        if (req.manifest == null || req.outputDir == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "manifest and outputDir are required"));
        }
        return ResponseEntity.ok(experimentService.startRun(req.manifest, req.outputDir));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<?> get(@PathVariable String runId) {
        var run = experimentService.getRun(runId);
        if (run == null) {
            return ResponseEntity.status(404).body(Map.of("error", "run not found", "runId", runId));
        }
        return ResponseEntity.ok(run);
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(experimentService.listRuns());
    }

    @GetMapping("/{runId}/summary")
    public ResponseEntity<?> summary(@PathVariable String runId) throws Exception {
        var summary = experimentService.readSummaryFiles(runId);
        if (summary == null) {
            return ResponseEntity.status(404).body(Map.of("error", "run not found", "runId", runId));
        }
        return ResponseEntity.ok(summary);
    }

    public static class RunRequest {
        public String manifest;
        public String outputDir;
    }
}
