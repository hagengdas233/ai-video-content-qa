package com.example.server.controller;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import com.example.server.service.MediaAnalysisTaskService;
import com.example.server.strategy.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit; //导入时间单位

@RestController
@Profile("dev")
@RequestMapping("/debug")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class DebugController {

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    @Autowired
    private AiService aiService;


    @Autowired
    private MediaAnalysisTaskService mediaAnalysisTaskService;

    //AI总结接口(分布式锁 + 限流 + MQ)
    @GetMapping("/ai")
    public String aiAnalyze(@RequestParam Long id,
                            @RequestParam(defaultValue = "理解视频核心内容并生成结构化分析报告") String goal,
                            @RequestParam(defaultValue = "false") boolean force) {
        try {
            MediaFile file = mediaFileMapper.selectById(id);
            if (file == null) return "文件不存在";
            return mediaAnalysisTaskService
                    .submitAnalysis(id, file.getUserId(), goal, force)
                    .toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ 提交失败: " + e.getMessage();
        }
    }

    //纯文字提取接口
    @GetMapping("/transcribe")
    public String transcribe(@RequestParam Long id) {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return "❌ 找不到文件记录";

        // 调用异步服务
        aiService.asyncTranscribe(id);

        return "✅ 提取任务已后台运行！请稍后查看结果。";
    }

    //下载音频接口
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam Long id) throws IOException {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return ResponseEntity.notFound().build();

        String inputPath = mediaFile.getFilePath();

        if (!inputPath.startsWith("http")) {
            if (!new File(inputPath).exists()) return ResponseEntity.notFound().build();
        }

        String outputMp3Path = System.getProperty("java.io.tmpdir") + File.separator + "download_" + UUID.randomUUID() + ".mp3";
        System.out.println("⬇ 下载请求，正在从源地址转码音频: " + inputPath);

        boolean success = runFfmpeg(inputPath, outputMp3Path);

        if (!success) return ResponseEntity.internalServerError().build();

        File mp3File = new File(outputMp3Path);
        Resource resource = new FileSystemResource(mp3File);

        String fileName = "audio.mp3";
        if (mediaFile.getFilename() != null) {
            fileName = mediaFile.getFilename().replaceAll("\\.[^.]+$", "") + ".mp3";
        }
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }

    private boolean runFfmpeg(String inputPath, String outputPath) {
        try {
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-y");
            command.add("-i");
            command.add(inputPath);
            command.add("-vn");
            command.add("-acodec");
            command.add("libmp3lame");
            command.add("-q:a");
            command.add("2");
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            return process.waitFor(15, TimeUnit.MINUTES) && process.exitValue() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
