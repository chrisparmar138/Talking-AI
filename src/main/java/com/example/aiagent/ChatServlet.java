package com.example.aiagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@WebServlet("/chat")
public class ChatServlet extends HttpServlet {

    // 1. Load Environment Variables
    private static final Map<String, String> env = loadEnvVariables();

    private static final String GROQ_API_KEY = env.get("GROQ_API_KEY");
    private static final String WEATHER_API_KEY = env.get("WEATHER_API_KEY");
    private static final String GNEWS_API_KEY = env.get("GNEWS_API_KEY");
    private static final String YOUTUBE_API_KEY = env.get("YOUTUBE_API_KEY");
    private static final String GOOGLE_API_KEY = env.get("GOOGLE_API_KEY");
    private static final String SEARCH_ENGINE_ID = env.get("SEARCH_ENGINE_ID");

    // Use a default model if the key is missing from .env
    private static final String GROQ_CHAT_MODEL = env.getOrDefault("GROQ_CHAT_MODEL", "llama-3.3-70b-versatile");
    private static final String GROQ_VISION_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct";

    private static final List<Map<String, String>> chatHistory = new LinkedList<>();
    private static String conversationContext = "NONE";

    private static final String SYSTEM_PROMPT = """
            You are an expert Windows 11 System Administrator AI.

            YOUR ROLE:
            - You have full control over the user's system via PowerShell.
            - You must generate valid, working PowerShell code for ANY user request.

            STRICT OUTPUT RULES:
            1. Output ONLY the code prefixed with '///CMD///'.
            2. Do NOT provide explanations.
            3. **MULTILINGUAL SUPPORT:** If you reply in a language other than English, PREPEND the language code in brackets.
               - Format: [LANG:code] Message
               - Spanish: [LANG:es-ES] Hola, he vaciado la papelera.
               - Hindi: [LANG:hi-IN] नमस्ते, मैंने कचरा साफ कर दिया है।
               - Default (English): No tag needed.

            CRITICAL SYNTAX RULES (MUST FOLLOW):
            1. **Empty Recycle Bin:** Use the modern cmdlet.
               - Command: "///CMD/// Clear-RecycleBin -Force -ErrorAction SilentlyContinue"

            2. **Windows Updates:** Open Settings Page.
               - Command: "///CMD/// Start-Process ms-settings:windowsupdate"

            3. **Uninstalling Software:** Use 'Get-WmiObject' (wmic is deprecated).
               - Command: "///CMD/// (Get-WmiObject -Class Win32_Product | Where-Object { $_.Name -match 'uTorrent' }).Uninstall()"

            4. **Creating Files:** - ALWAYS use '$env:USERPROFILE'.
               - WRAP path in Double Quotes.
               - WRAP content in Single Quotes.
               - Command: New-Item -Path "$env:USERPROFILE\\Downloads\\hello.py" -Force -Value 'print("Hello")'

            5. **Volume Control:** Use ASCII codes.
               - Up:   (New-Object -ComObject WScript.Shell).SendKeys([char]175)
               - Down: (New-Object -ComObject WScript.Shell).SendKeys([char]174)
               - Mute: (New-Object -ComObject WScript.Shell).SendKeys([char]173)

            EXAMPLE OUTPUTS:
            - "///CMD/// Clear-RecycleBin -Force -ErrorAction SilentlyContinue"
            - "[LANG:hi-IN] नमस्ते, मैं आपकी क्या मदद कर सकता हूँ?"
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // ===== THIS METHOD WAS MISSING IN YOUR CODE =====
    private static Map<String, String> loadEnvVariables() {
        Map<String, String> envMap = new HashMap<>();
        // 1. Load from System Environment (Cloud/Production)
        envMap.putAll(System.getenv());

        // 2. Load from .env file (Local Dev)
        try {
            List<String> lines = Files.readAllLines(Paths.get(".env"));
            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("#"))
                    continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    envMap.put(parts[0].trim(), parts[1].trim());
                }
            }
            System.out.println("✅ Loaded keys from .env file.");
        } catch (IOException e) {
            System.out.println("⚠️ No .env file found. Using System Env variables.");
        }
        return envMap;
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        setCorsHeaders(resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    private void setCorsHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }

    // ---------- INTENT DETECTORS ----------
    private boolean isWeather(String m) {
        return m.toLowerCase().contains("weather") || m.toLowerCase().contains("mausam")
                || m.toLowerCase().contains("temperature");
    }

    private boolean isNews(String m) {
        return m.toLowerCase().contains("news");
    }

    private boolean isYouTube(String m) {
        return m.toLowerCase().matches(".*(video|watch|play|paly|song|gana|music|youtube).*");
    }

    private boolean isDirectSearch(String m) {
        return m.toLowerCase().contains("search");
    }

    private boolean isGoogleQuestion(String m) {
        return m.toLowerCase().startsWith("who") || m.toLowerCase().startsWith("what");
    }

    private boolean isNavigation(String m) {
        return m.toLowerCase().contains("navigate") || m.toLowerCase().contains("guide");
    }

    private boolean isCurrentLocation(String m) {
        return m.toLowerCase().contains("location") || m.toLowerCase().contains("where am i");
    }

    private String extractCity(String msg) {
        String clean = msg.toLowerCase();
        clean = clean.replaceAll("[^a-zA-Z0-9\\s]", "");
        String stopWords = "\\b(what|whats|is|the|today|todays|weather|temperature|mausam|in|at|for|please|tell|me|check|batao|location|my|current|right|now|hey|hi)\\b";
        clean = clean.replaceAll(stopWords, "");
        clean = clean.trim().replaceAll("\\s+", " ");
        System.out.println("🔍 Raw: " + msg + " | 📍 Extracted City: '" + clean + "'");
        return clean.length() > 1 ? clean : null;
    }

    private void addToHistory(String role, String content) {
        if (chatHistory.size() >= 20)
            chatHistory.remove(0);
        chatHistory.add(Map.of("role", role, "content", content));
    }

    // VISION LOGIC
    private String analyzeImageWithGroq(String userMessage, String base64Image) {
        try {
            String url = "https://api.groq.com/openai/v1/chat/completions";
            List<Map<String, Object>> contentList = new ArrayList<>();
            String text = (userMessage == null || userMessage.trim().isEmpty()) ? "What is in this image?"
                    : userMessage;
            contentList.add(Map.of("type", "text", "text", text));
            contentList.add(Map.of("type", "image_url", "image_url", Map.of("url", base64Image)));

            Map<String, Object> userMsg = Map.of("role", "user", "content", contentList);
            List<Map<String, Object>> messages = List.of(userMsg);

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", GROQ_VISION_MODEL);
            payload.put("messages", messages);
            payload.put("temperature", 0.6);
            payload.put("max_completion_tokens", 1024);

            String requestBody = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("Authorization", "Bearer " + GROQ_API_KEY).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                return root.path("choices").get(0).path("message").path("content").asText();
            } else
                return "Vision Error: " + response.statusCode();
        } catch (Exception e) {
            return "Failed to analyze image.";
        }
    }

    private String getWeather(double lat, double lon) {
        try {
            return fetchWeather("https://api.openweathermap.org/data/2.5/weather?lat=" + lat + "&lon=" + lon + "&appid="
                    + WEATHER_API_KEY + "&units=metric");
        } catch (Exception e) {
            return "Weather error.";
        }
    }

    private String getWeatherByCity(String city) {
        try {
            return fetchWeather("https://api.openweathermap.org/data/2.5/weather?q="
                    + URLEncoder.encode(city, StandardCharsets.UTF_8) + "&appid=" + WEATHER_API_KEY + "&units=metric");
        } catch (Exception e) {
            return "City not found.";
        }
    }

    private String fetchWeather(String url) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder().uri(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            System.out.println("⚠️ WEATHER ERROR: " + res.statusCode() + " " + res.body());
            return "Weather Error: " + res.statusCode();
        }
        JsonNode root = mapper.readTree(res.body());
        return String.format("Weather in %s: %.1f°C, %s.", root.path("name").asText(),
                root.path("main").path("temp").asDouble(), root.path("weather").get(0).path("description").asText());
    }

    private String reverseGeocode(double lat, double lon) {
        try {
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("https://nominatim.openstreetmap.org/reverse?format=json&lat=" + lat
                                    + "&lon=" + lon))
                            .header("User-Agent", "AI").build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode addr = mapper.readTree(res.body()).path("address");
            return addr.path("city").asText(addr.path("town").asText("Unknown"));
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private String getNewsHeadlines() {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(
                            "https://gnews.io/api/v4/top-headlines?lang=en&country=in&max=3&apikey=" + GNEWS_API_KEY))
                    .build(), HttpResponse.BodyHandlers.ofString());
            StringBuilder sb = new StringBuilder("Top Headlines:\n");
            for (JsonNode article : mapper.readTree(res.body()).path("articles"))
                sb.append("- ").append(article.path("title").asText()).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "News unavailable.";
        }
    }

    private String getYouTubeLink(String query) {
        String cleanQuery = query.replaceAll("(?i)video|watch|play|paly|dekho|chalao|song|gana|music|on youtube", "")
                .trim();
        String searchUrl = "https://www.youtube.com/results?search_query="
                + URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8);
        if (cleanQuery.isEmpty())
            return "https://www.youtube.com";
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder().uri(
                    URI.create("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=1&q="
                            + URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8) + "&key=" + YOUTUBE_API_KEY))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonNode root = mapper.readTree(res.body());
                if (root.path("items").size() > 0)
                    return "https://youtube.com/watch?v="
                            + root.path("items").get(0).path("id").path("videoId").asText();
            }
            return searchUrl;
        } catch (Exception e) {
            return searchUrl;
        }
    }

    private String webSearchSummary(String query) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/customsearch/v1?key=" + GOOGLE_API_KEY + "&cx="
                            + SEARCH_ENGINE_ID + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&num=1"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(res.body()).path("items").get(0).path("snippet").asText();
        } catch (Exception e) {
            return "Search unavailable.";
        }
    }

    private String chatWithGroq(String userMessage) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
            messages.addAll(chatHistory);
            messages.add(Map.of("role", "user", "content", userMessage));
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", GROQ_CHAT_MODEL);
            payload.put("messages", messages);
            payload.put("temperature", 0.6);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + GROQ_API_KEY).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200)
                return mapper.readTree(response.body()).path("choices").get(0).path("message").path("content").asText();
            else
                return "Brain Error " + response.statusCode();
        } catch (Exception e) {
            return "AI Error.";
        }
    }

    private String executeSystemCommand(String command) {
        try {
            String cleanCmd = command.replace("///CMD///", "");
            cleanCmd = cleanCmd.replaceAll("\\[LANG:[a-zA-Z0-9-]+\\]", "");
            cleanCmd = cleanCmd.trim();

            System.out.println("EXECUTING: " + cleanCmd);

            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-Command", cleanCmd);
            Process p = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null)
                    output.append(line).append("\n");
            }

            StringBuilder errorOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null)
                    errorOutput.append(line).append("\n");
            }

            p.waitFor(5, TimeUnit.SECONDS);

            String result = output.toString().trim();
            String error = errorOutput.toString().trim();

            if (!error.isEmpty()) {
                System.out.println("CMD Error: " + error);
                return "Command failed: " + error;
            }

            return result.isEmpty() ? "Executed." : result;

        } catch (Exception e) {
            return "Failed to execute.";
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        setCorsHeaders(resp);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        try {
            Map<String, Object> body = mapper.readValue(req.getInputStream(), new TypeReference<>() {
            });
            String msg = (String) body.get("message");
            String image = (String) body.get("image");
            String lang = (String) body.getOrDefault("lang", "en-US");
            Map<String, Object> loc = (Map<String, Object>) body.get("location");

            Double lat = null, lon = null;
            if (loc != null) {
                if (loc.get("latitude") instanceof Number)
                    lat = ((Number) loc.get("latitude")).doubleValue();
                if (loc.get("longitude") instanceof Number)
                    lon = ((Number) loc.get("longitude")).doubleValue();
            }

            String reply = "";

            if (image != null && !image.isEmpty()) {
                reply = analyzeImageWithGroq(msg, image);
            } else if (conversationContext.equals("WEATHER_CITY") && (msg.contains("location") || msg.contains("my"))) {
                reply = (lat != null) ? getWeather(lat, lon) : "Need GPS.";
                conversationContext = "NONE";
            } else if (isNavigation(msg)) {
                reply = "Opening Google Maps...";
                conversationContext = "NONE";
            } else if (isWeather(msg)) {
                String city = extractCity(msg);
                if (city != null) {
                    reply = getWeatherByCity(city);
                    conversationContext = "NONE";
                } else if (lat != null && (msg.contains("my") || msg.contains("current"))) {
                    reply = getWeather(lat, lon);
                    conversationContext = "NONE";
                } else {
                    reply = "Which city?";
                    conversationContext = "WEATHER_CITY";
                }
            } else if (isCurrentLocation(msg)) {
                reply = (lat != null) ? "Near " + reverseGeocode(lat, lon) : "Need GPS.";
            } else if (isYouTube(msg)) {
                String link = getYouTubeLink(msg);
                executeSystemCommand("Start-Process '" + link + "'");
                reply = "Opening YouTube: " + msg.replaceAll("(?i)play|song|video", "").trim();
                conversationContext = "NONE";
            } else if (isDirectSearch(msg)) {
                String q = msg.replaceAll("(?i)search for|search|google|on internet", "").trim();
                executeSystemCommand("Start-Process 'https://www.google.com/search?q="
                        + URLEncoder.encode(q, StandardCharsets.UTF_8) + "'");
                reply = "Searching Google: " + q;
                conversationContext = "NONE";
            } else if (isNews(msg)) {
                reply = getNewsHeadlines();
                conversationContext = "NONE";
            } else if (isGoogleQuestion(msg)) {
                reply = webSearchSummary(msg);
                conversationContext = "NONE";
            } else {
                String aiResponse = chatWithGroq(msg);
                if (aiResponse.contains("///CMD///")) {
                    String result = executeSystemCommand(aiResponse);
                    reply = result;
                } else {
                    reply = aiResponse;
                }
                conversationContext = "NONE";
            }

            if (!reply.isEmpty()) {
                addToHistory("user", msg);
                addToHistory("assistant", reply);
            }
            mapper.writeValue(resp.getOutputStream(), Map.of("reply", reply, "lang", lang));

        } catch (Exception e) {
            e.printStackTrace();
            mapper.writeValue(resp.getOutputStream(), Map.of("error", "Error: " + e.getMessage()));
        }
    }
}