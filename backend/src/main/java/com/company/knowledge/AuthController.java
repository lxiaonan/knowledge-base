package com.company.knowledge;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
    private final Access access;
    private final Repository repo;
    private final UserDetailsService users;
    private final PasswordEncoder passwords;
    private final HttpSessionSecurityContextRepository contexts;
    private final AppProperties properties;
    private final AiService ai;
    public AuthController(Access access, Repository repo, UserDetailsService users, PasswordEncoder passwords,
                          HttpSessionSecurityContextRepository contexts, AppProperties properties, AiService ai) {
        this.access=access; this.repo=repo; this.users=users; this.passwords=passwords; this.contexts=contexts; this.properties=properties;
        this.ai=ai;
    }
    public record Login(@NotBlank @Size(max=64) String username, @NotBlank @Size(max=128) String password) {}
    public record NewUser(@Pattern(regexp="[a-zA-Z0-9_.-]{3,64}") @NotBlank String username,
                          @NotBlank @Size(max=80) String displayName, @NotBlank @Size(min=10, max=72) String password) {}
    @GetMapping("/api/auth/csrf") Map<String,String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @PostMapping("/api/auth/login") Models.User login(@Valid @RequestBody Login login, HttpServletRequest req, HttpServletResponse res) {
        org.springframework.security.core.userdetails.UserDetails details;
        try { details = users.loadUserByUsername(login.username().strip()); }
        catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) { throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "账号或密码不正确"); }
        if (!passwords.matches(login.password(), details.getPassword()))
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "账号或密码不正确");
        req.getSession();
        req.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, req, res);
        new HttpSessionCsrfTokenRepository().saveToken(null, req, res);
        return access.current();
    }
    @GetMapping("/api/auth/me") Models.User me() { return access.current(); }
    @PostMapping("/api/users") Models.User createUser(@Valid @RequestBody NewUser input) {
        if (!"ADMIN".equals(access.current().role())) throw ApiException.forbidden();
        if (input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw ApiException.bad("密码的 UTF-8 编码不能超过 72 字节");
        String id = Repository.id();
        repo.db().sql("INSERT INTO app_user(id, username, display_name, password_hash, role) VALUES (?, ?, ?, ?, 'MEMBER')")
            .params(id, input.username(), input.displayName().strip(), passwords.encode(input.password())).update();
        return repo.findUser(input.username());
    }
    @GetMapping("/api/system") Map<String,Object> system() {
        return Map.of("name", "知序 · 企业知识库", "demo", properties.demo(), "formats", new String[]{"pdf", "docx", "md", "txt"},
            "maxFileSizeMb",20, "model", properties.demo() ? "原文检索演示" : ai.chatModelName(),
            "chatConfigured",ai.chatConfigured(),
            "embeddingModel",properties.demo() ? "文本匹配" : properties.ai().embeddingModel());
    }
}
