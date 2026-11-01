package com.example.meetplatform.common.storage;

import com.example.meetplatform.common.exception.ApiException;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves objects of {@link LocalFileObjectStorage} through its signed URLs (the local stand-in for S3 presigned
 * URLs). Public like a presigned URL: the signature is the authorization. Range requests are supported, so
 * recordings can be seeked in the browser's video player.
 */
@Hidden
@RestController
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "local")
public class LocalStorageController {

    private final LocalFileObjectStorage storage;

    public LocalStorageController(LocalFileObjectStorage storage) {
        this.storage = storage;
    }

    @GetMapping(LocalFileObjectStorage.DOWNLOAD_PATH)
    public ResponseEntity<Resource> download(@RequestParam String bucket, @RequestParam String key,
                                             @RequestParam long expires,
                                             @RequestParam(required = false) String name,
                                             @RequestParam String sig) {
        Path path = storage.verifiedPath(bucket, key, expires, name, sig);
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("File");
        }
        boolean attachment = name != null && !name.isEmpty();
        MediaType type = MediaTypeFactory.getMediaType(attachment ? name : path.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noStore().cachePrivate());
        if (attachment) {
            response.header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString());
        }
        return response.body(new FileSystemResource(path));
    }
}
