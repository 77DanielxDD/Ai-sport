package com.example.aisport.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // ========== 队列名称配置 ==========
    @Value("${mq.queue.video-analysis}")
    private String videoAnalysisQueue;

    @Value("${mq.queue.video-analysis.dlq}")
    private String videoAnalysisDLQ;

    @Value("${mq.exchange.video-analysis}")
    private String videoAnalysisExchange;

    @Value("${mq.routing-key.video-analysis}")
    private String videoAnalysisRoutingKey;

    // ========== 队列定义 ==========

    /**
     * 视频分析主队列
     */
    @Bean
    public Queue videoAnalysisQueue() {
        return QueueBuilder.durable(videoAnalysisQueue)
                .withArgument("x-dead-letter-exchange", "") // 死信交换机
                .withArgument("x-dead-letter-routing-key", videoAnalysisDLQ) // 死信队列
                .withArgument("x-message-ttl", 300000) // 5分钟过期
                .build();
    }

    /**
     * 死信队列（处理失败的消息）
     */
    @Bean
    public Queue videoAnalysisDLQ() {
        return new Queue(videoAnalysisDLQ, true);
    }

    /**
     * 视频分析交换机（直连模式）
     */
    @Bean
    public DirectExchange videoAnalysisExchange() {
        return new DirectExchange(videoAnalysisExchange, true, false);
    }

    /**
     * 绑定关系：队列 -> 交换机
     */
    @Bean
    public Binding videoAnalysisBinding(Queue videoAnalysisQueue, DirectExchange videoAnalysisExchange) {
        return BindingBuilder.bind(videoAnalysisQueue)
                .to(videoAnalysisExchange)
                .with(videoAnalysisRoutingKey);
    }

    /**
     * 死信队列绑定
     */
    @Bean
    public Binding dlqBinding(Queue videoAnalysisDLQ, DirectExchange videoAnalysisExchange) {
        return BindingBuilder.bind(videoAnalysisDLQ)
                .to(videoAnalysisExchange)
                .with(videoAnalysisDLQ().getName());
    }

    // ========== 消息序列化配置 ==========

    /**
     * JSON消息转换器
     */
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate配置
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter());

        // 设置消息确认回调
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                System.out.println("消息发送成功，消息ID：" + (correlationData != null ? correlationData.getId() : "null"));
            } else {
                System.err.println("消息发送失败，原因：" + cause);
            }
        });

        // 设置消息返回回调
        rabbitTemplate.setReturnsCallback(returned -> {
            System.err.println("消息被退回：" + returned.getMessage());
            System.err.println("退回原因：" + returned.getReplyText());
            System.err.println("交换机：" + returned.getExchange());
            System.err.println("路由键：" + returned.getRoutingKey());
        });

        return rabbitTemplate;
    }

    /**
     * 消费者容器工厂配置
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setConcurrentConsumers(3); // 并发消费者数
        factory.setMaxConcurrentConsumers(10); // 最大并发消费者数
        factory.setPrefetchCount(1); // 每次预取消息数
        factory.setDefaultRequeueRejected(false); // 拒绝时不重新入队
        return factory;
    }
}