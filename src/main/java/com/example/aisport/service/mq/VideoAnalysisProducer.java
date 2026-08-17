package com.example.aisport.service.mq;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.task.AnalysisTask;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class VideoAnalysisProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${mq.exchange.video-analysis}")
    private String exchange;

    @Value("${mq.routing-key.video-analysis}")
    private String routingKey;

    public VideoAnalysisProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendAnalysisTask(ExerciseVideo video, AnalysisTask task) {
        Map<String, Object> message = new HashMap<>();
        message.put("videoId", video.getId());
        message.put("taskId", task.getId());
        message.put("userId", video.getUser().getId());
        message.put("videoPath", video.getStoredFilePath());
        message.put("exerciseType", video.getExerciseType());
        message.put("originalFileName", video.getOriginalFileName());
        message.put("timestamp", System.currentTimeMillis());
        message.put("correlationId", task.getCorrelationId());
        // 追踪字段：同一条消息重投时 messageId 不变，便于去重与排查
        message.put("messageId", UUID.randomUUID().toString());
        message.put("schemaVersion", 1);
        message.put("attempt", task.getAttempt());

        rabbitTemplate.convertAndSend(exchange, routingKey, message);
    }
}