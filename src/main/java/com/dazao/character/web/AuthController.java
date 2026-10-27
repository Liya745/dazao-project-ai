package com.dazao.character.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** 本地独立账号注册；不读取或迁移旧社区及 Saya 的账号数据。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
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
        // BCrypt 按字节限制密码长度，避免多字节字符被截断或触发底层异常。
        if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "密码的 UTF-8 长度不能超过 72 字节");
        }
        jdbc.update("INSERT INTO studio_users(username,password_hash) VALUES (?,?)",
                request.username(), passwords.encode(request.password()));
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
