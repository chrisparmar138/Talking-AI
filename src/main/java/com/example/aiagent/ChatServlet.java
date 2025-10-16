package com.example.aiagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.servlet.annotation.WebServlet;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@WebServlet("/chat")
public class ChatServlet extends HttpServlet {

    // --- DOTENV & API KEYS ---
    private static final Dotenv dotenv = Dotenv.load();
    private static final String GROQ_API_KEY = dotenv.get("GROQ_API_KEY");
    private static final String WEATHER_API_KEY = dotenv.get("WEATHER_API_KEY");
    private static final String GNEWS_API_KEY = dotenv.get("GNEWS_API_KEY");
    private static final String YOUTUBE_API_KEY = dotenv.get("YOUTUBE_API_KEY");
    private static final String HF_API_KEY = dotenv.get("HF_API_KEY");
    private static final String GOOGLE_API_KEY = dotenv.get("GOOGLE_API_KEY");
    private static final String SEARCH_ENGINE_ID = dotenv.get("SEARCH_ENGINE_ID");

    // --- API URLS & MODEL IDS ---
    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_CHAT_MODEL = dotenv.get("GROQ_CHAT_MODEL"); // Reading from .env
    private static final String GROQ_MULTIMODAL_MODEL = "google/paligemma-3b-mix-224";

    // --- GROQ SYSTEM PROMPTS ---
    private static final String COMMAND_SYSTEM_PROMPT = "You are an AI that converts user requests into Windows commands. "
            + "Your entire response MUST BE A SINGLE, VALID JSON OBJECT with 'lang' (the user's language code) and 'reply' keys. "
            + "The 'reply' must contain ONLY the raw command. For opening apps, use 'start'. "
            + "For closing, use 'taskkill /F /IM'. If it's not a command, make the 'reply' value empty.";

    private static final String CONVERSATIONAL_SYSTEM_PROMPT = "You are a helpful AI assistant. "
            + "The current UTC date and time is %s. The user's current location is approximately '%s'. "
            + "You have access to tools. To use a tool, your 'reply' value MUST BE a JSON object string: {\"tool_name\":\"...\",\"parameters\":{...}}. "
            + "Available tools: "
            + "1. 'get_weather': parameters are {\"location\":\"city, country\"}. Use the user's location as default if not specified. "
            + "2. 'get_news': parameters are {\"query\":\"...\", \"lang\":\"...\", \"country\":\"...\"}. 'query' is for the topic, 'lang' is the ISO 639-1 language code (e.g., 'en', 'hi'), 'country' is the ISO 3166-1 alpha-2 country code (e.g., 'us', 'in'). "
            + "If the user asks for general news (e.g., 'todays news'), use their current location to set the 'country' parameter. "
            + "If a user asks for weather or news, identify the parameters and call the appropriate tool. Do not answer directly. For all other questions, reply normally. "
            + "Your response MUST BE A SINGLE, VALID JSON OBJECT with 'lang' and 'reply'.";

    // --- HTTP CLIENT & JSON MAPPER ---
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(60))
            .proxy(ProxySelector.getDefault())
            .build();

    private enum TimeFormat { DATE_ONLY, TIME_ONLY, FULL }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", req.getHeader("Origin"));
        resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        resp.setHeader("Access-control-allow-credentials", "true");
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Access-Control-Allow-Origin", req.getHeader("Origin"));
        resp.setHeader("Access-Control-Allow-Credentials", "true");
        resp.setContentType("application/json");

        try {
            Map<String, Object> payload = objectMapper.readValue(req.getInputStream(), new TypeReference<>() {});
            String userMessage = (String) payload.get("message");
            String userLang = (String) payload.getOrDefault("lang", "en-US");
            String imageUrl = (String) payload.get("imageUrl");
            Map<String, Double> location = (Map<String, Double>) payload.get("location");
            String timeZone = (String) payload.get("timeZone");

            if (imageUrl != null && !imageUrl.trim().isEmpty()) {
                String visionResponseJson = callGroqVisionApi(userMessage, imageUrl, userLang);
                Map<String, Object> visionResponseMap = objectMapper.readValue(visionResponseJson, new TypeReference<>() {});
                List<Map<String, Object>> choices = (List<Map<String, Object>>) visionResponseMap.get("choices");
                String content = "Sorry, I couldn't understand that image.";
                if (choices != null && !choices.isEmpty()) {
                    Map<String, String> message = (Map<String, String>) choices.get(0).get("message");
                    content = message.get("content");
                }
                objectMapper.writeValue(resp.getOutputStream(), Map.of("reply", content, "lang", userLang));
                return;
            }

            Optional<Map<String, String>> localCommandResponse = handleLocalCommands(userMessage, userLang, timeZone);
            if (localCommandResponse.isPresent()) {
                objectMapper.writeValue(resp.getOutputStream(), localCommandResponse.get());
                return;
            }

            Map<String, String> commandContentMap = callGroqApiWithJson(userMessage, COMMAND_SYSTEM_PROMPT, GROQ_CHAT_MODEL, userLang);
            String commandToExecute = commandContentMap.get("reply");
            String detectedLangForCommand = commandContentMap.getOrDefault("lang", userLang);

            if (commandToExecute != null && !commandToExecute.trim().isEmpty()) {
                Runtime.getRuntime().exec("cmd /c " + commandToExecute);
                String confirmationPrompt = "Tell the user in a short, friendly way that their request '" + userMessage + "' was successful.";
                String currentTimeUTC = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
                String dynamicSystemPrompt = String.format(CONVERSATIONAL_SYSTEM_PROMPT, currentTimeUTC, "Unknown");
                objectMapper.writeValue(resp.getOutputStream(), callGroqApiForConversation(confirmationPrompt, dynamicSystemPrompt, GROQ_CHAT_MODEL, detectedLangForCommand));
            } else {
                String userLocationStr = "Unknown";
                if (location != null && location.containsKey("latitude")) {
                    Optional<String> addressOpt = getAddressFromCoordinates(location.get("latitude"), location.get("longitude"));
                    if(addressOpt.isPresent()){
                        userLocationStr = addressOpt.get().replaceFirst("Based on your browser's coordinates, you are in or near ", "").replace(".", "");
                    }
                }
                String currentTimeUTC = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
                String dynamicSystemPrompt = String.format(CONVERSATIONAL_SYSTEM_PROMPT, currentTimeUTC, userLocationStr);

                Map<String, String> aiResponse = callGroqApiForConversation(userMessage, dynamicSystemPrompt, GROQ_CHAT_MODEL, userLang);
                String replyContent = aiResponse.get("reply");
                String lang = aiResponse.get("lang");

                try {
                    Map<String, Object> toolCall = objectMapper.readValue(replyContent, new TypeReference<>() {});
                    String toolName = (String) toolCall.get("tool_name");
                    // ** NEW ** Safely get parameters to prevent crashes
                    Object paramsObj = toolCall.get("parameters");
                    Map<String, String> params = new HashMap<>();
                    if (paramsObj instanceof Map) {
                        params = (Map<String, String>) paramsObj;
                    }

                    if ("get_weather".equals(toolName)) {
                        String locationForWeather = params.get("location");
                        if (locationForWeather == null || locationForWeather.isEmpty()) {
                            objectMapper.writeValue(resp.getOutputStream(), Map.of("reply", "The AI did not specify a location for the weather tool.", "lang", lang));
                            return;
                        }
                        Optional<Map<String, Double>> coords = getCoordinatesFromLocationName(locationForWeather);
                        Optional<String> weatherUpdate = coords.flatMap(c -> getWeatherUpdate(c.get("lat"), c.get("lon")))
                                .or(() -> Optional.of("Sorry, I couldn't find coordinates for " + locationForWeather));
                        objectMapper.writeValue(resp.getOutputStream(), Map.of("reply", weatherUpdate.get(), "lang", lang));

                    } else if ("get_news".equals(toolName)) {
                        String query = params.getOrDefault("query", "");
                        String langCode = params.getOrDefault("lang", "en");
                        String countryCode = params.getOrDefault("country", "");

                        // If country isn't specified by AI, try to infer from user's overall lang setting (e.g., en-IN -> in)
                        if (countryCode.isEmpty() && userLang != null && userLang.contains("-")) {
                            countryCode = userLang.split("-")[1].toLowerCase();
                        } else if (countryCode.isEmpty()) {
                            countryCode = "us"; // Ultimate fallback
                        }

                        Optional<String> newsUpdate = getNewsHeadlines(query, langCode, countryCode);
                        objectMapper.writeValue(resp.getOutputStream(), Map.of("reply", newsUpdate.orElse("Sorry, I couldn't fetch the news for that request."), "lang", lang));

                    } else {
                        objectMapper.writeValue(resp.getOutputStream(), aiResponse);
                    }
                } catch (JsonProcessingException e) {
                    objectMapper.writeValue(resp.getOutputStream(), aiResponse);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            objectMapper.writeValue(resp.getOutputStream(), Map.of("error", "Failed to process request: " + e.getMessage()));
        }
    }

    private Optional<Map<String, String>> handleLocalCommands(String command, String lang, String timeZone) {
        Function<String, Optional<Map<String, String>>> createResponse = (reply) -> Optional.of(Map.of("reply", reply, "lang", lang));
        String lowerCaseCommand = command.toLowerCase().trim();

        List<String> dateQueries = Arrays.asList("current date", "what is the date", "what's the date", "date");
        List<String> timeQueries = Arrays.asList("current time", "what is the time", "what's the time", "time");
        List<String> fullDateTimeQueries = Arrays.asList("current date and time", "date and time");

        if (dateQueries.contains(lowerCaseCommand)) return getCurrentTimeAndDate(timeZone, TimeFormat.DATE_ONLY).flatMap(createResponse);
        if (timeQueries.contains(lowerCaseCommand)) return getCurrentTimeAndDate(timeZone, TimeFormat.TIME_ONLY).flatMap(createResponse);
        if (fullDateTimeQueries.contains(lowerCaseCommand)) return getCurrentTimeAndDate(timeZone, TimeFormat.FULL).flatMap(createResponse);

        if (lowerCaseCommand.startsWith("draw") || lowerCaseCommand.startsWith("create an image of")) return generateImageWithHuggingFace(command.replaceFirst("(?i)draw|create an image of", "").trim()).flatMap(createResponse);
        if (lowerCaseCommand.startsWith("create a video of") || lowerCaseCommand.startsWith("generate a video of")) return generateVideoWithHuggingFace(command.replaceFirst("(?i)create a video of|generate a video of", "").trim()).flatMap(createResponse);
        if (lowerCaseCommand.startsWith("compose music about") || lowerCaseCommand.startsWith("create a song about")) return generateMusicWithHuggingFace(command.replaceFirst("(?i)compose music about|create a song about", "").trim()).flatMap(createResponse);
        if (lowerCaseCommand.startsWith("search")) {
            if (lowerCaseCommand.contains("in browser")) {
                String query = command.replaceFirst("(?i)search in browser", "").trim();
                return searchInBrowser(query).flatMap(createResponse);
            } else {
                String query = command.replaceFirst("(?i)search", "").trim();
                return searchWithApi(query).flatMap(createResponse);
            }
        }
        if (lowerCaseCommand.startsWith("play")) return playFirstVideoOnYouTube(command.substring(4).replaceAll("(?i)\\s*on youtube$", "").trim()).flatMap(createResponse);

        return Optional.empty();
    }

    private Optional<String> getNewsHeadlines(String query, String lang, String country) {
        if (GNEWS_API_KEY == null || GNEWS_API_KEY.startsWith("your_")) return Optional.of("GNews API key not configured.");
        try {
            StringBuilder urlBuilder = new StringBuilder("https://gnews.io/api/v4/");

            boolean isSearch = query != null && !query.isEmpty();
            urlBuilder.append(isSearch ? "search?q=" : "top-headlines?");
            if (isSearch) {
                urlBuilder.append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            }

            urlBuilder.append("&lang=").append(URLEncoder.encode(lang, StandardCharsets.UTF_8));
            urlBuilder.append("&country=").append(URLEncoder.encode(country, StandardCharsets.UTF_8));
            urlBuilder.append("&max=3&apikey=").append(GNEWS_API_KEY.trim());

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlBuilder.toString())).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> data = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Map<String, Object>> articles = (List<Map<String, Object>>) data.get("articles");
            if (articles == null || articles.isEmpty()) return Optional.of("I couldn't find any news headlines for that request.");
            StringBuilder builder = new StringBuilder("Here are the top headlines. ");
            articles.forEach(article -> builder.append("Headline: ").append(article.get("title")).append(". "));
            return Optional.of(builder.toString());
        } catch (Exception e) {
            e.printStackTrace();
            return Optional.of("Error fetching news.");
        }
    }

    private Optional<String> getCurrentTimeAndDate(String timeZone, TimeFormat format) {
        try {
            ZoneId userZoneId = ZoneId.of(timeZone != null && !timeZone.isEmpty() ? timeZone : "UTC");
            ZonedDateTime now = ZonedDateTime.now(userZoneId);
            DateTimeFormatter formatter; String prefix;
            switch (format) {
                case DATE_ONLY: formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"); prefix = "The current date is "; break;
                case TIME_ONLY: formatter = DateTimeFormatter.ofPattern("h:mm a"); prefix = "The current time is "; break;
                default: formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a"); prefix = "The current date and time is "; break;
            }
            return Optional.of(prefix + now.format(formatter));
        } catch (Exception e) { return Optional.of("Sorry, I had trouble getting the time for your location."); }
    }

    private Optional<Map<String, Double>> getCoordinatesFromLocationName(String locationName) {
        if (WEATHER_API_KEY == null) return Optional.empty();
        try {
            String encodedLocation = URLEncoder.encode(locationName, StandardCharsets.UTF_8);
            String url = String.format("http://api.openweathermap.org/geo/1.0/direct?q=%s&limit=1&appid=%s", encodedLocation, WEATHER_API_KEY);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            List<Map<String, Object>> locations = objectMapper.readValue(response.body(), new TypeReference<>() {});
            if (locations.isEmpty()) return Optional.empty();
            Map<String, Object> firstResult = locations.get(0);
            return Optional.of(Map.of("lat", ((Number) firstResult.get("lat")).doubleValue(), "lon", ((Number) firstResult.get("lon")).doubleValue()));
        } catch (Exception e) { e.printStackTrace(); return Optional.empty(); }
    }

    private Optional<String> getAddressFromCoordinates(double lat, double lon) {
        if (WEATHER_API_KEY == null) return Optional.of("Weather API key not configured for location services.");
        String url = String.format("http://api.openweathermap.org/geo/1.0/reverse?lat=%f&lon=%f&limit=1&appid=%s", lat, lon, WEATHER_API_KEY);
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            List<Map<String, Object>> locations = objectMapper.readValue(response.body(), new TypeReference<>() {});
            if (locations.isEmpty()) return Optional.of("I couldn't determine your location from the coordinates.");
            return Optional.of(String.format("Based on your browser's coordinates, you are in or near %s, %s.", locations.get(0).get("name"), locations.get(0).get("country")));
        } catch (Exception e) { return Optional.of("Error getting address from coordinates."); }
    }

    private Optional<String> getWeatherUpdate(double lat, double lon) {
        if (WEATHER_API_KEY == null) return Optional.of("Weather API key not configured.");
        String url = String.format("https://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&units=metric&appid=%s", lat, lon, WEATHER_API_KEY);
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> data = objectMapper.readValue(response.body(), new TypeReference<>() {});
            String city = (String) data.get("name");
            String desc = ((List<Map<String, Object>>) data.get("weather")).get(0).get("description").toString();
            double temp = ((Number) ((Map<String, Object>) data.get("main")).get("temp")).doubleValue();
            return Optional.of(String.format("The current weather in %s is %s with a temperature of %d degrees Celsius.", city, desc, Math.round(temp)));
        } catch (Exception e) { return Optional.of("Error fetching weather."); }
    }

    private Optional<String> getDirections(double lat, double lon, String destination) {
        if (GOOGLE_API_KEY == null) return Optional.of("Google Maps API is not configured.");
        try {
            String origin = lat + "," + lon; String encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8);
            String url = String.format("https://maps.googleapis.com/maps/api/directions/json?origin=%s&destination=%s&key=%s", origin, encodedDestination, GOOGLE_API_KEY);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> data = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Map<String, Object>> routes = (List<Map<String, Object>>) data.get("routes");
            if (routes == null || routes.isEmpty()) return Optional.of("Sorry, I couldn't find a route to " + destination);
            Map<String, Object> firstRoute = routes.get(0); String summary = (String) firstRoute.get("summary");
            List<Map<String, Object>> legs = (List<Map<String, Object>>) firstRoute.get("legs");
            Map<String, String> distance = (Map<String, String>) legs.get(0).get("distance");
            Map<String, String> duration = (Map<String, String>) legs.get(0).get("duration");
            return Optional.of(String.format("The best route to %s is via %s. The distance is %s and it will take approximately %s.", destination, summary, distance.get("text"), duration.get("text")));
        } catch (Exception e) { return Optional.of("Error fetching directions."); }
    }

    private Optional<String> generateImageWithHuggingFace(String prompt) {
        if (HF_API_KEY == null || HF_API_KEY.startsWith("your_")) return Optional.of("Hugging Face API key not configured.");
        String modelUrl = "https://api-inference.huggingface.co/models/stabilityai/stable-diffusion-xl-base-1.0";
        try {
            String jsonPayload = objectMapper.writeValueAsString(Map.of("inputs", prompt));
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(modelUrl)).header("Authorization", "Bearer " + HF_API_KEY).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                String errorBody = new String(response.body(), StandardCharsets.UTF_8);
                if (errorBody.contains("is currently loading")) return Optional.of("The AI image model is warming up. Please try again in a moment.");
                return Optional.of("Sorry, I couldn't create the image with the free service right now.");
            }
            return Optional.of("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(response.body()));
        } catch (Exception e) { return Optional.of("Error generating image."); }
    }

    private Optional<String> generateVideoWithHuggingFace(String prompt) {
        if (HF_API_KEY == null || HF_API_KEY.startsWith("your_")) return Optional.of("Hugging Face API key not configured.");
        String modelUrl = "https://api-inference.huggingface.co/models/cerspense/zeroscope_v2_576w";
        try {
            String jsonPayload = objectMapper.writeValueAsString(Map.of("inputs", prompt));
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(modelUrl)).header("Authorization", "Bearer " + HF_API_KEY).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return Optional.of("Sorry, the video model is busy.");
            return Optional.of("data:video/mp4;base64," + Base64.getEncoder().encodeToString(response.body()));
        } catch (Exception e) { return Optional.of("Error generating video."); }
    }

    private Optional<String> generateMusicWithHuggingFace(String prompt) {
        if (HF_API_KEY == null || HF_API_KEY.startsWith("your_")) return Optional.of("Hugging Face API key not configured.");
        String modelUrl = "https://api-inference.huggingface.co/models/facebook/musicgen-small";
        try {
            String jsonPayload = objectMapper.writeValueAsString(Map.of("inputs", prompt));
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(modelUrl)).header("Authorization", "Bearer " + HF_API_KEY).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return Optional.of("Sorry, the music model is busy.");
            return Optional.of("data:audio/flac;base64," + Base64.getEncoder().encodeToString(response.body()));
        } catch (Exception e) { return Optional.of("Error generating music."); }
    }

    private Optional<String> searchWithApi(String query) {
        if (GOOGLE_API_KEY == null || SEARCH_ENGINE_ID == null) return Optional.of("Google Search API is not configured.");
        if (query.isEmpty()) return Optional.of("What should I search for?");
        try {
            String apiUrl = String.format("https://www.googleapis.com/customsearch/v1?key=%s&cx=%s&q=%s&num=3", GOOGLE_API_KEY, SEARCH_ENGINE_ID, URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.of("Sorry, I couldn't perform the search.");
            Map<String, Object> responseData = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Map<String, String>> items = (List<Map<String, String>>) responseData.get("items");
            if (items == null || items.isEmpty()) return Optional.of("I couldn't find any results for '" + query + "'.");
            StringBuilder result = new StringBuilder("Here are the top results for '" + query + "'. ");
            items.forEach(item -> result.append("Result: ").append(item.get("title")).append(". "));
            return Optional.of(result.toString());
        } catch (Exception e) { return Optional.of("An error occurred while searching the web."); }
    }

    private Optional<String> searchInBrowser(String query) {
        if (query.isEmpty()) return Optional.of("What should I search for?");
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(new URI("https://www.google.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)));
                return Optional.of("I've opened the search results for '" + query + "' in your browser.");
            } catch (Exception e) { return Optional.of("Could not open browser."); }
        }
        return Optional.of("Cannot open browser on this system.");
    }

    private Optional<String> playFirstVideoOnYouTube(String query) {
        if (YOUTUBE_API_KEY == null || YOUTUBE_API_KEY.startsWith("your_")) return Optional.of("YouTube API key not configured.");
        if (query.isEmpty()) return Optional.of("What video should I play?");
        try {
            String apiUrl = String.format("https://www.googleapis.com/youtube/v3/search?part=snippet&maxResults=1&q=%s&type=video&key=%s", URLEncoder.encode(query, StandardCharsets.UTF_8), YOUTUBE_API_KEY);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String,Object> data = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Map<String,Object>> items = (List<Map<String,Object>>) data.get("items");
            if(items == null || items.isEmpty()) return Optional.of("No video found for '" + query + "'.");
            String videoId = ((Map<String,Object>) items.get(0).get("id")).get("videoId").toString();
            Desktop.getDesktop().browse(new URI("https://www.youtube.com/watch?v=" + videoId));
            return Optional.of("Playing the top result for '" + query + "' on YouTube.");
        } catch (Exception e) { return Optional.of("Error playing video."); }
    }

    private Map<String, String> callGroqApiWithJson(String userMessage, String systemPrompt, String model, String userLang) throws IOException, InterruptedException {
        if (GROQ_API_KEY == null || GROQ_API_KEY.startsWith("your_")) return Map.of("lang", userLang, "reply", "Groq API Key not configured.");
        List<Map<String, String>> messages = List.of(Map.of("role", "system", "content", systemPrompt), Map.of("role", "user", "content", userMessage));
        String jsonPayload = objectMapper.writeValueAsString(Map.of("messages", messages, "model", model, "response_format", Map.of("type", "json_object")));
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(GROQ_API_URL)).header("Authorization", "Bearer " + GROQ_API_KEY).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return Map.of("lang", userLang, "reply", "AI Service Error");
        Map<String, Object> outerMap = objectMapper.readValue(response.body(), new TypeReference<>() {});
        List<Map<String, Object>> choices = (List<Map<String, Object>>) outerMap.get("choices");
        if (choices != null && !choices.isEmpty()) {
            Map<String, String> message = (Map<String, String>) choices.get(0).get("message");
            return objectMapper.readValue(message.get("content"), new TypeReference<>() {});
        }
        return Map.of("lang", userLang, "reply", "Could not parse AI response.");
    }

    private Map<String, String> callGroqApiForConversation(String userMessage, String systemPrompt, String model, String userLang) throws IOException, InterruptedException {
        if (GROQ_API_KEY == null || GROQ_API_KEY.startsWith("your_")) return Map.of("lang", userLang, "reply", "Groq API Key not configured.");

        List<Map<String, String>> messages = List.of(Map.of("role", "system", "content", systemPrompt), Map.of("role", "user", "content", userMessage));
        String jsonPayload = objectMapper.writeValueAsString(Map.of("messages", messages, "model", model, "response_format", Map.of("type", "json_object")));
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(GROQ_API_URL)).header("Authorization", "Bearer " + GROQ_API_KEY).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return Map.of("lang", userLang, "reply", "AI Service Error");
        Map<String, Object> outerMap = objectMapper.readValue(response.body(), new TypeReference<>() {});
        List<Map<String, Object>> choices = (List<Map<String, Object>>) outerMap.get("choices");
        if (choices != null && !choices.isEmpty()) {
            Map<String, String> message = (Map<String, String>) choices.get(0).get("message");
            return objectMapper.readValue(message.get("content"), new TypeReference<>() {});
        }
        return Map.of("lang", userLang, "reply", "Could not parse AI response.");
    }

    private String callGroqVisionApi(String userMessage, String imageUrl, String userLang) throws IOException, InterruptedException {
        if (GROQ_API_KEY == null || GROQ_API_KEY.startsWith("your_")) return "{\"choices\":[{\"message\":{\"content\":\"Groq API Key not configured.\"}}]}";
        List<Map<String, Object>> contentList = new ArrayList<>();
        contentList.add(Map.of("type", "text", "text", userMessage));
        contentList.add(Map.of("type", "image_url", "image_url", Map.of("url", imageUrl)));
        Map<String, Object> userMessagePayload = new LinkedHashMap<>();
        userMessagePayload.put("role", "user");
        userMessagePayload.put("content", contentList);
        List<Map<String, Object>> messages = List.of(userMessagePayload);
        String jsonPayload = objectMapper.writeValueAsString(Map.of("messages", messages, "model", GROQ_MULTIMODAL_MODEL));
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(GROQ_API_URL)).header("Authorization", "Bearer " + GROQ_API_KEY).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return "{\"choices\":[{\"message\":{\"content\":\"Sorry, I had trouble understanding that image.\"}}]}";
        return response.body();
    }
}