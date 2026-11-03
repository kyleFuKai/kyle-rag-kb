package com.kyle.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * Spring AI 对话客户端配置:注册全局共用的 ChatClient,供 RAG 问答链路拼 prompt + 调模型。
 *
 * 为什么必须 @Qualifier:容器里有两个 ChatModel——
 *   miniMaxChatModel(对话要用) 和 openAiChatModel(为用百炼 embedding 引入 openai starter 时顺带注册的),
 * 裸注 ChatModel 启动即 NoUniqueBeanDefinitionException(实测验证过)。删 Qualifier 前先想清楚。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/3 23:59
 */
@Configuration
public class SpringAiConfig {

    /** 全局对话入口,底层绑定 MiniMax;system prompt / 温度等在调用处按需指定 */
    @Bean
    public ChatClient chatClient(@Qualifier("miniMaxChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .build();
    }
}
