package com.example.aisport.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class PythonAnalyzeResponse {
    public Long video_id;
    public String exercise_type;
    public Integer rep_count;
    public List<Map<String, Object>> rep_events;
    public Integer processing_time_ms;
    public String result_json;
}
