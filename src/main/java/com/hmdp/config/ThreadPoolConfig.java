package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ThreadPoolConfig {
    @Bean(destroyMethod = "shutdown")  // 关闭时自动调用shutdown()
    public ExecutorService seckillOrderExecutor() {
        return Executors.newSingleThreadExecutor();
        // 你也可以换成线程池工厂方法，或ThreadPoolExecutor自定义配置
    }
}