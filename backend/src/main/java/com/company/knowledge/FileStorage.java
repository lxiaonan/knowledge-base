package com.company.knowledge;

import java.io.IOException;
import java.nio.file.*;
import io.minio.*;
import org.springframework.stereotype.Component;

@Component
public class FileStorage {
    private final AppProperties.Storage config;
    private final MinioClient minio;
    public FileStorage(AppProperties properties) throws Exception {
        config = properties.storage();
        if ("minio".equals(config.type())) {
            minio = MinioClient.builder().endpoint(config.endpoint()).credentials(config.accessKey(), config.secretKey()).build();
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(config.bucket()).build()))
                minio.makeBucket(MakeBucketArgs.builder().bucket(config.bucket()).build());
        } else if ("local".equals(config.type())) {
            minio = null;
            Files.createDirectories(Path.of(config.path()));
        } else throw new IllegalArgumentException("STORAGE_TYPE must be local or minio");
    }
    private Path local(String key) {
        if (!key.matches("[a-f0-9-]{36}\\.[a-z0-9]+")) throw new IllegalArgumentException("Invalid storage key");
        Path root = Path.of(config.path()).toAbsolutePath().normalize();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Invalid storage path");
        return target;
    }
    public void put(String key, byte[] bytes, String contentType) throws Exception {
        if (minio != null) {
            minio.putObject(PutObjectArgs.builder().bucket(config.bucket()).object(key)
                .stream(new java.io.ByteArrayInputStream(bytes), bytes.length, -1).contentType(contentType).build());
        } else {
            Path destination = local(key);
            Path temp = Files.createTempFile(destination.getParent(), "upload-", ".tmp");
            try { Files.write(temp, bytes); Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
        }
    }
    public byte[] read(String key) throws Exception {
        if (minio == null) return Files.readAllBytes(local(key));
        try (var stream = minio.getObject(GetObjectArgs.builder().bucket(config.bucket()).object(key).build())) {
            return stream.readAllBytes();
        }
    }
    public void delete(String key) throws Exception {
        if (minio == null) Files.deleteIfExists(local(key));
        else minio.removeObject(RemoveObjectArgs.builder().bucket(config.bucket()).object(key).build());
    }
}
