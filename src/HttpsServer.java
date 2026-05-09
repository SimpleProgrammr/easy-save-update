import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;


/**
 * ---------- Configuration ----------
 * ----------------------------------------------------------------
 * Global variables
 * ----------------------------------------------------------------
 */
private static final int PORT = 8443;
private static final String KEYSTORE = "kosciuszkon.jks";
private static final String KS_PASS = "HASSLO123";
private static final String KEY_PASS = "HASSLO123";
private static final Path UPDATES_PATH = Path.of("./updates");

private static final int MAX_QUERY_LENGTH = 64;
private static final int MAX_PARAM_COUNT = 2;
private static final int MAX_KEY_LENGTH = 10;
private static final int MAX_VALUE_LENGTH = 512;
private static final Pattern SAFE_KEY =
        Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

void main() throws Exception {

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
    server.createContext("/", new DefaultHandler());
    server.createContext("/file/", new FileHandler());

    server.setExecutor(Executors.newFixedThreadPool(4));
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

/** Wysyła odpowiedź */
static void sendText(HttpExchange ex, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
    ex.sendResponseHeaders(code, bytes.length);
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
        String filename = rawPath.substring("/file/".length()).split("/")[0];

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

        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}

static class DefaultHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange ex) throws IOException {

        var p = parseQuery(ex.getRequestURI().getRawQuery());

        if (p.containsKey("serial"))
            sendText(ex, 200, getLatestVersion(p.get("serial")));
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
                return getVersionForCohort(serial_number, Path.of(UPDATES_PATH + "/" + ser + "/conf.txt"));
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