package org.apache.cloudstack.storage.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import java.util.Map;

public class NetAppUtil {

    private static final Logger s_logger = (Logger) LogManager.getLogger(NetAppUtil.class);
    private final long connTimeout = 5000;
    private final boolean skipTlsValidation = false;
    static final ObjectMapper mapper = new ObjectMapper();
    // Use shared HttpClient instance
    HttpClient client = HttpClient.newHttpClient();

    public CompletableFuture<Boolean> createOntapVolume(String url, String volumeName, Long capacityBytes, Map<String, String> details) {
        HttpRequest request = null;
        try {
            String payload1 = "{  \"aggregates\": [    " +
                    "                {      " +
                    "                   \"name\": \"sti246_vsim_ocvs040a_aggr1\"    " +
                    "                }  " +
                    "             ], " +
                    "             \"name\": \"" + volumeName + "\",  " +
                    "             \"size\": " + capacityBytes + ",  " +
                    "             \"svm\": {    " +
                    "                \"name\": \"vs0\"  " +
                    "             }" +
                    "          }";

            // Prepare payload
            /*Map<String, Object> payload = Map.of(
                    "name", volumeName,
                    "size", capacityBytes,
                    "details", details
            );*/

            ObjectMapper mapper = new ObjectMapper();
            //String jsonPayload = mapper.writeValueAsString(payload);

            // Build HTTP request
            request = HttpRequest.newBuilder()
                    .uri(URI.create("https://10.196.38.171/api/storage/volumes"))
                    .header("Content-Type", "application/json")
                    .header("username", "admin")
                    .header("password", "netapp1!")
                    .POST(HttpRequest.BodyPublishers.ofString(payload1))
                    .build();

            // Send async request
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> response.statusCode() == 200 || response.statusCode() == 201);
        } catch (Exception e) {
            CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;
        }
    }
}

