package com.company.knowledge;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Administrator configuration; secrets are encrypted at rest and are never returned by the API. */
@Service
public class ChatSettings {
    private static final String SETTING = "chat_config_encrypted";
    private final Repository repo;
    private final AppProperties defaults;
    private final Path keyFile;
    private final ObjectMapper json = new ObjectMapper();
    private volatile Config cached;
    public record Config(String baseUrl, String model, String apiKey) {
        public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
        @Override public String toString() { return "ChatConfig[model="+model+", apiKey=REDACTED]"; }
    }
    public record View(String baseUrl, String model, boolean keyConfigured) {}
    public ChatSettings(Repository repo, AppProperties defaults,
                        @Value("${app.settings-key-path:./data/config/settings.key}") String keyPath) {
        this.repo=repo; this.defaults=defaults; this.keyFile=Path.of(keyPath).toAbsolutePath().normalize();
    }
    public Config current() {
        var result=cached;
        if (result==null) synchronized(this) {
            result=cached;
            if (result==null) {
                var stored=repo.db().sql("SELECT setting_value FROM app_setting WHERE setting_key=?").param(SETTING).query(String.class).optional();
                result=stored.map(this::decrypt).orElseGet(() -> new Config(defaults.ai().baseUrl(),defaults.ai().chatModel(),defaults.ai().apiKey()));
                cached=result;
            }
        }
        return result;
    }
    public View view() { var config=current(); return new View(config.baseUrl(),config.model(),config.configured()); }
    public Config resolve(String baseUrl,String model,String apiKey) {
        String endpoint=baseUrl==null ? "" : baseUrl.strip();
        try {
            URI uri=URI.create(endpoint);
            if (!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw ApiException.bad("请输入有效的 API 根地址，例如 https://ai-api.m2time.com，不包含 /v1"); }
        String modelId=model==null ? "" : model.strip();
        if (!modelId.matches("[a-zA-Z0-9_.:/-]{1,120}")) throw ApiException.bad("模型名称格式不正确");
        String key=apiKey==null || apiKey.isBlank() ? current().apiKey() : apiKey.strip();
        if (key!=null && (key.length()>4096 || key.chars().anyMatch(c -> c<33 || c>126))) throw ApiException.bad("密钥格式不正确，请检查是否包含空格或换行");
        return new Config(endpoint.replaceAll("/+$",""),modelId,key==null?"":key);
    }
    public synchronized View save(Config config) {
        String encrypted=encrypt(config);
        if (repo.db().sql("UPDATE app_setting SET setting_value=? WHERE setting_key=?").params(encrypted,SETTING).update()==0)
            repo.db().sql("INSERT INTO app_setting(setting_key,setting_value) VALUES (?,?)").params(SETTING,encrypted).update();
        cached=config;
        return view();
    }
    public synchronized View clearKey() {
        var current=current();
        return save(new Config(current.baseUrl(),current.model(),""));
    }
    private byte[] masterKey(boolean create) throws Exception {
        if (!Files.exists(keyFile) && create) {
            Files.createDirectories(keyFile.getParent());
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
            try { Files.write(keyFile,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE); }
            catch (FileAlreadyExistsException ignored) { /* Another instance initialized it. */ }
            if (keyFile.getFileSystem().supportedFileAttributeViews().contains("posix"))
                Files.setPosixFilePermissions(keyFile,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
        byte[] bytes=Files.readAllBytes(keyFile);
        if (bytes.length!=32) throw new IllegalStateException("Invalid local encryption key");
        return bytes;
    }
    private String encrypt(Config config) {
        try {
            byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(masterKey(true),"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(SETTING.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(json.writeValueAsBytes(config));
            return "v1:"+Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(encrypted);
        } catch(Exception e) { throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,"无法保存模型配置，请检查本地配置目录是否可写"); }
    }
    private Config decrypt(String value) {
        try {
            String[] parts=value.split(":",-1);
            if (parts.length!=3 || !parts[0].equals("v1")) throw new IllegalStateException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(masterKey(false),"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[1])));
            cipher.updateAAD(SETTING.getBytes(StandardCharsets.UTF_8));
            return json.readValue(cipher.doFinal(Base64.getDecoder().decode(parts[2])),Config.class);
        } catch(Exception e) { throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,"无法读取已保存的模型配置，请恢复对应的本地配置加密文件"); }
    }
}
