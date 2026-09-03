package com.youkeda.exercise.claw.ai.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.ai.voice.VoiceClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.UUID;

/**
 * 语音客户端
 *
 * 封装 DashScope 语音 API 调用：
 * - ASR（语音识别）：Paraformer，文件转录模式
 * - TTS（语音合成）：CosyVoice，非流式合成
 */
@Component
public class VoiceClient {

    private static final Logger log = LoggerFactory.getLogger(VoiceClient.class);

    private static final int ASR_TIMEOUT_SECONDS = 120;
    private static final int TTS_TIMEOUT_SECONDS = 60;
    private static final int FILE_UPLOAD_TIMEOUT_SECONDS = 60;
    private static final int MAX_ASYNC_POLLS = 5;
    private static final long ASYNC_POLL_BASE_MS = 2000;

    /** DashScope 文件上传 API */
    private static final String FILE_UPLOAD_URL = "https://dashscope.aliyuncs.com/api/v1/files";

    /** DashScope 异步任务查询 API */
    private static final String TASK_QUERY_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/";

    private final VoiceProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public VoiceClient(VoiceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(ASR_TIMEOUT_SECONDS))
                .build();
    }

    /**
     * 语音识别（ASR）：将音频字节转为文字
     *
     * 流程：
     * 1. 上传音频到 DashScope 文件服务，获取 fileUrl
     * 2. 调用 Paraformer 转录 API
     * 3. 若返回异步任务，轮询直到完成
     *
     * @param audioBytes 音频字节数据
     * @param encodeType 音频编码类型
     * @return 识别文本，失败时返回 null
     * @throws VoiceClientException API 返回业务错误码时抛出
     */
    public String asr(byte[] audioBytes, int encodeType) throws VoiceClientException {
        return asr(audioBytes, determineFileName(encodeType));
    }

    /** Web uploads retain their actual extension so the ASR service can detect the codec. */
    public String asr(byte[] audioBytes, String originalFileName) throws VoiceClientException {
        try {
            // 1. 上传音频获取文件 URL
            String fileName = normalizeAudioFileName(originalFileName);
            String fileUrl = uploadFile(audioBytes, fileName);
            if (fileUrl == null) {
                log.warn("音频上传失败，无法进行 ASR");
                return null;
            }
            log.info("音频上传成功 | fileUrl={}", fileUrl);

            // 2. 调用 Paraformer 转录
            String requestBody = buildAsrRequestBody(fileUrl);
            log.info("调用 Paraformer ASR | fileName={}", fileName);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getAsrBaseUrl()))
                    .timeout(Duration.ofSeconds(ASR_TIMEOUT_SECONDS))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            return parseAsrResponse(response.body());

        } catch (VoiceClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("ASR 调用失败 | error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 语音合成（TTS）：将文字转为语音音频字节
     *
     * @param text 待合成的文字
     * @return TTS 结果（音频字节 + 音频 URL），失败时返回 null
     * @throws VoiceClientException API 返回业务错误码时抛出
     */
    public TtsResult tts(String text) throws VoiceClientException {
        try {
            if (text == null || text.trim().isEmpty()) {
                log.warn("TTS 输入文本为空");
                return null;
            }

            String requestBody = buildTtsRequestBody(text);
            log.info("调用 CosyVoice TTS | text={}", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getTtsBaseUrl()))
                    .timeout(Duration.ofSeconds(TTS_TIMEOUT_SECONDS))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            JsonNode root = objectMapper.readTree(body);

            // 检查 API 错误码
            JsonNode codeNode = root.get("code");
            if (codeNode != null && !codeNode.isNull()) {
                String msg = root.path("message").asText("TTS 未知错误");
                log.warn("TTS API 错误 | code={} | message={}", codeNode.asText(), msg);
                throw new VoiceClientException(codeNode.asText(), msg);
            }

            // 非流式响应：提取 output.audio.url 再下载
            JsonNode audioUrlNode = root.path("output").path("audio").path("url");
            if (audioUrlNode == null || audioUrlNode.isNull()) {
                log.warn("TTS 响应缺少音频 URL: {}", body);
                return null;
            }

            String audioUrl = audioUrlNode.asText();
            log.info("TTS 合成成功，获取音频 URL | url={}", audioUrl);

            // 下载音频字节，URL 与字节绑定在同一个返回值里（消除跨调用 lastTtsUrl 竞态）
            return new TtsResult(downloadAudio(audioUrl), audioUrl);

        } catch (VoiceClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("TTS 调用失败 | error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 从音频 URL 下载字节数据
     */
    private byte[] downloadAudio(String audioUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(audioUrl))
                .timeout(Duration.ofSeconds(TTS_TIMEOUT_SECONDS))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 200) {
            log.info("音频下载成功 | size={} bytes", response.body().length);
            return response.body();
        }

        log.warn("音频下载失败 | status={} | url={}", response.statusCode(), audioUrl);
        return null;
    }

    /**
     * 解析 WAV 音频采样率（Hz）
     *
     * 从 WAV 头部读取 sampleRate，非 WAV 格式返回默认值 8000。
     *
     * @param audioBytes 音频字节数据
     * @return 采样率（Hz），最低 8000
     */
    public int parseSampleRate(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length < 44) {
            return 8000;
        }

        try {
            // WAV 格式检测（RIFF 开头）
            if (audioBytes[0] == 'R' && audioBytes[1] == 'I'
                    && audioBytes[2] == 'F' && audioBytes[3] == 'F') {
                ByteBuffer bb = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN);
                int sampleRate = bb.getInt(24);
                log.info("WAV 采样率解析 | sampleRate={}Hz", sampleRate);
                return Math.max(sampleRate, 8000);
            }
        } catch (Exception e) {
            log.warn("解析采样率失败 | error={}", e.getMessage());
        }

        return 8000;
    }

    /**
     * 解析 WAV 音频字节，计算播放时长（毫秒）
     *
     * WAV（RIFF）格式解析头部精确计算：
     * 1. 从偏移 12 开始顺序扫描 RIFF 子块，定位 "data" 块
     * 2. 验证 dataSize 不超过文件剩余字节（避免元数据段中的误匹配）
     * 3. 根据采样率、声道数、位深计算精确时长
     *
     * 非 WAV 格式或解析失败时，按文本长度估算（中文约 3-4 字/秒）。
     *
     * @param audioBytes 音频字节数据
     * @param textHint   用于估算的文本（回退估算时有效）
     * @return 播放时长（毫秒），最低 100ms，上限 10 分钟
     */
    public int parsePlaytime(byte[] audioBytes, String textHint) {
        if (audioBytes == null || audioBytes.length == 0) {
            return 100;
        }

        try {
            // WAV 格式检测（RIFF 开头）
            if (audioBytes.length > 44
                    && audioBytes[0] == 'R' && audioBytes[1] == 'I'
                    && audioBytes[2] == 'F' && audioBytes[3] == 'F') {

                ByteBuffer bb = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN);

                int sampleRate = bb.getInt(24);
                short numChannels = bb.getShort(22);
                short bitsPerSample = bb.getShort(34);

                // 扫描 "data" 子块获取数据大小
                int offset = 12;
                int dataSize = 0;
                while (offset + 8 < audioBytes.length) {
                    String chunkId = new String(audioBytes, offset, 4,
                            java.nio.charset.StandardCharsets.US_ASCII);
                    int chunkSize = bb.getInt(offset + 4);

                    // 校验：chunkSize 不能为负且不能超过剩余文件大小
                    if (chunkSize < 0 || (long) offset + 8 + chunkSize > audioBytes.length) {
                        // 可能读到了垃圾数据，跳过这个 chunk
                        offset += 1;
                        continue;
                    }

                    if ("data".equals(chunkId)) {
                        dataSize = chunkSize;
                        break;
                    }
                    offset += 8 + chunkSize;
                    if (chunkSize % 2 != 0) {
                        offset++;
                    }
                }

                // 计算时长，并验证 dataSize 合理（不超过文件剩余字节）
                if (dataSize > 0 && sampleRate > 0 && numChannels > 0 && bitsPerSample > 0
                        && dataSize < audioBytes.length) {
                    double bytesPerSecond = sampleRate * numChannels * (bitsPerSample / 8.0);
                    int durationMs = (int) (dataSize / bytesPerSecond * 1000);
                    log.info("WAV 解析 | sampleRate={} | channels={} | bitsPerSample={} | dataSize={} | duration={}ms",
                            sampleRate, numChannels, bitsPerSample, dataSize, durationMs);
                    return clampPlaytime(durationMs);
                }

                log.warn("WAV 解析数据异常，回退估算 | dataSize={} | fileSize={}", dataSize, audioBytes.length);
            }
        } catch (Exception e) {
            log.warn("WAV 解析异常，回退估算 | error={}", e.getMessage());
        }

        // 回退：按文本长度估算（中文约 3-4 字/秒）
        if (textHint != null && !textHint.isEmpty()) {
            int durationMs = textHint.length() * 300;
            log.info("按文本估算时长 | textLen={} | estimatedMs={}", textHint.length(), durationMs);
            return clampPlaytime(durationMs);
        }

        // 回退：按文件大小估算（OPUS ~2KB/s, WAV ~16KB/s 取均值 8KB/s）
        int estimatedMs = (int) ((long) audioBytes.length * 1000 / 8000L);
        log.info("按文件大小估算时长 | fileSize={} | estimatedMs={}", audioBytes.length, estimatedMs);
        return clampPlaytime(estimatedMs);
    }

    /**
     * 限制时长在合理范围内（100ms ~ 10分钟）
     */
    private int clampPlaytime(int ms) {
        if (ms <= 0) return 100;
        if (ms > 600_000) return 600_000; // 10分钟上限
        return ms;
    }

    /**
     * 上传音频到 DashScope 文件服务
     */
    private String uploadFile(byte[] audioBytes, String fileName) throws Exception {
        String boundary = "Boundary_" + UUID.randomUUID().toString().replace("-", "");

        // 构建 multipart body
        byte[] body = buildMultipartBody(audioBytes, fileName, boundary);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(FILE_UPLOAD_URL))
                .timeout(Duration.ofSeconds(FILE_UPLOAD_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        // 解析响应
        JsonNode root = objectMapper.readTree(response.body());

        // 检查错误
        JsonNode codeNode = root.get("code");
        if (codeNode != null && !codeNode.isNull()) {
            int code = codeNode.asInt(0);
            if (code != 200) {
                throw new VoiceClientException("FileUploadFailed",
                        "文件上传失败 | code=" + code + " | msg=" + root.path("message").asText());
            }
        }

        JsonNode fileUrlNode = root.path("output").path("file_url");
        if (fileUrlNode != null && !fileUrlNode.isNull()) {
            return fileUrlNode.asText();
        }

        log.warn("文件上传响应缺少 file_url: {}", response.body());
        return null;
    }

    /**
     * 构建 multipart/form-data 请求体
     */
    private byte[] buildMultipartBody(byte[] fileBytes, String fileName, String boundary) throws Exception {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";

        byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(headerBytes.length + fileBytes.length + footerBytes.length);
        buffer.put(headerBytes);
        buffer.put(fileBytes);
        buffer.put(footerBytes);

        return buffer.array();
    }

    /**
     * 构建 Paraformer ASR 请求体
     */
    private String buildAsrRequestBody(String fileUrl) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getAsrModel());

        ObjectNode input = root.putObject("input");
        ArrayNode fileUrls = input.putArray("file_urls");
        fileUrls.add(fileUrl);

        // 可选参数
        ObjectNode parameters = root.putObject("parameters");
        parameters.put("sample_rate", 16000);
        parameters.put("format", "wav");

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 构建 CosyVoice TTS 请求体
     *
     * CosyVoice 系列（v1/v2/v3-flash）格式统一：
     * {
     *   "model": "cosyvoice-v1",
     *   "input": {
     *     "text": "...",
     *     "voice": "longxiaoxia_v2",
     *     "format": "wav",
     *     "sample_rate": 24000
     *   }
     * }
     */
    private String buildTtsRequestBody(String text) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getTtsModel());

        ObjectNode input = root.putObject("input");
        input.put("text", text);
        input.put("voice", properties.getTtsVoice());
        input.put("format", "mp3");
        input.put("sample_rate", 24000);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 解析 ASR 响应，支持同步和异步两种模式
     */
    private String parseAsrResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        // 检查 API 错误码
        JsonNode codeNode = root.get("code");
        if (codeNode != null && !codeNode.isNull()) {
            String errorCode = codeNode.asText();
            String errorMessage = root.path("message").asText("ASR 未知错误");
            throw new VoiceClientException(errorCode, errorMessage);
        }

        JsonNode output = root.get("output");

        // 同步响应：output.text 直接包含识别结果
        if (output != null && output.has("text")) {
            JsonNode textNode = output.get("text");
            if (textNode != null && !textNode.isNull()) {
                String text = textNode.asText();
                if (text != null && !text.trim().isEmpty()) {
                    return text.trim();
                }
            }
        }

        // 异步响应：output.task_status 存在
        if (output != null && output.has("task_status")) {
            String taskStatus = output.path("task_status").asText("");
            String taskId = output.path("task_id").asText("");

            if ("PENDING".equals(taskStatus) || "RUNNING".equals(taskStatus)) {
                return pollAsyncTask(taskId);
            } else if ("SUCCEEDED".equals(taskStatus)) {
                String text = output.path("text").asText("");
                return text.isEmpty() ? null : text.trim();
            } else if ("FAILED".equals(taskStatus)) {
                String errorMsg = output.path("message").asText("异步任务失败");
                log.warn("ASR 异步任务失败 | taskId={} | error={}", taskId, errorMsg);
                return null;
            }
        }

        log.warn("ASR 响应格式异常: {}",
                responseBody != null && responseBody.length() > 500
                        ? responseBody.substring(0, 500) + "..." : responseBody);
        return null;
    }

    /**
     * 轮询异步 ASR 任务直到完成
     */
    private String pollAsyncTask(String taskId) throws Exception {
        log.info("ASR 异步任务，开始轮询 | taskId={}", taskId);

        for (int i = 0; i < MAX_ASYNC_POLLS; i++) {
            long sleepMs = ASYNC_POLL_BASE_MS * (1L << i); // 2s, 4s, 8s, 16s, 32s
            Thread.sleep(sleepMs);

            String url = TASK_QUERY_URL + taskId;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(ASR_TIMEOUT_SECONDS))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode output = root.get("output");
            if (output == null) {
                continue;
            }

            String taskStatus = output.path("task_status").asText("");
            log.info("ASR 异步任务轮询 | taskId={} | attempt={} | status={}",
                    taskId, i + 1, taskStatus);

            if ("SUCCEEDED".equals(taskStatus)) {
                String text = output.path("text").asText("");
                if (!text.isEmpty()) {
                    return text.trim();
                }
                return null;
            } else if ("FAILED".equals(taskStatus)) {
                log.warn("ASR 异步任务失败 | taskId={}", taskId);
                return null;
            }
            // PENDING/RUNNING → 继续轮询
        }

        log.warn("ASR 异步任务轮询超时 | taskId={}", taskId);
        return null;
    }

    /**
     * 根据 encodeType 确定上传文件名（用于推断音频格式）
     */
    private String determineFileName(int encodeType) {
        return switch (encodeType) {
            case 4 -> "audio.wav";
            case 6 -> "audio.amr";
            case 7 -> "audio.silk";
            default -> "audio.wav";
        };
    }

    private String normalizeAudioFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) return "audio.wav";
        String name = java.nio.file.Path.of(originalFileName).getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot) : "";
        return switch (extension) {
            case ".wav", ".mp3", ".m4a", ".webm", ".ogg", ".opus", ".amr", ".aac", ".flac" ->
                    "audio" + extension;
            default -> "audio.wav";
        };
    }

    /**
     * TTS 合成结果：音频字节 + 音频 URL 绑定返回，
     * 替代此前 {@code volatile lastTtsUrl} 的跨调用旁路（消除 last-writer-wins 竞态）。
     */
    public record TtsResult(byte[] audioBytes, String audioUrl) {
    }
}
