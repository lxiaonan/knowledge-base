package com.company.knowledge;

import java.util.Map;
import java.util.concurrent.Semaphore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/chat")
public class SettingsController {
    private final Access access;
    private final ChatSettings settings;
    private final AiService ai;
    private final Semaphore testCapacity=new Semaphore(1);
    public record Input(@NotBlank @Size(max=500) String baseUrl, @NotBlank @Size(max=120) String model,
                        @Size(max=4096) String apiKey) {
        @Override public String toString() { return "ChatSettingsInput[apiKey=REDACTED]"; }
    }
    public SettingsController(Access access,ChatSettings settings,AiService ai) {this.access=access;this.settings=settings;this.ai=ai;}
    private void admin() { if (!"ADMIN".equals(access.current().role())) throw ApiException.forbidden(); }
    @GetMapping ChatSettings.View get() {admin();return settings.view();}
    @PutMapping ChatSettings.View save(@Valid @RequestBody Input input) {
        admin();return settings.save(settings.resolve(input.baseUrl(),input.model(),input.apiKey()));
    }
    @DeleteMapping("/key") ChatSettings.View clear() {admin();return settings.clearKey();}
    @PostMapping("/test") Map<String,String> test(@Valid @RequestBody Input input) {
        admin();
        var config=settings.resolve(input.baseUrl(),input.model(),input.apiKey());
        if (!config.configured()) throw ApiException.bad("请先填写 API 密钥");
        if (!testCapacity.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"正在测试连接，请稍后重试");
        try { ai.testConnection(config);return Map.of("message","连接成功，指定模型已返回响应"); }
        finally {testCapacity.release();}
    }
}
