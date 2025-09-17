package com.example.aiagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.awt.Desktop;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ChatServlet extends HttpServlet {

    private static final Dotenv dotenv = Dotenv.load();
    private static final String GROQ_API_KEY = dotenv.get("GROQ_API_KEY");
    private static final String WEATHER_API_KEY = dotenv.get("WEATHER_API_KEY");
    private static final String GNEWS_API_KEY = dotenv.get("GNEWS_API_KEY");
    private static final String YOUTUBE_API_KEY = dotenv.get("YOUTUBE_API_KEY");

    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODEL = "moonshotai/kimi-k2-instruct";

    // INSECURE SYSTEM PROMPT (As requested)
    private static final String COMMAND_SYSTEM_PROMPT = "You are an AI that converts user requests into Windows commands. Your entire response MUST BE A SINGLE, VALID JSON OBJECT with 'lang' and 'reply' keys. The 'reply' must contain ONLY the raw command. For opening apps, use 'start'. For closing, use 'taskkill /F /IM'. If it's not a command, make the 'reply' value empty. Example: for 'open chrome', respond {\"lang\":\"en-US\", \"reply\":\"start chrome\"}.";

    // A separate, friendly prompt for generating conversational feedback.
    private static final String CONVERSATIONAL_SYSTEM_PROMPT = "You are a helpful AI assistant. A user's command has just been successfully executed. Your task is to provide a short, natural, conversational confirmation message back to the user. Do not say what command was executed. Just confirm the action in a friendly way. Your entire response MUST BE A SINGLE, VALID JSON OBJECT with 'lang' and 'reply' keys.";


    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).proxy(ProxySelector.getDefault()).build();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", req.getHeader("Origin"));
        resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        resp.setHeader("Access-Control-Allow-Credentials", "true");
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Access-Control-Allow-Origin", req.getHeader("Origin"));
        resp.setHeader("Access-Control-Allow-Credentials", "true");
        resp.setContentType("application/json");

        try {
            Map<String, String> payload = objectMapper.readValue(req.getInputStream(), Map.class);
            String userMessage = payload.get("message");
            System.out.println("Received message: " + userMessage);

            // Gatekeeper for reliable features first
            Optional<String> localCommandResponse = handleLocalCommands(userMessage);

            if (localCommandResponse.isPresent()) {
                // If it was a weather, news, search, or YouTube command, handle it locally.
                Map<String, String> responsePayload = Map.of("reply", localCommandResponse.get(), "lang", "en-US");
                objectMapper.writeValue(resp.getOutputStream(), responsePayload);
            } else {
                // Otherwise, use the AI to generate a response (command or conversation).
                // STEP 1: Get the command from the AI
                String commandJsonResponse = callGroqApi(userMessage, COMMAND_SYSTEM_PROMPT);
                Map<String, String> commandResponseMap = objectMapper.readValue(commandJsonResponse, new TypeReference<>() {});
                String commandToExecute = commandResponseMap.get("reply");

                String finalJsonResponse;

                if (commandToExecute != null && !commandToExecute.trim().isEmpty()) {
                    System.out.println("AI generated command: " + commandToExecute);
                    try {
                        // STEP 2: Execute the command
                        Runtime.getRuntime().exec("cmd /c " + commandToExecute);

                        // STEP 3: Get a natural-sounding confirmation
                        String confirmationPrompt = "Tell the user in a short, friendly way that their request '" + userMessage + "' was successful.";
                        finalJsonResponse = callGroqApi(confirmationPrompt, CONVERSATIONAL_SYSTEM_PROMPT);

                    } catch (IOException e) {
                        e.printStackTrace();
                        String errorMsg = "Sorry, an error occurred while executing the command: " + e.getMessage();
                        finalJsonResponse = "{\"lang\":\"en-US\", \"reply\":\"" + errorMsg + "\"}";
                    }
                } else {
                    // If no command was generated, it's a conversational query.
                    String conversationalPrompt = "Provide a concise, helpful answer to the following user query: " + userMessage;
                    finalJsonResponse = callGroqApi(conversationalPrompt, CONVERSATIONAL_SYSTEM_PROMPT);
                }

                // STEP 4: Send the final, natural-sounding response to the frontend.
                resp.getWriter().write(finalJsonResponse);
            }

        } catch (Exception e) {
            e.printStackTrace();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            Map<String, String> errorResponse = Map.of("error", "Failed to process request: " + e.getMessage());
            objectMapper.writeValue(resp.getOutputStream(), errorResponse);
        }
    }

    // Gatekeeper for the most reliable features
    private Optional<String> handleLocalCommands(String command) {
        String lowerCaseCommand = command.toLowerCase().trim();

        if (lowerCaseCommand.contains("news") || lowerCaseCommand.contains("headlines")) {
            return getNewsHeadlines("en");
        }
        if (lowerCaseCommand.contains("weather")) {
            return getWeatherUpdate();
        }
        if (lowerCaseCommand.startsWith("search for") || lowerCaseCommand.startsWith("search")) {
            String query = command.substring(command.toLowerCase().indexOf("search for") != -1 ? "search for".length() : "search".length()).trim();
            return searchWeb(query);
        }
        // Updated block to include YouTube functionality
        if (lowerCaseCommand.startsWith("play")) {
            String query = command.substring("play".length()).trim();
            if (query.toLowerCase().endsWith("on youtube")) {
                query = query.substring(0, query.length() - "on youtube".length()).trim();
            }
            return playFirstVideoOnYouTube(query);
        }
        return Optional.empty();
    }

    // Updated to accept a system prompt as an argument
    private String callGroqApi(String userMessage, String systemPrompt) throws IOException, InterruptedException {
        if (GROQ_API_KEY == null || GROQ_API_KEY.startsWith("YOUR_")) {
            return "{\"lang\":\"en-US\", \"reply\":\"API Key not configured.\"}";
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));

        String jsonPayload = objectMapper.writeValueAsString(Map.of(
                "messages", messages,
                "model", GROQ_MODEL,
                "response_format", Map.of("type", "json_object")
        ));

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(GROQ_API_URL)).header("Authorization", "Bearer " + GROQ_API_KEY).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("Groq API Error: " + response.body());
            return "{\"lang\":\"en-US\", \"reply\":\"AI Service Error\"}";
        }

        Map<String,Object> responseMap = objectMapper.readValue(response.body(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
        if(choices != null && !choices.isEmpty()){
            @SuppressWarnings("unchecked")
            Map<String,String> message = (Map<String,String>) choices.get(0).get("message");
            if(message != null && message.containsKey("content")){
                return message.get("content");
            }
        }

        return "{\"lang\":\"en-US\", \"reply\":\"Could not parse AI response.\"}";
    }

    // Helper methods for News, Weather, and Search are unchanged.
    private Optional<String> getNewsHeadlines(String lang) {
        if (GNEWS_API_KEY == null || GNEWS_API_KEY.startsWith("YOUR_")) return Optional.of("The GNews API key is not configured.");
        String url = String.format("https://gnews.io/api/v4/top-headlines?country=in&lang=%s&max=3&apikey=%s", lang, GNEWS_API_KEY.trim());
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) { System.out.println("GNews Error: " + response.body()); return Optional.of("Sorry, I couldn't fetch the news."); }
            Map<String, Object> data = objectMapper.readValue(response.body(), new TypeReference<>() {});
            @SuppressWarnings("unchecked") List<Map<String, Object>> articles = (List<Map<String, Object>>) data.get("articles");
            if (articles == null || articles.isEmpty()) return Optional.of("I couldn't find any headlines.");
            StringBuilder builder = new StringBuilder("Here are the top headlines. ");
            for (int i = 0; i < articles.size(); i++) builder.append(String.format("Headline %d: %s. ", i + 1, articles.get(i).get("title")));
            return Optional.of(builder.toString());
        } catch (Exception e) { e.printStackTrace(); return Optional.of("Sorry, an error occurred while getting the news."); }
    }

    private Optional<String> getWeatherUpdate() {
        if (WEATHER_API_KEY == null || WEATHER_API_KEY.startsWith("YOUR_")) return Optional.of("The weather API key is not configured.");
        String url = String.format("https://api.openweathermap.org/data/2.5/weather?lat=18.6277&lon=73.8137&units=metric&appid=%s", WEATHER_API_KEY.trim());
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) { System.out.println("Weather Error: " + response.body()); return Optional.of("Sorry, I couldn't fetch the weather."); }
            Map<String, Object> data = objectMapper.readValue(response.body(), new TypeReference<>() {});
            @SuppressWarnings("unchecked") Map<String, Object> main = (Map<String, Object>) data.get("main");
            @SuppressWarnings("unchecked") List<Map<String, Object>> weather = (List<Map<String, Object>>) data.get("weather");
            String desc = (String) weather.get(0).get("description");
            double temp = ((Number) main.get("temp")).doubleValue();
            return Optional.of(String.format("The current weather in Pimpri-Chinchwad is %s with a temperature of %d degrees Celsius.", desc, Math.round(temp)));
        } catch (Exception e) { e.printStackTrace(); return Optional.of("Sorry, an error occurred while getting the weather."); }
    }

    private Optional<String> searchWeb(String query) {
        if (query.isEmpty()) {
            return Optional.of("What would you like me to search for?");
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
                URI searchUri = new URI("https://www.google.com/search?q=" + encodedQuery);
                Desktop.getDesktop().browse(searchUri);
                return Optional.of("Here are the search results for '" + query + "'.");
            } catch (Exception e) {
                return Optional.of("Sorry, I couldn't open the browser to perform the search.");
            }
        }
        return Optional.of("Sorry, I can't open a web browser on this system.");
    }

    // New helper method for YouTube API
    private Optional<String> playFirstVideoOnYouTube(String query) {
        if (YOUTUBE_API_KEY == null || YOUTUBE_API_KEY.startsWith("YOUR_")) {
            return Optional.of("The YouTube API key is not configured.");
        }
        if (query.isEmpty()) {
            return Optional.of("Sure, what music or video would you like me to play?");
        }

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String apiUrl = String.format(
                    "https://www.googleapis.com/youtube/v3/search?part=snippet&maxResults=1&q=%s&type=video&key=%s",
                    encodedQuery, YOUTUBE_API_KEY
            );

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.out.println("YouTube API Error: " + response.body());
                return Optional.of("Sorry, I couldn't search for the video on YouTube.");
            }

            Map<String, Object> data = objectMapper.readValue(response.body(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");

            if (items == null || items.isEmpty()) {
                return Optional.of("Sorry, I couldn't find any video results for '" + query + "'.");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> idObject = (Map<String, Object>) items.get(0).get("id");
            String videoId = (String) idObject.get("videoId");

            if (videoId == null || videoId.isEmpty()) {
                return Optional.of("Could not extract video ID from the API response.");
            }

            URI videoUri = new URI("https://www.youtube.com/watch?v=" + videoId);
            Desktop.getDesktop().browse(videoUri);

            return Optional.of("Playing the top result for '" + query + "' on YouTube.");

        } catch (Exception e) {
            e.printStackTrace();
            return Optional.of("Sorry, an error occurred while trying to play the video.");
        }
    }
}