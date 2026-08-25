package com.pulseink.controller.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "pulseink.auth.jwt-secret=01234567890123456789012345678901",
            "pulseink.auth.demo-password=pulseink-demo",
            "pulseink.model.provider=fake"
        })
@ActiveProfiles("local")
class CampaignControllerIT {

    private static final Pattern LOCATION_ID =
            Pattern.compile("/api/campaigns/(\\d+)$");

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7")
            .withDatabaseName("pulseink")
            .withUsername("pulseink")
            .withPassword("pulseink_dev");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void cleanCampaigns() {
        jdbcTemplate.execute("DELETE FROM campaign");
    }

    @Test
    void anonymousListDetailAndCreateAreUnauthorized() throws Exception {
        assertThat(get("/api/campaigns").statusCode()).isEqualTo(401);
        assertThat(get("/api/campaigns/1").statusCode()).isEqualTo(401);
        assertThat(postAnonymous("/api/campaigns", validBody()).statusCode()).isEqualTo(401);
    }

    @Test
    void editorCreateReturnsCreatedWithLocationDraftAndActor() throws Exception {
        var token = token(1L, "EDITOR");
        var response = post("/api/campaigns", token, validBody());

        assertThat(response.statusCode()).isEqualTo(201);
        var location = response.headers().firstValue("Location").orElse("");
        assertThat(location).startsWith("/api/campaigns/");
        var id = extractId(location);
        assertThat(response.body()).contains("\"status\":\"DRAFT\"");
        assertThat(response.body()).contains("\"createdBy\":1");
        assertThat(response.body()).contains("\"name\":\"PulseInk 秋招发布\"");
        assertThat(response.body()).contains("\"version\":0");

        var detail = get("/api/campaigns/" + id, token);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("\"id\":" + id);
    }

    @Test
    void adminCreateReturnsCreated() throws Exception {
        var token = token(2L, "ADMIN");
        var response = post("/api/campaigns", token, validBody());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"createdBy\":2");
    }

    @Test
    void viewerCreateIsForbidden() throws Exception {
        var token = token(3L, "VIEWER");
        var response = post("/api/campaigns", token, validBody());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void viewerCanListAndReadDetail() throws Exception {
        var viewerToken = token(3L, "VIEWER");
        var editorToken = token(1L, "EDITOR");
        var created = post("/api/campaigns", editorToken, validBody());
        var id = extractId(created.headers().firstValue("Location").orElse(""));

        assertThat(get("/api/campaigns", viewerToken).statusCode()).isEqualTo(200);
        assertThat(get("/api/campaigns/" + id, viewerToken).statusCode()).isEqualTo(200);
    }

    @Test
    void bodySuppliedAuditFieldsCannotOverrideServerValues() throws Exception {
        var token = token(1L, "EDITOR");
        var body = """
                {"name":"Override Test","objective":"o","audience":"a",
                 "channels":["BLOG"],"constraints":[],
                 "createdBy":999,"status":"PUBLISHED","version":42,
                 "createdAt":"1999-01-01T00:00:00Z","updatedAt":"1999-01-01T00:00:00Z"}
                """;
        var response = post("/api/campaigns", token, body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"createdBy\":1");
        assertThat(response.body()).contains("\"status\":\"DRAFT\"");
        assertThat(response.body()).contains("\"version\":0");
        assertThat(response.body()).doesNotContain("999");
        assertThat(response.body()).doesNotContain("PUBLISHED");
        assertThat(response.body()).doesNotContain("1999");
    }

    @Test
    void invalidFieldChannelPageAndSizeReturn400WithStableCode() throws Exception {
        var token = token(1L, "EDITOR");

        var blankName = post("/api/campaigns", token, """
                {"name":"","objective":"o","audience":"a","channels":["BLOG"],"constraints":[]}
                """);
        assertThat(blankName.statusCode()).isEqualTo(400);
        assertThat(blankName.body()).contains("\"code\":\"INVALID_CAMPAIGN\"");

        var badChannel = post("/api/campaigns", token, """
                {"name":"Bad","objective":"o","audience":"a","channels":["PODCAST"],"constraints":[]}
                """);
        assertThat(badChannel.statusCode()).isEqualTo(400);
        assertThat(badChannel.body()).contains("\"code\":\"INVALID_CAMPAIGN\"");

        assertThat(get("/api/campaigns?page=-1&size=20", token).statusCode()).isEqualTo(400);
        assertThat(get("/api/campaigns?page=0&size=0", token).statusCode()).isEqualTo(400);
        assertThat(get("/api/campaigns?page=0&size=101", token).statusCode()).isEqualTo(400);
    }

    @Test
    void absentPositiveIdReturns404WithCampaignNotFoundCode() throws Exception {
        var token = token(1L, "EDITOR");
        var response = get("/api/campaigns/999999", token);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"CAMPAIGN_NOT_FOUND\"");
    }

    @Test
    void listReportsMetadataInNewestFirstOrder() throws Exception {
        var token = token(1L, "EDITOR");
        var first = post("/api/campaigns", token, bodyWithName("First"));
        var second = post("/api/campaigns", token, bodyWithName("Second"));
        var third = post("/api/campaigns", token, bodyWithName("Third"));

        var response = get("/api/campaigns?page=0&size=20", token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"page\":0");
        assertThat(response.body()).contains("\"size\":20");
        assertThat(response.body()).contains("\"totalElements\":3");
        assertThat(response.body()).contains("\"totalPages\":1");
        assertThat(response.body()).containsSubsequence(
                "\"name\":\"Third\"",
                "\"name\":\"Second\"",
                "\"name\":\"First\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postAnonymous(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String token(long uid, String... roles) {
        var claims = JwtClaimsSet.builder()
                .subject("test-" + uid)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(30)))
                .claim("uid", uid)
                .claim("roles", List.of(roles))
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private long extractId(String location) {
        var matcher = LOCATION_ID.matcher(location);
        if (!matcher.find()) {
            throw new AssertionError("Location header does not contain a campaign id: " + location);
        }
        return Long.parseLong(matcher.group(1));
    }

    private String validBody() {
        return """
                {"name":"PulseInk 秋招发布","objective":"向 Java 后端开发者介绍 PulseInk",
                 "audience":"关注 Agent 工程化的 Java 开发者","channels":["BLOG","SOCIAL"],
                 "constraints":["事实性结论必须给出引用","避免夸大效果"]}
                """;
    }

    private String bodyWithName(String name) {
        return """
                {"name":"%s","objective":"o","audience":"a",
                 "channels":["BLOG"],"constraints":[]}
                """.formatted(name);
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }
}
