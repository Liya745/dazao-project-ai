package com.dazao.character.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import com.dazao.character.system.UserService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** 本地独立账号注册；不读取或迁移旧社区及 Saya 的账号数据。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @GetMapping("/session")
    public Map<String, Object> session(Principal principal) {
        return Map.of("authenticated", principal != null, "username",
                principal == null ? "" : principal.getName());
    }

    @PostMapping("/register")
    public Map<String, String> register(
            @Valid
            @RequestBody
            RegisterRequest request) {
        users.register(request.username(), request.password());
        return Map.of("username", request.username());
    }

    public record RegisterRequest(
            @NotBlank(message = "请填写用户名")
            @Pattern(regexp = "[a-zA-Z0-9_]{3,32}", message = "用户名应为 3～32 位字母、数字或下划线")
            String username,
            @NotBlank(message = "请填写密码")
            @Size(min = 8, max = 64, message = "密码长度应为 8～64 位")
            String password) {
    }
}
