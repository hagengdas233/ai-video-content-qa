package com.example.server.utils;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DeepSeekUtils {

    private static final String SYSTEM_PROMPT = """
            # Role
            你是一位拥有认知心理学背景的资深信息架构师，负责从语音转录文本中提取高价值信息并重构逻辑。

            # Goals
            忽略口语废话、重复和语气词，输出结构清晰、客观专业的分析报告。

            # Constraints
            1. 文本过短或无意义时，输出“无法提取有效信息”。
            2. 不输出开场白或结束语。
            3. 严格使用以下 Markdown 结构：

            ## 核心摘要
            ## 深度洞察
            ### 1. 观点标题
            ## 原始内容精选
            ## 领域标签
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DeepSeekUtils(@Value("${ai.deepseek.api-key}") String apiKey,
                         @Value("${ai.deepseek.base-url}") String baseUrl,
                         @Value("${ai.deepseek.model}") String modelName,
                         ObjectMapper objectMapper) {
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
        this.objectMapper = objectMapper;
    }

    public String analyzeContent(String content) {
        if (content == null || content.isBlank()) {
            return "无法提取有效信息";
        }
        return chatModel.chat(SYSTEM_PROMPT + "\n\n待分析文本：\n" + content);
    }

    public String chat(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        return chatModel.chat(prompt);
    }

    public AgentState.AgentPlan plan(VideoContext context) {
        try {
            String prompt = """
                    你是 Video Agent 的 Planner。理解用户目标，并拆成 3 到 5 个可执行任务。
                    任务必须能够仅依靠 VideoContext 中的 ASR、OCR、CHUNK_SUMMARY 和时间戳证据完成。
                    CHUNK_SUMMARY 是对应时间区间的压缩摘要，不是逐字 ASR 或原始 OCR。
                    只返回 JSON：
                    {
                      "understoodGoal": "对用户目标的明确理解",
                      "tasks": ["任务1", "任务2", "任务3"]
                    }
                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);
            return parsePlanResponse(chatModel.chat(prompt));
        } catch (Exception e) {
            throw new IllegalStateException("Agent 任务规划失败", e);
        }
    }

    public VideoChunk.ChunkSummary summarizeChunk(List<VideoContext.VideoSegment> segments) {
        try {
            String prompt = """
                    压缩以下五分钟视频片段，保留人物、事件、观点、结论以及重要 OCR 信息。
                    只返回 JSON：
                    {
                      "segmentSummary": "不超过 200 字的片段摘要",
                      "keywords": ["关键词1", "关键词2", "关键词3"]
                    }
                    原始片段：
                    """ + objectMapper.writeValueAsString(segments);
            return parseJson(chatModel.chat(prompt), VideoChunk.ChunkSummary.class);
        } catch (Exception e) {
            throw new IllegalStateException("视频片段摘要失败", e);
        }
    }

    public AnalysisResult execute(VideoContext context,
                                  AgentState.AgentPlan plan,
                                  AgentState.CriticResult previousCritique) {
        try {
            String prompt = """
                    你是 Video Agent 的 Executor。按照计划分析 VideoContext 并生成结构化产物。
                    所有重要结论必须绑定 VideoContext 中存在的真实 timestampMs，
                    并将来源准确标明为 ASR、OCR 或 CHUNK_SUMMARY。
                    使用 CHUNK_SUMMARY 时只能引用该摘要片段的 startMs，并明确这是区间级摘要证据，
                    不得伪装成逐字 ASR、原始 OCR 或更精确的秒级证据。
                    不得使用视频上下文之外的事实。
                    如果存在 Critic 反馈，必须逐项修正。

                    只返回 JSON：
                    {
                      "title": "产物标题",
                      "conclusions": ["结论"],
                      "evidence": [
                        {"timestampMs": 120000, "source": "ASR、OCR或CHUNK_SUMMARY", "content": "证据内容"}
                      ],
                      "suggestions": ["建议"]
                    }

                    Plan:
                    """ + objectMapper.writeValueAsString(plan) + """

                    PreviousCritique:
                    """ + objectMapper.writeValueAsString(previousCritique) + """

                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);
            return parseJson(chatModel.chat(prompt), AnalysisResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 执行失败", e);
        }
    }

    public AgentState.CriticResult critique(VideoContext context,
                                            AgentState.AgentPlan plan,
                                            AnalysisResult result) {
        try {
            String prompt = """
                    你是 Video Agent 的 Critic，只负责检查，不负责改写产物。
                    检查标准：
                    1. 是否覆盖用户目标和 Planner 的全部任务；
                    2. 每个重要结论是否能在 VideoContext 中找到时间戳证据，且 ASR、OCR、
                       CHUNK_SUMMARY 来源标注准确；
                    3. 是否存在上下文不支持的结论；
                    4. title、conclusions、evidence、suggestions 是否完整。

                    只有全部满足时 passed 才能为 true。
                    requiredTimestamps 填写需要重新读取或补充分析的时间戳毫秒值。
                    只返回 JSON：
                    {
                      "passed": false,
                      "feedback": ["具体修改建议"],
                      "missingRequirements": ["遗漏要求"],
                      "unsupportedClaims": ["无证据结论"],
                      "requiredTimestamps": [120000]
                    }

                    Plan:
                    """ + objectMapper.writeValueAsString(plan) + """

                    Draft:
                    """ + objectMapper.writeValueAsString(result) + """

                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);
            return parseJson(chatModel.chat(prompt), AgentState.CriticResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Critic 校验失败", e);
        }
    }

    private <T> T parseJson(String response, Class<T> type) throws Exception {
        return objectMapper.readValue(stripCodeFence(response), type);
    }

    AgentState.AgentPlan parsePlanResponse(String response) throws Exception {
        JsonNode root = objectMapper.readTree(stripCodeFence(response));
        List<String> tasks = new ArrayList<>();
        JsonNode taskNodes = root.path("tasks");
        if (taskNodes.isArray()) {
            for (JsonNode taskNode : taskNodes) {
                String task = taskNode.isTextual()
                        ? taskNode.asText()
                        : flattenTaskObject(taskNode);
                if (task != null && !task.isBlank()) {
                    tasks.add(task.trim());
                }
            }
        } else if (taskNodes.isTextual() && !taskNodes.asText().isBlank()) {
            tasks.add(taskNodes.asText().trim());
        }
        return new AgentState.AgentPlan(root.path("understoodGoal").asText(""), tasks);
    }

    private String flattenTaskObject(JsonNode taskNode) {
        if (!taskNode.isObject()) {
            return taskNode.asText("");
        }
        List<String> parts = new ArrayList<>();
        taskNode.fields().forEachRemaining(field -> {
            if (field.getValue().isValueNode() && !field.getValue().asText().isBlank()) {
                parts.add(field.getValue().asText().trim());
            }
        });
        return String.join(" - ", parts);
    }

    private String stripCodeFence(String response) {
        return response
                .replace("```json", "")
                .replace("```", "")
                .trim();
    }
}
