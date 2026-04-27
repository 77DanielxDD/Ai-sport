package com.example.aisport.experiment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExperimentService {

    public static class ExperimentRun {
        public String runId;
        public String status;
        public String manifest;
        public String outputDir;
        public String message;
        public LocalDateTime startedAt;
        public LocalDateTime finishedAt;
    }

    @Value("${app.experiment.python:python}")
    private String pythonCmd;

    @Value("${app.experiment.script-path:evaluation/action_analysis/scripts/evaluate_action_analysis.py}")
    private String scriptPath;

    private final ConcurrentHashMap<String, ExperimentRun> runs = new ConcurrentHashMap<>();

    public ExperimentRun startRun(String manifest, String outputDir) {
        String runId = UUID.randomUUID().toString();
        ExperimentRun run = new ExperimentRun();
        run.runId = runId;
        run.status = "RUNNING";
        run.manifest = manifest;
        run.outputDir = outputDir;
        run.startedAt = LocalDateTime.now();
        runs.put(runId, run);

        CompletableFuture.runAsync(() -> execute(run));
        return run;
    }

    public ExperimentRun getRun(String runId) {
        return runs.get(runId);
    }

    public Map<String, ExperimentRun> listRuns() {
        return runs;
    }

    public Map<String, String> readSummaryFiles(String runId) throws IOException {
        ExperimentRun run = runs.get(runId);
        if (run == null) {
            return null;
        }
        Path summaryCsv = Path.of(run.outputDir, "summary_metrics.csv");
        Path summaryMd = Path.of(run.outputDir, "summary_table.md");

        Map<String, String> out = new HashMap<>();
        out.put("runId", runId);
        out.put("status", run.status);
        if (Files.exists(summaryCsv)) {
            out.put("summaryMetricsCsv", Files.readString(summaryCsv, StandardCharsets.UTF_8));
        }
        if (Files.exists(summaryMd)) {
            out.put("summaryTableMd", Files.readString(summaryMd, StandardCharsets.UTF_8));
        }
        return out;
    }

    private void execute(ExperimentRun run) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    pythonCmd,
                    scriptPath,
                    "--manifest", run.manifest,
                    "--output-dir", run.outputDir
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();

            run.finishedAt = LocalDateTime.now();
            if (code == 0) {
                run.status = "COMPLETED";
                run.message = output;
            } else {
                run.status = "FAILED";
                run.message = output;
            }
        } catch (Exception e) {
            run.status = "FAILED";
            run.message = e.getMessage();
            run.finishedAt = LocalDateTime.now();
        }
    }
}
