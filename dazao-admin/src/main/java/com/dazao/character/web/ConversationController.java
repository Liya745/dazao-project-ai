package com.dazao.character.web;

import com.dazao.character.system.UserService;
import com.dazao.character.service.ConversationService;
import com.dazao.character.domain.StudioRepository;
import com.dazao.character.domain.StudioRepository.ConversationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 会话采用角色快照；失败轮次保留记录但不进入模型上下文。 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final StudioRepository repository;
    private final UserService users;
    private final ConversationService conversations;

    public ConversationController(StudioRepository repository, UserService users, ConversationService conversations) {
        this.repository = repository;
        this.users = users;
        this.conversations = conversations;
    }

    @GetMapping
    public List<ConversationView> list(Principal principal) {
        return repository.conversations(users.owner(principal.getName()));
    }

    @PostMapping
    public ConversationView create(Principal principal,
                                   @Valid
                                   @RequestBody
                                   NewConversation request) {
        return repository.createConversation(users.owner(principal.getName()), request.characterId());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(Principal principal,
                                   @PathVariable
                                   String id) {
        long owner = users.owner(principal.getName());
        return Map.of("conversation", repository.conversation(owner, id), "turns", repository.turns(owner, id));
    }

    @DeleteMapping("/{id}")
    public void delete(Principal principal,
                       @PathVariable
                       String id) {
        repository.deleteConversation(users.owner(principal.getName()), id);
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter message(Principal principal,
                              @PathVariable
                              String id,
                              @Valid
                              @RequestBody
                              NewMessage request) {
        SseEmitter emitter = new SseEmitter(150_000L);
        conversations.message(users.owner(principal.getName()), principal.getName(), id, request.content(),
                new ConversationService.ReplySink() {
                    @Override
                    public void onCancel(Runnable cancel) {
                        emitter.onTimeout(cancel);
                        emitter.onError(error -> cancel.run());
                        emitter.onCompletion(cancel);
                    }

                    @Override
                    public void delta(String text) throws Exception {
                        emitter.send(SseEmitter.event().name("delta").data(Map.of("text", text)));
                    }

                    @Override
                    public void finished(String turnId, String content, boolean success) throws Exception {
                        emitter.send(SseEmitter.event().name(success ? "done" : "error")
                                .data(success ? Map.of("turnId", turnId, "content", content)
                                        : Map.of("message", "回复未完成，输入已保留，可重试")));
                    }

                    @Override
                    public void complete() {
                        emitter.complete();
                    }
                });
        return emitter;
    }

    public record NewConversation(
            @NotBlank
            @Size(max = 36)
            String characterId) {
    }

    public record NewMessage(
            @NotBlank(message = "请输入消息")
            @Size(max = 4000, message = "消息最多 4000 字")
            String content) {
    }
}
