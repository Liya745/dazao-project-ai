package com.dazao.character;

import com.dazao.character.ai.AiGateway;
import com.dazao.character.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证认证、归属、版本、锁定和 SSE 持久化；模型替身只存在于测试类路径。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:studio_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "studio.ai.api-key="
})
@AutoConfigureMockMvc
class StudioIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private AiGateway ai;

    private MockHttpSession owner;

    @BeforeEach
    void login() throws Exception {
        when(ai.configured()).thenReturn(true);
        when(ai.modelName()).thenReturn("test-model");
        owner = account();
    }

    @Test
    void authenticationRequiresLoginAndCsrfAndRejectsInvalidRegistration() throws Exception {
        mvc.perform(get("/api/characters")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/characters").session(owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(definition())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "invalid_long_password", "password", "密".repeat(30)))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/session").session(owner))
                .andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(post("/api/auth/logout").session(owner).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void usersCannotReadChangeExportOrDeleteEachOthersAssets() throws Exception {
        String role = createCharacter().path("id").asText();
        String chat = createConversation(role).path("id").asText();
        MockHttpSession other = account();
        for (String path : List.of("/api/characters/" + role, "/api/characters/" + role + "/export",
                "/api/conversations/" + chat)) {
            mvc.perform(get(path).session(other)).andExpect(status().isNotFound());
        }
        mvc.perform(put("/api/characters/" + role).session(other).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", 1, "definition", definition()))))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/characters/" + role).session(other).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/conversations/" + chat + "/messages").session(other).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"你好\"}"))
                .andExpect(status().isNotFound());
        verify(ai, never()).stream(any(), any());
    }

    @Test
    void staleUpdatesConflictAndExistingConversationsKeepTheirSnapshot() throws Exception {
        String role = createCharacter().path("id").asText();
        String chat = createConversation(role).path("id").asText();
        Map<String, Object> changed = new java.util.LinkedHashMap<>(definition());
        changed.put("name", "新版本名字");
        String update = json.writeValueAsString(Map.of("version", 1, "definition", changed));
        mvc.perform(put("/api/characters/" + role).session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(put("/api/characters/" + role).session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isConflict());
        mvc.perform(get("/api/conversations/" + chat).session(owner))
                .andExpect(jsonPath("$.conversation.definition.name").value("灯下"))
                .andExpect(jsonPath("$.conversation.characterVersion").value(1));
        assertThat(createConversation(role).path("definition").path("name").asText()).isEqualTo("新版本名字");
    }

    @Test
    void generationOnlyMergesSelectedUnlockedFieldsEvenIfModelOverwritesOthers() throws Exception {
        when(ai.generate(anyString(), anyString())).thenReturn("{\"name\":\"不应写回\",\"personality\":\"热情但克制\",\"description\":\"也不应写回\"}");
        Map<String, Object> request = Map.of("brief", "更热情一点", "sourceType", "original", "current", definition(),
                "selectedFields", List.of("name", "personality"), "lockedFields", List.of("name"));
        mvc.perform(post("/api/characters/generate").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("灯下"))
                .andExpect(jsonPath("$.description").value("在深夜修复旧书的猫妖"))
                .andExpect(jsonPath("$.personality").value("热情但克制"));
    }

    @Test
    void lockedOnlyRequestsDoNotCallModelAndInvalidOutputReturnsSafeError() throws Exception {
        mvc.perform(post("/api/characters/generate").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("brief", "改一下", "sourceType", "original", "current", definition(),
                        "selectedFields", List.of("name"), "lockedFields", List.of("name")))))
                .andExpect(status().isBadRequest());
        verify(ai, never()).generate(anyString(), anyString());
        when(ai.generate(anyString(), anyString())).thenReturn("secret-provider-debug-not-json");
        MvcResult invalid = mvc.perform(post("/api/characters/generate").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"brief\":\"创建角色\",\"sourceType\":\"original\"}"))
                .andExpect(status().isBadGateway()).andReturn();
        assertThat(invalid.getResponse().getContentAsString()).doesNotContain("secret-provider-debug");
    }

    @Test
    void exportUsesCharacterCardV2AndDeletingCharacterRemovesItsConversations() throws Exception {
        String role = createCharacter().path("id").asText();
        String chat = createConversation(role).path("id").asText();
        mvc.perform(get("/api/characters/" + role + "/export").session(owner))
                .andExpect(jsonPath("$.spec").value("chara_card_v2"))
                .andExpect(jsonPath("$.data.first_mes").value("书还没有修好，先坐一会儿吧。"))
                .andExpect(jsonPath("$.data.extensions.dazao.speechStyle").value("短句，冷幽默"))
                .andExpect(jsonPath("$.data.ownerId").doesNotExist());
        mvc.perform(delete("/api/characters/" + role).session(owner).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/conversations/" + chat).session(owner)).andExpect(status().isNotFound());
    }

    @Test
    void streamSetupFailureReturnsReadableJsonEvenForSseAccept() throws Exception {
        String chat = createConversation(createCharacter().path("id").asText()).path("id").asText();
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "模型尚未配置"))
                .when(ai).requireConfigured();
        mvc.perform(post("/api/conversations/" + chat + "/messages").session(owner).with(csrf())
                .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"你好\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("模型尚未配置"));
    }

    @Test
    void streamPersistsCompletedTurnsAndUsesCharacterContext() throws Exception {
        String chat = createConversation(createCharacter().path("id").asText()).path("id").asText();
        doAnswer(call -> {
            assertThat(call.getArgument(0, List.class).toString()).contains("灯下").contains("你好");
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onPartialResponse("来，坐吧。");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("来，坐吧。")).build());
            return null;
        }).when(ai).stream(any(), any());
        MvcResult result = mvc.perform(post("/api/conversations/" + chat + "/messages").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"你好\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        MvcResult completed = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
        assertThat(completed.getResponse().getContentAsString()).contains("event:delta").contains("event:done");
        mvc.perform(get("/api/conversations/" + chat).session(owner))
                .andExpect(jsonPath("$.turns[0].status").value("DONE"))
                .andExpect(jsonPath("$.turns[0].assistantContent").value("来，坐吧。"));
    }

    @Test
    void failedStreamRetainsUserInputAndDoesNotIncludeFailureInLaterContext() throws Exception {
        String chat = createConversation(createCharacter().path("id").asText()).path("id").asText();
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onPartialResponse("未完成的半句话");
            handler.onError(new RuntimeException("secret-provider-error"));
            return null;
        }).when(ai).stream(any(), any());
        MvcResult result = mvc.perform(post("/api/conversations/" + chat + "/messages").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"失败轮用户输入\"}"))
                .andReturn();
        result.getAsyncResult(5000);
        String response = mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("event:error").doesNotContain("secret-provider-error");
        mvc.perform(get("/api/conversations/" + chat).session(owner))
                .andExpect(jsonPath("$.turns[0].status").value("FAILED"))
                .andExpect(jsonPath("$.turns[0].userContent").value("失败轮用户输入"));
        doAnswer(call -> {
            assertThat(call.getArgument(0, List.class).toString()).doesNotContain("失败轮用户输入");
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("这次完成了")).build());
            return null;
        }).when(ai).stream(any(), any());
        MvcResult retry = mvc.perform(post("/api/conversations/" + chat + "/messages").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"新的消息\"}"))
                .andReturn();
        retry.getAsyncResult(5000);
        mvc.perform(asyncDispatch(retry)).andExpect(status().isOk());
    }

    @Test
    void duplicateAccountRegistrationKeepsExistingCredentials() throws Exception {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", username,
                            "password", attempt == 0 ? "original-password" : "replacement-password"))))
                    .andExpect(status().is(attempt == 0 ? 200 : 409));
        }
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", username)
                .param("password", "original-password")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", username)
                .param("password", "replacement-password")).andExpect(status().isUnauthorized());
    }

    @Test
    void pendingReplyBlocksConcurrentMessagesAndDeletionThenReleasesConversation() throws Exception {
        String role = createCharacter().path("id").asText();
        String chat = createConversation(role).path("id").asText();
        var responseHandler = new java.util.concurrent.atomic.AtomicReference<StreamingChatResponseHandler>();
        doAnswer(call -> {
            responseHandler.set(call.getArgument(1));
            return null;
        }).when(ai).stream(any(), any());
        MvcResult pending = mvc.perform(post("/api/conversations/" + chat + "/messages")
                .session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"等待回复\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        try {
            mvc.perform(post("/api/conversations/" + chat + "/messages").session(owner).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"重复消息\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(delete("/api/conversations/" + chat).session(owner).with(csrf()))
                    .andExpect(status().isConflict());
            mvc.perform(delete("/api/characters/" + role).session(owner).with(csrf()))
                    .andExpect(status().isConflict());
        } finally {
            responseHandler.get().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("完成")).build());
        }
        // 供应商终止回调重复到达时，不能将成功轮次改为失败或重复释放并发许可。
        responseHandler.get().onError(new RuntimeException("late-provider-callback"));
        pending.getAsyncResult(5000);
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
        mvc.perform(get("/api/conversations/" + chat).session(owner))
                .andExpect(jsonPath("$.turns.length()").value(1))
                .andExpect(jsonPath("$.turns[0].status").value("DONE"));
        mvc.perform(delete("/api/conversations/" + chat).session(owner).with(csrf()))
                .andExpect(status().isOk());
    }

    private MockHttpSession account() throws Exception {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", "test-password-123"))))
                .andExpect(status().isOk());
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf()).param("username", username)
                .param("password", "test-password-123")).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private JsonNode createCharacter() throws Exception {
        MvcResult result = mvc.perform(post("/api/characters").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(definition())))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode createConversation(String character) throws Exception {
        MvcResult result = mvc.perform(post("/api/conversations").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("characterId", character))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> definition() {
        return Map.of("name", "灯下", "description", "在深夜修复旧书的猫妖", "personality", "外冷内热",
                "scenario", "深夜书店", "firstMessage", "书还没有修好，先坐一会儿吧。", "messageExample", "用户：谢谢。角色：灯还亮着。",
                "appearance", "灰色长衣", "speechStyle", "短句，冷幽默", "sourceType", "original");
    }
}
