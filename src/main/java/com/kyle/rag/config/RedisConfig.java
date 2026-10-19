package com.kyle.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 自定义 RedisTemplate：key 用字符串（redis-cli 里可读），value 用 JSON 序列化
 * （替代默认的 JDK 二进制），并写入 @class 类型信息以便反序列化还原 DTO。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/19 19:42
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        ObjectMapper mapper = new ObjectMapper();
        // 注册 Java8 时间模块，DTO 里的 LocalDateTime 才能正常序列化
        mapper.findAndRegisterModules();
        // 带 @class 类型信息；校验器只放行自己的包和常用集合，
        // 防 Jackson 反序列化 gadget 攻击（Redis 值被注入时不会实例化任意类）
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.kyle.rag.")
                        .allowIfSubType("java.util.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL
        );

        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(mapper);

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        return template;
    }
}
