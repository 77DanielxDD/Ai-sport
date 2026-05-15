// 1. 包声明：必须与物理路径匹配
package com.example.aisport.controller;

// 2. 导入注解
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 3. 类注解和定义
@RestController
@RequestMapping("/api/test")
public class TestController { // 注意：类名是 TestController

    @GetMapping("/hello")
    public String hello() {
        return "✅ 健身动作分析系统后端服务已成功启动！";
    }
}