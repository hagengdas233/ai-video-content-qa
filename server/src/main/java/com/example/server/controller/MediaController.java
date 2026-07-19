package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.auth.UserContext;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.MediaAnalysisTaskService;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils; //确保导入这个
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/media")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class MediaController {

    @Autowired(required = false)
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MinioUtils minioUtils;

    @Autowired
    private YtDlpUtils ytDlpUtils;

    @Autowired
    private MediaService mediaService;

    @Autowired
    private MediaAnalysisTaskService mediaAnalysisTaskService;

    @PostMapping("/init-upload")
    public ResponseEntity<String> initUpload(@RequestParam String filename,
                                             @RequestParam int totalChunks) {
        try {
            return ResponseEntity.ok(mediaService.initChunkedUpload(
                    filename, totalChunks, UserContext.requireUserId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Failed to initialize upload");
        }
    }

    @GetMapping("/upload-status")
    public ResponseEntity<?> uploadStatus(@RequestParam String uploadId) {
        try {
            Set<Integer> uploadedChunks = mediaService.getUploadedChunks(
                    uploadId, UserContext.requireUserId());
            return ResponseEntity.ok(uploadedChunks);
        } catch (MediaService.AccessDeniedException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/upload-chunk")
    public ResponseEntity<String> uploadChunk(@RequestParam String uploadId,
                                              @RequestParam int chunkIndex,
                                              @RequestParam int totalChunks,
                                              @RequestParam("file") MultipartFile file) {
        try {
            mediaService.uploadChunk(
                    uploadId, chunkIndex, totalChunks, file, UserContext.requireUserId());
            return ResponseEntity.ok("Chunk uploaded");
        } catch (MediaService.AccessDeniedException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Chunk upload failed");
        }
    }

    @PostMapping("/complete-upload")
    public ResponseEntity<String> completeUpload(@RequestParam String uploadId) {
        try {
            mediaService.completeChunkedUpload(uploadId, UserContext.requireUserId());
            return ResponseEntity.ok("Upload success");
        } catch (MediaService.AccessDeniedException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Upload merge failed");
        }
    }


    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("Upload failed: file is empty");
        }
        if (mediaFileMapper == null) {
            return ResponseEntity.status(500).body("Upload failed: database not ready");
        }
        try {
            Long currentUserId = UserContext.requireUserId();
            String md5 = mediaService.calculateMd5(file);
            System.out.println("Uploading to MinIO...");
            String fileUrl = minioUtils.uploadFile(file);
            System.out.println("MinIO upload success, url: " + fileUrl);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(file.getOriginalFilename());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUploadTime(LocalDateTime.now());

            mediaFile.setUserId(currentUserId);
            mediaFile.setContentHash(md5);

            mediaFileMapper.insert(mediaFile);
            mediaService.rememberContentHash(mediaFile.getId(), md5);

            String cacheKey = "media:list:user:" + currentUserId;
            redisTemplate.delete(cacheKey);
            System.out.println("Cache cleared: " + cacheKey);

            return ResponseEntity.ok("Upload success");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        }
    }

    @PostMapping("/upload-url")
    public org.springframework.http.ResponseEntity<String> uploadUrl(@RequestParam("url") String url) {
        File tempFile = null;
        try {
            Long currentUserId = UserContext.requireUserId();
            if (url == null || url.isBlank()) {
                return org.springframework.http.ResponseEntity.badRequest().body("Upload failed: url is empty");
            }
            if (mediaFileMapper == null) {
                return org.springframework.http.ResponseEntity.status(500).body("Upload failed: database not ready");
            }
            System.out.println("Received upload url: " + url);

            tempFile = ytDlpUtils.downloadVideo(url);

            String md5 = mediaService.calculateMd5(tempFile);
            String fileUrl = minioUtils.uploadLocalFile(tempFile);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename("WEB_" + tempFile.getName());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUploadTime(LocalDateTime.now());

            mediaFile.setUserId(currentUserId);
            mediaFile.setContentHash(md5);

            mediaFileMapper.insert(mediaFile);
            mediaService.rememberContentHash(mediaFile.getId(), md5);

            String cacheKey = "media:list:user:" + currentUserId;
            redisTemplate.delete(cacheKey);
            System.out.println("Cache cleared: " + cacheKey);

            return org.springframework.http.ResponseEntity.ok("Upload success");

        } catch (Exception e) {
            e.printStackTrace();
            return org.springframework.http.ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @PostMapping("/analyze/{mediaId}")
    public ResponseEntity<?> analyze(@PathVariable Long mediaId,
                                     @RequestParam(defaultValue = "false") boolean force,
                                     @RequestBody(required = false) Map<String, Object> request) {
        try {
            Long currentUserId = UserContext.requireUserId();
            String goal = request == null || request.get("goal") == null
                    ? null
                    : String.valueOf(request.get("goal"));

            Map<String, Object> result = mediaAnalysisTaskService
                    .submitAnalysis(mediaId, currentUserId, goal, force);
            return "REUSED".equals(result.get("status"))
                    ? ResponseEntity.ok(result)
                    : ResponseEntity.status(202).body(result);
        } catch (MediaAnalysisTaskService.MediaNotFoundException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        } catch (MediaAnalysisTaskService.AccessDeniedException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        } catch (MediaAnalysisTaskService.RateLimitExceededException e) {
            return ResponseEntity.status(429).body(e.getMessage());
        } catch (MediaAnalysisTaskService.AnalysisStateConflictException e) {
            return ResponseEntity.status(409).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Analysis submit failed: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    public List<MediaFile> getList() {
        Long currentUserId = UserContext.requireUserId();
        String cacheKey = "media:list:user:" + currentUserId;

        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                System.out.println("命中 Redis 缓存，直接返回！");
                return objectMapper.readValue(json, new TypeReference<List<MediaFile>>(){});
            }
        } catch (Exception e) {
            System.err.println("Redis 读取失败: " + e.getMessage());
        }

        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        query.eq("user_id", currentUserId);
        List<MediaFile> list = mediaFileMapper.selectList(query.orderByDesc("id"));

        try {
            String jsonToWrite = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(cacheKey, jsonToWrite, 30, TimeUnit.MINUTES);
            System.out.println("已写入 Redis 缓存");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;
    }

    //删除接口
    @DeleteMapping("/delete")
    public ResponseEntity<String> delete(@RequestParam("id") Long id) {
        Long currentUserId = UserContext.requireUserId();

        MediaFile media = mediaFileMapper.selectById(id);
        if (media == null) return ResponseEntity.status(404).body("文件不存在");

        if (media.getUserId() == null || !media.getUserId().equals(currentUserId)) {
            return ResponseEntity.status(403).body("无权删除他人的文件");
        }

        if (media.getFilePath() != null && media.getFilePath().startsWith("http")) {
            minioUtils.removeFile(media.getFilePath());
        }

        mediaFileMapper.deleteById(id);
        mediaService.forgetContentHash(id);

        if (media.getUserId() != null) {
            String cacheKey = "media:list:user:" + media.getUserId();
            redisTemplate.delete(cacheKey);
            System.out.println("缓存已清除: " + cacheKey);
        }

        return ResponseEntity.ok("删除成功");
    }
}
