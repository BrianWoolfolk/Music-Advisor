package advisor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.net.URLDecoder;
import java.util.stream.Collectors;

public class Main {
    // FINAL VARIABLES
    public static final String CLIENT_SEC = "76531d2f43f841be870fa82972b56210";
    public static final String CLIENT_ID = "b2a89b791194490680c6a8741b86ebcb";

    public static String AUTH_API = "https://accounts.spotify.com";
    public static String RESOURCE_API = "https://api.spotify.com";
    public static int PAGE_LEN = 5;

    public static int CURR_PAGE = 0;
    public static int MAX_PAGE = 0;
    public static String CURR_OPT = null;

    public static final String LOCAL = "http://localhost:8080";
    public static final String AUTH_URL = "/authorize" +
            "?client_id=" + CLIENT_ID +
            "&redirect_uri=" + LOCAL +
            "&response_type=code";

    private static String code = null;
    private static String token = null;
    private static volatile boolean waiting = false;
    private static final HashMap<String, String> CATEGORY_IDs = new HashMap<>();

    public static void getAuth() throws IOException, InterruptedException {
        // HTTP SERVER
        HttpServer server = HttpServer.create();
        server.bind(new InetSocketAddress(8080), 0);
        code = null;

        // SERVER READ CODE
        server.createContext("/",
                exchange -> {
                    String query = exchange.getRequestURI().getQuery();

                    if (query != null) {
                        var result = QueryParser.parseQueryToParams(query);
                        code = result.get("code");
                    }

                    String message;
                    if (code != null) {
                        message = "Got the code. Return back to your program.";
                        exchange.sendResponseHeaders(200, message.length());
                        exchange.getResponseBody().write(message.getBytes());
                        exchange.getResponseBody().close();
                        waiting = false;
                    } else {
                        message = "Authorization code not found. Try again.";
                        exchange.sendResponseHeaders(400, message.length());
                        exchange.getResponseBody().write(message.getBytes());
                        exchange.getResponseBody().close();
                    }
                }
        );

        System.out.println("use this link to request the access code:");
        System.out.println(AUTH_API + AUTH_URL);
        System.out.println("waiting for code...");
        waiting = true;

        server.start();
        while (waiting) {
            Thread.onSpinWait();
        }

        server.stop(1);
        if (code == null) {
            return;
        }

        System.out.println("code received");
        System.out.println("making http request for access_token...");

        String authorization = CLIENT_ID + ":" + CLIENT_SEC;
        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", Base64.getEncoder().encodeToString(authorization.getBytes()))
                .uri(URI.create(AUTH_API + "/api/token"))
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code&code=" + code + "&redirect_uri=" + LOCAL))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject jo = JsonParser.parseString(response.body()).getAsJsonObject();
        token = jo.get("access_token").getAsString();
        System.out.println("Success!");
    }

    public static boolean isNotAuth() {
        if (token == null) {
            System.out.println("Please, provide access for application.");
            return true;
        }
        return false;
    }

    /**
     * Gets the Spotify API
     *
     * @param type 2 for "new", 1 for "featured", 0 for "categories"
     * @throws IOException          For something
     * @throws InterruptedException And some description
     */
    public static void getAPI(int type, boolean print) throws IOException, InterruptedException {
        if (isNotAuth()) return;

        if (CURR_PAGE < 1) CURR_PAGE = 1;

        String URL_PATH = "/v1/browse/";
        if (type >= 2) URL_PATH += "new-releases";
        else if (type == 1) URL_PATH += "featured-playlists";
        else URL_PATH += "categories";

        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create(RESOURCE_API + URL_PATH + "?offset=" + (CURR_PAGE - 1) + "&limit=" + PAGE_LEN))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject jo = JsonParser.parseString(response.body()).getAsJsonObject();

        // CHECK FOR ERRORS
        JsonElement err = jo.getAsJsonObject("error");
        if (err != null) {
            System.out.println("Specified id doesn't exist");
            return;
        }

        // PARSE RESPONSE
        JsonObject jarr;
        if (type >= 2) jarr = jo.getAsJsonObject("albums");
        else if (type == 0) jarr = jo.getAsJsonObject("categories");
        else jarr = jo.getAsJsonObject("playlists");

        // SAVE PAGINATION
        if (print) {
            int tot = jarr.get("total").getAsInt();
            MAX_PAGE = (int) Math.ceil((double) tot / PAGE_LEN);
        }

        var items = jarr.getAsJsonArray("items").asList();
        for (
            int i = (CURR_PAGE - 1) * PAGE_LEN;
             i < items.size() && i < CURR_PAGE * PAGE_LEN;
             i++
        ) {
            var entry = items.get(i).getAsJsonObject();

            if (type == 0) {
                String name = entry.get("name").getAsString();
                String id = entry.get("id").getAsString();

                if (CATEGORY_IDs.isEmpty() || !print) CATEGORY_IDs.putIfAbsent(name, id);
                if (print) System.out.println(name);
            } else {
                System.out.println(entry.get("name").getAsString());
            }

            if (type >= 2) {
                // Using Stream API to map each artist name
                List<String> artists = entry.getAsJsonArray("artists").asList().stream()
                        .map(n -> n.getAsJsonObject().get("name").getAsString())
                        .collect(Collectors.toList());
                System.out.println(artists);
            }

            if (type >= 1) {
                System.out.println(entry.getAsJsonObject("external_urls").get("spotify").getAsString());
                System.out.println();
            }
        }

        if (print) System.out.println("---PAGE " + CURR_PAGE + " OF " + MAX_PAGE + "---");
    }

    public static void getCategoryPlaylists(String categoryname) throws IOException, InterruptedException {
        if (isNotAuth()) return;

        if (CURR_PAGE < 1) CURR_PAGE = 1;

        // REFRESH
        if (CATEGORY_IDs.isEmpty()) {
            int temp1 = CURR_PAGE;
            int temp2 = MAX_PAGE;
            int temp3 = PAGE_LEN;

            CURR_PAGE = 0;
            MAX_PAGE = 0;
            PAGE_LEN = 20;

            getAPI(0, false);

            CURR_PAGE = temp1;
            MAX_PAGE = temp2;
            PAGE_LEN = temp3;
        }

        if (categoryname == null || categoryname.isEmpty() || CATEGORY_IDs.get(categoryname) == null) {
            System.out.println("Unknown category name.");
            return;
        }

        String categoryID = CATEGORY_IDs.get(categoryname);
        String URL_PATH = "/v1/browse/categories/" + categoryID + "/playlists";

        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create(RESOURCE_API + URL_PATH + "?offset=" + (CURR_PAGE - 1) + "&limit=" + PAGE_LEN))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject jo = JsonParser.parseString(response.body()).getAsJsonObject();

        // CHECK FOR ERRORS
        JsonObject err = jo.getAsJsonObject("error");
        if (err != null) {
            System.out.println(err.get("message").getAsString());
            return;
        }

        // PARSE RESPONSE
        JsonObject jarr = jo.getAsJsonObject("playlists");

        // SAVE PAGINATION
        int tot = jarr.get("total").getAsInt();
        MAX_PAGE = (int) Math.ceil((double) tot / PAGE_LEN);

        // PARSE RESPONSE
        var items = jarr.getAsJsonArray("items").asList();
        for (
            int i = (CURR_PAGE - 1) * PAGE_LEN;
            i < items.size() && i < CURR_PAGE * PAGE_LEN;
            i++
        ) {
            var entry = items.get(i).getAsJsonObject();
            System.out.println(entry.get("name").getAsString());
            System.out.println(entry.getAsJsonObject("external_urls").get("spotify").getAsString());
            System.out.println();
        }

        System.out.println("---PAGE " + CURR_PAGE + " OF " + MAX_PAGE + "---");
    }

    public static void handlePagination(int step) throws IOException, InterruptedException {
        if ((CURR_PAGE + step > MAX_PAGE) || (CURR_PAGE + step < 1)) {
            System.out.println("No more pages.");
            return;
        }
        if (CURR_OPT == null) return;

        CURR_PAGE += step;

        switch (CURR_OPT) {
            case "new": {
                getAPI(2, true);
                break;
            }
            case "featured": {
                getAPI(1, true);
                break;
            }
            case "categories": {
                getAPI(0, true);
                break;
            }
            default: {
                if (CURR_OPT.startsWith("playlists ")) {
                    getCategoryPlaylists(CURR_OPT.substring(10));
                }
            }
        }
    }

    // MAIN FUNCTION
    public static void main(String[] args) throws IOException, InterruptedException {
        // READ ARGS
        if (args.length >= 2 && Objects.equals(args[0], "-access")) {
            AUTH_API = args[1];
        }
        if (args.length >= 4 && Objects.equals(args[2], "-resource")) {
            RESOURCE_API = args[3];
        }
        if (args.length >= 6 && Objects.equals(args[4], "-page")) {
            PAGE_LEN = Integer.parseInt(args[5]);
            if (PAGE_LEN < 1) PAGE_LEN = 5;
        }

        // INPUT READING
        Scanner scn = new Scanner(System.in);
        String option;

        // MAIN LOOP
        while (true) {
            option = scn.nextLine();

            switch (option) {
                case "new": {
                    CURR_PAGE = 0;
                    MAX_PAGE = 0;
                    CURR_OPT = "new";
                    getAPI(2, true);
                    break;
                }
                case "featured": {
                    CURR_PAGE = 0;
                    MAX_PAGE = 0;
                    CURR_OPT = "featured";
                    getAPI(1, true);
                    break;
                }
                case "categories": {
                    CURR_PAGE = 0;
                    MAX_PAGE = 0;
                    CURR_OPT = "categories";
                    getAPI(0, true);
                    break;
                }
                case "auth":
                    getAuth();
                    break;
                case "exit":
//                    return;
                    continue;
                case "prev":
                    handlePagination(-1);
                    break;
                case "next":
                    handlePagination(1);
                    break;
                default:
                    if (option.startsWith("playlists ")) {
                        CURR_PAGE = 0;
                        MAX_PAGE = 0;
                        CURR_OPT = option;
                        getCategoryPlaylists(option.substring(10));
                    } else return;
            }
        }
    }
}


class QueryParser {
    /* https://stackoverflow.com/questions/11640025/how-to-obtain-the-query-string-in-a-get-with-java-httpserver-httpexchange */
    public static Map<String, String> parseQueryToParams(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;

        int last = 0;
        int next;
        int l = query.length();

        while (last < l) {
            next = query.indexOf('&', last);
            if (next == -1) {
                next = l;
            }

            if (next > last) {
                int eqPos = query.indexOf('=', last);
                if (eqPos < 0 || eqPos > next) {
                    result.put(URLDecoder.decode(query.substring(last, next), StandardCharsets.UTF_8), "");
                } else {
                    result.put(URLDecoder.decode(query.substring(last, eqPos), StandardCharsets.UTF_8),
                            URLDecoder.decode(query.substring(eqPos + 1, next), StandardCharsets.UTF_8));
                }
            }
            last = next + 1;
        }
        return result;
    }
}