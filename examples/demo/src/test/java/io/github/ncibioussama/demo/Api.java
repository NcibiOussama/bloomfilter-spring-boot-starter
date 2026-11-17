package io.github.ncibioussama.demo;

import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Small HTTP client for the demo's API, returning error statuses instead of throwing. */
final class Api {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;

    Api(int port) {
        this.http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                })
                .build();
    }

    int status(String path) {
        return http.get().uri(path).retrieve().toBodilessEntity().getStatusCode().value();
    }

    /** Status and raw body; {@code path} may contain characters that must be encoded. */
    ResponseEntity<String> raw(String path, Map<String, ?> query) {
        return http.get().uri(builder -> {
                    builder.path(path);
                    query.forEach((name, value) -> builder.queryParam(name, "{" + name + "}"));
                    return builder.build(query);
                })
                .retrieve().toEntity(String.class);
    }

    Map<String, Object> get(String path) {
        ResponseEntity<Map<String, Object>> response = http.get().uri(path).retrieve().toEntity(JSON);
        if (response.getStatusCode().isError()) {
            throw new AssertionError("GET " + path + " returned " + response.getStatusCode());
        }
        return response.getBody();
    }

    long createUser(String email, String name) {
        ResponseEntity<Map<String, Object>> response = http.post().uri("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "name", name))
                .retrieve().toEntity(JSON);
        if (response.getStatusCode().value() != 201) {
            throw new AssertionError("POST /users returned " + response.getStatusCode());
        }
        return ((Number) response.getBody().get("id")).longValue();
    }

    int delete(String path) {
        return http.delete().uri(path).retrieve().toBodilessEntity().getStatusCode().value();
    }

    Map<String, Object> post(String path) {
        return http.post().uri(path).retrieve().toEntity(JSON).getBody();
    }

    long databaseLookups() {
        return ((Number) get("/users/database-lookups").get("databaseLookups")).longValue();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> filter(String name) {
        return (Map<String, Object>) ((Map<String, Object>) get("/actuator/bloomfilters").get("filters")).get(name);
    }

    @SuppressWarnings("unchecked")
    long rebuildSuccesses(String name) {
        return ((Number) ((Map<String, Object>) filter(name).get("rebuild")).get("successes")).longValue();
    }
}
