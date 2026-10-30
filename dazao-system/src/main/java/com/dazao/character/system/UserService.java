package com.dazao.character.system;

import com.dazao.character.common.ApiException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 独立账号的注册、认证查询与身份解析；不依赖 Web 层或角色数据。 */
@Service
public class UserService implements UserDetailsService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public UserService(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    public void register(String username, String password) {
        // BCrypt 按字节限制密码长度，避免多字节字符被截断或触发底层异常。
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码的 UTF-8 长度不能超过 72 字节");
        }
        jdbc.update("INSERT INTO studio_users(username,password_hash) VALUES (?,?)",
                username, passwords.encode(password));
    }

    public long owner(String username) {
        return jdbc.queryForObject("SELECT id FROM studio_users WHERE username=?", Long.class, username);
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbc.query("SELECT username, password_hash FROM studio_users WHERE username = ?",
                (rs, row) -> User.withUsername(rs.getString("username"))
                        .password(rs.getString("password_hash")).roles("USER").build(), username)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
    }
}
