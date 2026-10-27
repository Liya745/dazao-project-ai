package com.dazao.character.web;

import com.dazao.character.ai.AiGateway;
import com.dazao.character.ai.GenerationService;
import com.dazao.character.domain.CharacterDefinition;
import com.dazao.character.domain.StudioRepository;
import com.dazao.character.domain.StudioRepository.CharacterView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** 登录身份决定归属；角色卡导出不包含用户信息和密钥。 */
@RestController
public class CharacterController {

    private final StudioRepository repository;
    private final GenerationService generator;
    private final AiGateway ai;
    private final RequestBudget budget;

    public CharacterController(StudioRepository repository, GenerationService generator,
                               AiGateway ai, RequestBudget budget) {
        this.repository = repository;
        this.generator = generator;
        this.ai = ai;
        this.budget = budget;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return Map.of("aiConfigured", ai.configured(), "model", ai.modelName());
    }

    @PostMapping("/api/characters/generate")
    public CharacterDefinition generate(Principal principal,
                                        @Valid
                                        @RequestBody
                                        GenerateRequest request) {
        if ("ip".equals(request.sourceType()) && (request.work() == null || request.work().isBlank())) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "IP 角色请填写作品名称");
        }
        ai.requireConfigured();
        budget.consume(principal.getName());
        return generator.generate(request.brief(), request.current(), request.selectedFields(),
                request.lockedFields(), request.sourceType(), request.work(), request.canonVersion());
    }

    @GetMapping("/api/characters")
    public List<CharacterView> list(Principal principal) {
        return repository.characters(repository.owner(principal));
    }

    @PostMapping("/api/characters")
    public CharacterView create(Principal principal,
                                @Valid
                                @RequestBody
                                CharacterDefinition definition) {
        return repository.create(repository.owner(principal), definition);
    }

    @GetMapping("/api/characters/{id}")
    public CharacterView get(Principal principal,
                             @PathVariable
                             String id) {
        return repository.character(repository.owner(principal), id);
    }

    @PutMapping("/api/characters/{id}")
    public CharacterView update(Principal principal,
                                @PathVariable
                                String id,
                                @Valid
                                @RequestBody
                                UpdateRequest request) {
        return repository.update(repository.owner(principal), id, request.version(), request.definition());
    }

    @DeleteMapping("/api/characters/{id}")
    public void delete(Principal principal,
                       @PathVariable
                       String id) {
        repository.deleteCharacter(repository.owner(principal), id);
    }

    @PostMapping("/api/characters/{id}/copy")
    public CharacterView copy(Principal principal,
                             @PathVariable
                             String id) {
        long owner = repository.owner(principal);
        return repository.create(owner, repository.character(owner, id).definition());
    }

    @GetMapping(value = "/api/characters/{id}/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> export(Principal principal,
                                     @PathVariable
                                     String id) {
        CharacterView card = repository.character(repository.owner(principal), id);
        CharacterDefinition d = card.definition();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("name", d.name());
        data.put("description", d.description());
        data.put("personality", d.personality());
        data.put("scenario", text(d.scenario()));
        data.put("first_mes", d.firstMessage());
        data.put("mes_example", text(d.messageExample()));
        data.put("creator_notes", "");
        data.put("system_prompt", "");
        data.put("post_history_instructions", "");
        data.put("alternate_greetings", List.of());
        data.put("tags", List.of(d.sourceType()));
        data.put("creator", "Dazao Character Studio");
        data.put("character_version", String.valueOf(card.version()));
        data.put("extensions", Map.of("dazao", Map.of("appearance", text(d.appearance()),
                "speechStyle", text(d.speechStyle()), "work", text(d.work()), "canonVersion", text(d.canonVersion()))));
        return Map.of("spec", "chara_card_v2", "spec_version", "2.0", "data", data);
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record UpdateRequest(
            @Min(1)
            int version,
            @NotNull
            @Valid
            CharacterDefinition definition) {
    }

    public record GenerateRequest(
            @NotBlank(message = "请描述你想创建的角色")
            @Size(max = 6000)
            String brief,
            CharacterDefinition current,
            @Size(max = 8)
            List<String> selectedFields,
            @Size(max = 8)
            Set<String> lockedFields,
            @NotBlank
            @Pattern(regexp = "original|ip|assistant")
            String sourceType,
            @Size(max = 200)
            String work,
            @Size(max = 200)
            String canonVersion) {
    }
}
