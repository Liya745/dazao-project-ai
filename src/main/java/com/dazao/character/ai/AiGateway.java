package com.dazao.character.ai;

import com.dazao.character.web.ApiException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 真实模型调用边界；未配置时明确失败，不使用演示回复。 */
@Service
public class AiGateway {

    private final OpenAiChatModel generator;
    private final OpenAiStreamingChatModel chatter;
    private final String modelName;

    public AiGateway(
            @Value("${studio.ai.api-key}")
            String key,
            @Value("${studio.ai.base-url}")
            String baseUrl,
            @Value("${studio.ai.model}")
            String model) {
        modelName = model;
        if (key.isBlank()) {
            generator = null;
            chatter = null;
        } else {
            generator = OpenAiChatModel.builder().apiKey(key).baseUrl(baseUrl).modelName(model)
                    .responseFormat("json_object").temperature(0.8).maxTokens(3000)
                    .timeout(Duration.ofSeconds(90)).maxRetries(0).logRequests(false).logResponses(false).build();
            chatter = OpenAiStreamingChatModel.builder().apiKey(key).baseUrl(baseUrl).modelName(model)
                    .temperature(0.8).maxTokens(1200).timeout(Duration.ofSeconds(120))
                    .logRequests(false).logResponses(false).build();
        }
    }

    public boolean configured() {
        return generator != null;
    }

    public String modelName() {
        return modelName;
    }

    public void requireConfigured() {
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "模型尚未配置，请为后端设置 AI_API_KEY 后重启");
        }
    }

    public String generate(String instruction, String request) {
        requireConfigured();
        try {
            return generator.chat(List.of(SystemMessage.from(instruction), UserMessage.from(request)))
                    .aiMessage().text();
        } catch (Exception error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "角色生成暂时失败，请检查模型配置或稍后重试");
        }
    }

    public void stream(List<ChatMessage> messages, StreamingChatResponseHandler handler) {
        requireConfigured();
        chatter.chat(messages, handler);
    }
}
