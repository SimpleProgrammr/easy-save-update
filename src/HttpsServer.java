import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;

import java.awt.*;
import java.util.List;


/**
 * ---------- Configuration ----------
 * ----------------------------------------------------------------
 * Global variables
 * ----------------------------------------------------------------
 */
private static int PORT;
private static String KEYSTORE;
private static String KS_PASS;
private static String KEY_PASS;
private static Path UPDATES_PATH;

private static int MAX_QUERY_LENGTH;
private static int MAX_PARAM_COUNT;
private static int MAX_KEY_LENGTH;
private static int MAX_VALUE_LENGTH;
private static final Pattern SAFE_KEY =
        Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

private void loadConfig(Path path) throws Exception {
    if(!Files.exists(path) || !Files.isRegularFile(path)) {
        throw new Exception("Config File Not Found!");
    }
    int config_members = 9;
    var lines =  Files.readAllLines(path);
    try {
        if(lines.size() < config_members) {
            throw new Exception("Config File Error!");
        }
        PORT = Integer.parseInt(lines.getFirst());
        KEYSTORE = lines.get(1);
        KS_PASS = lines.get(2);
        KEY_PASS= lines.get(3);
        UPDATES_PATH = Path.of(lines.get(4));
        MAX_QUERY_LENGTH = Integer.parseInt(lines.get(5));
        MAX_PARAM_COUNT = Integer.parseInt(lines.get(6));
        MAX_KEY_LENGTH = Integer.parseInt(lines.get(7));
        MAX_VALUE_LENGTH = Integer.parseInt(lines.get(8));
    }
    catch (Exception e) {
        e.printStackTrace();
        throw e;
    }
}

void main() throws Exception {

    loadConfig(Path.of("./server_config.txt"));

    // 1. Załaduj keystore z certyfikatem serwera
    KeyStore ks = KeyStore.getInstance("JKS");
    try (FileInputStream fis = new FileInputStream(KEYSTORE)) {
        ks.load(fis, KS_PASS.toCharArray());
    }

    // 2. Skonfiguruj KeyManager (klucz prywatny + certyfikat)
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(ks, KEY_PASS.toCharArray());

    // 3. Skonfiguruj TrustManager (dla self-signed możemy ufać własnym certom)
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(ks);

    // 4. Zainicjalizuj SSLContext z TLS
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

    // 5. Utwórz serwer HTTPS
    com.sun.net.httpserver.HttpsServer server =
            com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress(PORT), 0);

    server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
        @Override
        public void configure(HttpsParameters params) {
            SSLContext ctx = getSSLContext();
            SSLParameters sslParams = ctx.getDefaultSSLParameters();
            params.setSSLParameters(sslParams);
        }
    });

    // 6. Rejestracja endpointów
    server.createContext("/get_newest", new DefaultHandler());
    server.createContext("/files/", new FileHandler());

    server.setExecutor(Executors.newFixedThreadPool(10));
    server.start();

    System.out.println("Serwer HTTPS uruchomiony na https://localhost:" + PORT);
}

static Map<String, String> parseQuery(String query) throws IllegalArgumentException {
    Map<String, String> params = new LinkedHashMap<>();
    if (query == null || query.isEmpty()) return params;

    // [1] Limit długości całego query stringa
    if (query.length() > MAX_QUERY_LENGTH) {
        throw new IllegalArgumentException(
                "Query string zbyt długi: " + query.length() + " > " + MAX_QUERY_LENGTH);
    }

    // Oddziel separatorem & lub ;  (RFC 3986 dopuszcza oba)
    String[] pairs = query.split("[&;]", -1);

    // [2] Limit liczby parametrów
    if (pairs.length > MAX_PARAM_COUNT) {
        throw new IllegalArgumentException(
                "Zbyt wiele parametrów: " + pairs.length + " > " + MAX_PARAM_COUNT);
    }

    for (String pair : pairs) {
        if (pair.isEmpty()) continue;  // pomiń &&, trailing &

        String[] kv = pair.split("=", 2);

        // [6] Błąd dekodowania musi być jawny
        String key = decode(kv[0]);
        String value = kv.length > 1 ? decode(kv[1]) : "";

        // [3] Limit długości klucza i wartości
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Klucz zbyt długi: " + key.length());
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Wartość zbyt długa dla klucza '" + key + "'");
        }

        // [4] Walidacja klucza – tylko znaki alfanumeryczne i _-
        if (!SAFE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Niedozwolone znaki w kluczu: '" + key + "'");
        }

        // [5] First-wins: ignoruj zduplikowane klucze (HTTP Parameter Pollution)
        params.putIfAbsent(key, value);
    }
    return params;
}
private static String decode(String s) {
    try {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
        // Nieprawidłowy % (np. %ZZ, urwane sekwencje) – NIE akceptuj po cichu
        throw new IllegalArgumentException("Błędna sekwencja URL-encoding: '" + s + "'", e);
    }
}

static void sendText(HttpExchange ex, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
    ex.sendResponseHeaders(code, bytes.length);


    var alert_lvl = code/100-1;
    Log(alert_lvl, "Sending Response: " + body);
    try (OutputStream os = ex.getResponseBody()) {
        os.write(bytes);
    }
}


public static class FileHandler implements HttpHandler {
    static final Path BASE_PATH = Path.of("./updates");

    @Override
    public void handle(HttpExchange ex) throws IOException {

        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            ex.sendResponseHeaders(405, -1);
            return;
        }


        String rawPath = ex.getRequestURI().getPath();
        String filename = rawPath.substring("/files/".length()).split("/")[0];

        if (filename.isBlank()) {
            sendText(ex, 400, "Brak nazwy pliku.");
            return;
        }

        AtomicReference<Path> wantedFile = new AtomicReference<>(Path.of(""));
        Files.walk(BASE_PATH).filter(Files::isRegularFile).forEach(file -> {
            if (file.getFileName().toString().endsWith(".xdu") && file.getFileName().toString().equals(filename)) {
                wantedFile.set(file);
            }
        });

        sendFile(ex, 200, wantedFile.get());
    }

    private void sendFile(HttpExchange ex, int code, Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String mimeType = Files.probeContentType(path);
        if (mimeType == null) mimeType = "application/octet-stream";

        ex.getResponseHeaders().set("Content-Type", mimeType);
        ex.sendResponseHeaders(code, bytes.length);

        var alert_lvl = code/100-1;
        Log(alert_lvl, "Sending File: " + path.getFileName());
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}

static class DefaultHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange ex) throws IOException {

        var p = parseQuery(ex.getRequestURI().getRawQuery());

        if (p.containsKey("serial")) {
            var fileName = getLatestVersion(p.get("serial"));
            var version = fileName.substring(0, fileName.indexOf(".xdu"));
            sendText(ex, 200,  "%s %s".formatted(version, fileName));
        }
        else
            sendText(ex, 404, "Not provided serial number");


    }


    static String getLatestVersion(String serial_number) throws IOException {
        var device = serial_number.split("-")[0];
        if (device.length() != 5) {
            return "";
        }
        for (var ser : getAvailableDeviceSeries()) {
            if (ser.equals(device)) {
                var version = getVersionForCohort(serial_number, Path.of(UPDATES_PATH + "/" + ser + "/conf.txt"));
                if(Files.exists(Path.of(UPDATES_PATH + "/%s/".formatted(device) + version))){
                    return version;
                }
                else
                    return "Update file error. Contact administrator";
            }
        }
        return "Unknown device";
    }

    static List<String> getAvailableDeviceSeries() {
        var deviceSeries = new ArrayList<String>();
        try {
            var paths = Files.walk(UPDATES_PATH).filter(Files::isDirectory);
            paths.forEach(path -> deviceSeries.add(path.getFileName().toString()));
        } catch (IOException e) {
            e.printStackTrace();
        }
        deviceSeries.remove(UPDATES_PATH.getFileName().toString());

        return deviceSeries;
    }

    static String getVersionForCohort(String serial_number, Path path) throws IOException {
        try {
            var cohort_config = Files.readAllLines(path);
            var code = serial_number.hashCode() % 16;
            for (var c : cohort_config) {
                if (Objects.equals(c.split(" ")[0], String.valueOf(code))) {
                    return c.split(" ")[1];
                }
            }
            return cohort_config.getLast().split(" ")[1];
        } catch (Exception e) {
            throw new IOException(e);
        }

    }
}


public static final String ANSI_RESET = "\u001B[0m";
public static final String ANSI_RED = "\u001B[31m";
public static final String ANSI_GREEN = "\u001B[32m";
public static final String ANSI_YELLOW = "\u001B[33m";
static void Log(int alert_lvl, String message) throws IOException {
    String base = "[%s] [%s] %s".formatted(
            timeParser(System.currentTimeMillis()),
            alert_lvl,
            message);
    var alert_color = new String[]{ANSI_GREEN, ANSI_YELLOW, ANSI_RED};
    alert_lvl = Math.min(alert_lvl, alert_color.length-1);
    System.out.println(alert_color[alert_lvl] + base + ANSI_RESET);
}

static String timeParser(long millis){
    var instant = Instant.ofEpochMilli(millis);

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    return(formatter.format(instant));

}