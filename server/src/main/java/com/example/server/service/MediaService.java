package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.AnalysisRedisKeys;
import com.example.server.utils.MinioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    //注入数据库操作接口 (MyBatis-Plus 自动代理)
    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MinioUtils minioUtils;

    private final String UPLOAD_DIR = "D:/Project/MediaApp/uploads/";
    private static final String CHUNK_UPLOAD_KEY_PREFIX = "upload:chunked:";
    static final long CONTENT_HASH_CACHE_HOURS = 24;
    private static final Path CHUNK_UPLOAD_DIR = Path.of(System.getProperty("java.io.tmpdir"), "dovideo-chunks");

    public MediaService() {
        File dir = new File(UPLOAD_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    public String initChunkedUpload(String filename, int totalChunks, Long userId) throws IOException {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename is required");
        }
        if (totalChunks <= 0) {
            throw new IllegalArgumentException("totalChunks must be greater than 0");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }

        String uploadId = UUID.randomUUID().toString();
        String redisKey = CHUNK_UPLOAD_KEY_PREFIX + uploadId;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("filename", filename);
        metadata.put("totalChunks", String.valueOf(totalChunks));
        metadata.put("userId", String.valueOf(userId));
        redisTemplate.opsForHash().putAll(redisKey, metadata);
        refreshChunkUploadTtl(uploadId);
        Files.createDirectories(uploadDirectory(uploadId));
        return uploadId;
    }

    public Set<Integer> getUploadedChunks(String uploadId, Long currentUserId) {
        requireOwnedUpload(uploadId, currentUserId);
        return getUploadedChunks(uploadId);
    }

    private Set<Integer> getUploadedChunks(String uploadId) {
        Set<String> members = redisTemplate.opsForSet().members(partsKey(uploadId));
        Set<Integer> result = new TreeSet<>();
        if (members != null) {
            for (String member : members) {
                result.add(Integer.parseInt(member));
            }
        }
        return result;
    }

    public void uploadChunk(String uploadId,
                            int chunkIndex,
                            int totalChunks,
                            MultipartFile chunk,
                            Long currentUserId) throws IOException {
        if (chunk == null || chunk.isEmpty()) {
            throw new IllegalArgumentException("chunk is empty");
        }

        Map<Object, Object> metadata = requireOwnedUpload(uploadId, currentUserId);
        int expectedChunks = Integer.parseInt(String.valueOf(metadata.get("totalChunks")));
        if (totalChunks != expectedChunks || chunkIndex < 0 || chunkIndex >= expectedChunks) {
            throw new IllegalArgumentException("invalid chunk index or totalChunks");
        }

        Path directory = uploadDirectory(uploadId);
        Files.createDirectories(directory);
        chunk.transferTo(chunkPath(directory, chunkIndex));
        redisTemplate.opsForSet().add(partsKey(uploadId), String.valueOf(chunkIndex));
        refreshChunkUploadTtl(uploadId);
    }

    public MediaFile completeChunkedUpload(String uploadId, Long currentUserId) throws Exception {
        Map<Object, Object> metadata = requireOwnedUpload(uploadId, currentUserId);
        String filename = String.valueOf(metadata.get("filename"));
        int totalChunks = Integer.parseInt(String.valueOf(metadata.get("totalChunks")));
        Path directory = uploadDirectory(uploadId);

        Set<Integer> uploadedChunks = getUploadedChunks(uploadId);
        if (uploadedChunks.size() != totalChunks) {
            throw new IllegalStateException("not all chunks have been uploaded");
        }

        Path mergedFile = directory.resolve("merged" + fileSuffix(filename));
        MessageDigest digest = md5Digest();
        try (OutputStream fileOutput = Files.newOutputStream(mergedFile);
             DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
             BufferedOutputStream output = new BufferedOutputStream(digestOutput)) {
            for (int i = 0; i < totalChunks; i++) {
                Path part = chunkPath(directory, i);
                if (!Files.isRegularFile(part)) {
                    throw new IllegalStateException("missing chunk: " + i);
                }
                Files.copy(part, output);
            }
        }
        String contentHash = HexFormat.of().formatHex(digest.digest());

        String fileUrl = minioUtils.uploadLocalFile(mergedFile.toFile(), filename);
        try {
            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(filename);
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUploadTime(LocalDateTime.now());
            Object userId = metadata.get("userId");
            if (userId != null) {
                mediaFile.setUserId(Long.valueOf(String.valueOf(userId)));
            }
            mediaFile.setContentHash(contentHash);
            mediaFileMapper.insert(mediaFile);

            rememberContentHash(mediaFile.getId(), contentHash);
            if (mediaFile.getUserId() != null) {
                redisTemplate.delete("media:list:user:" + mediaFile.getUserId());
            }
            cleanupUpload(uploadId);
            return mediaFile;
        } catch (Exception e) {
            minioUtils.removeFile(fileUrl);
            throw e;
        }
    }

    public String calculateMd5(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return calculateMd5(inputStream);
        }
    }

    public String calculateMd5(File file) throws IOException {
        try (InputStream inputStream = Files.newInputStream(file.toPath())) {
            return calculateMd5(inputStream);
        }
    }

    public String calculateStoredContentHash(MediaFile mediaFile) throws Exception {
        if (mediaFile == null || mediaFile.getFilePath() == null || mediaFile.getFilePath().isBlank()) {
            throw new IOException("stored media path is missing");
        }

        String filePath = mediaFile.getFilePath();
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            try (InputStream inputStream = minioUtils.openStoredFile(filePath)) {
                return calculateMd5(inputStream);
            }
        }

        File localFile = new File(filePath);
        if (!localFile.isFile()) {
            throw new IOException("stored media file is not accessible: " + filePath);
        }
        return calculateMd5(localFile);
    }

    public void rememberContentHash(Long mediaId, String md5) {
        if (!AnalysisRedisKeys.isMd5(md5)) {
            throw new IllegalArgumentException("contentHash must be a lowercase 32-character MD5");
        }
        try {
            redisTemplate.opsForValue().set(
                    AnalysisRedisKeys.contentHash(mediaId), md5, CONTENT_HASH_CACHE_HOURS, TimeUnit.HOURS);
        } catch (RuntimeException e) {
            log.warn("Failed to cache contentHash for mediaId={}; MySQL remains authoritative", mediaId, e);
        }
    }

    public void forgetContentHash(Long mediaId) {
        try {
            redisTemplate.delete(AnalysisRedisKeys.contentHash(mediaId));
        } catch (RuntimeException e) {
            log.warn("Failed to remove contentHash cache for deleted mediaId={}", mediaId, e);
        }
    }

    private String calculateMd5(InputStream inputStream) throws IOException {
        MessageDigest digest = md5Digest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    private Map<Object, Object> requireUpload(String uploadId) {
        uploadDirectory(uploadId);
        Map<Object, Object> metadata = redisTemplate.opsForHash()
                .entries(CHUNK_UPLOAD_KEY_PREFIX + uploadId);
        if (metadata.isEmpty()) {
            throw new IllegalArgumentException("uploadId does not exist or has expired");
        }
        return metadata;
    }

    private Map<Object, Object> requireOwnedUpload(String uploadId, Long currentUserId) {
        Map<Object, Object> metadata = requireUpload(uploadId);
        Object ownerId = metadata.get("userId");
        if (currentUserId == null || ownerId == null
                || !String.valueOf(currentUserId).equals(String.valueOf(ownerId))) {
            throw new AccessDeniedException("no permission to access this upload");
        }
        return metadata;
    }

    private Path uploadDirectory(String uploadId) {
        try {
            UUID.fromString(uploadId);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid uploadId");
        }
        return CHUNK_UPLOAD_DIR.resolve(uploadId);
    }

    private Path chunkPath(Path directory, int chunkIndex) {
        return directory.resolve("part-" + chunkIndex);
    }

    private String partsKey(String uploadId) {
        return CHUNK_UPLOAD_KEY_PREFIX + uploadId + ":parts";
    }

    private void refreshChunkUploadTtl(String uploadId) {
        // Redis expire currently triggers DefaultedRedisConnection.pExpire recursion
        // with this dependency combination. Keep upload state persistent for now.
    }

    private String fileSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private void cleanupUpload(String uploadId) throws IOException {
        Path directory = uploadDirectory(uploadId);
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("failed to clean upload files", e);
                    }
                });
            }
        }
        redisTemplate.delete(List.of(CHUNK_UPLOAD_KEY_PREFIX + uploadId, partsKey(uploadId)));
    }

    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    public String convertVideoToAudio(MultipartFile file) throws IOException, InterruptedException {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(file.getOriginalFilename());
        mediaFile.setStatus("PROCESSING"); //状态：处理中
        mediaFile.setUploadTime(LocalDateTime.now());
        mediaFile.setFilePath(""); //暂时为空

        //这一步执行后，MySQL 里就会多一行数据
        mediaFileMapper.insert(mediaFile);

        // --- 下面是原有的文件处理逻辑 ---
        String fileId = UUID.randomUUID().toString();
        String inputPath = UPLOAD_DIR + fileId + "_input.mp4";
        String outputPath = UPLOAD_DIR + fileId + "_output.mp3";

        File inputFile = new File(inputPath);
        file.transferTo(inputFile);

        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        command.add("-vn");
        command.add("-acodec");
        command.add("libmp3lame");
        command.add("-q:a");
        command.add("2");
        command.add(new File(outputPath).getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        if (process.waitFor() == 0) {
            inputFile.delete(); // 删掉原视频

            // --- 数据库操作：更新状态为完成 ---
            mediaFile.setStatus("COMPLETED");
            mediaFile.setFilePath(outputPath);
            mediaFileMapper.updateById(mediaFile); // 根据 ID 更新这一行

            return outputPath;
        } else {
            // --- 数据库操作：记录失败 ---
            mediaFile.setStatus("FAILED");
            mediaFileMapper.updateById(mediaFile);
            throw new RuntimeException("FFmpeg 转换失败");
        }
    }
}
