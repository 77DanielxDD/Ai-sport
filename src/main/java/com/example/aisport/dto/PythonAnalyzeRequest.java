package com.example.aisport.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PythonAnalyzeRequest {
    private Long video_id;
    private String video_path;
    private String exercise_type;
}
