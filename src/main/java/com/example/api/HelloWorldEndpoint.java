package com.example.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.client.ComponentClient;
import com.example.application.HelloWorldAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * This is a simple Akka Endpoint that uses an agent and LLM to generate
 * greetings in different languages.
 */
// Opened up for access from the public internet to make the service easy to try out.
// For actual services meant for production this must be carefully considered,
// and often set more limited
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint
public class HelloWorldEndpoint {

  public record Request(String user, String text) {}
  public record ModelInfo(
    String name,
    String displayName,
    String description,
    List<String> supportedGenerationMethods,
    Integer inputTokenLimit,
    Integer outputTokenLimit
  ) {}
  public record QuotaInfo(boolean available, String source, String message, List<JsonNode> metrics) {}
  public record ModelsResponse(List<ModelInfo> models, QuotaInfo quota) {}

  private static final String GOOGLE_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models";
  private static final String GOOGLE_QUOTAS_URL = "https://serviceusage.googleapis.com/v1beta1/projects/";
  private static final String GOOGLE_CLOUD_SCOPE = "https://www.googleapis.com/auth/cloud-platform.read-only";
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final ComponentClient componentClient;

  public HelloWorldEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/hello")
  public String hello(Request request) {
    return componentClient
      .forAgent()
      .inSession(request.user)
      .method(HelloWorldAgent::greet)
      .invoke(request.text);
  }

  @Get("/health")
  public String health() {
    return "OK";
  }

  @Get("/models")
  public CompletableFuture<ModelsResponse> models() {
    String apiKey = System.getenv("GEMINI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      return CompletableFuture.failedFuture(
        new IllegalStateException("GEMINI_API_KEY is not configured")
      );
    }

    String projectId = System.getenv("GOOGLE_CLOUD_PROJECT");
    return fetchModels(apiKey, null, new ArrayList<>())
      .thenCombine(fetchQuota(projectId), ModelsResponse::new);
  }

  private CompletableFuture<QuotaInfo> fetchQuota(String projectId) {
    if (projectId == null || projectId.isBlank()) {
      return CompletableFuture.completedFuture(new QuotaInfo(
        false,
        "Google Cloud Service Usage API",
        "Set GOOGLE_CLOUD_PROJECT to enable quota lookup.",
        List.of()
      ));
    }

    return CompletableFuture.supplyAsync(() -> {
      try {
        return GoogleCredentials.getApplicationDefault()
          .createScoped(List.of(GOOGLE_CLOUD_SCOPE))
          .refreshAccessToken()
          .getTokenValue();
      } catch (IOException exception) {
        throw new CompletionException(exception);
      }
    })
      .thenCompose(accessToken -> fetchQuotaMetrics(accessToken, projectId, null, new ArrayList<>()))
      .thenApply(metrics -> new QuotaInfo(
        true,
        "Google Cloud Service Usage API",
        "These are configured quota limits, not current usage or AI Studio model-specific rate limits.",
        List.copyOf(metrics)
      ))
      .exceptionally(exception -> new QuotaInfo(
        false,
        "Google Cloud Service Usage API",
        "Quota data is unavailable. Check ADC credentials and the serviceusage.quotas.get permission.",
        List.of()
      ));
  }

  private CompletableFuture<List<JsonNode>> fetchQuotaMetrics(
    String accessToken,
    String projectId,
    String pageToken,
    List<JsonNode> metrics
  ) {
    String url = GOOGLE_QUOTAS_URL
      + URLEncoder.encode(projectId, StandardCharsets.UTF_8)
      + "/services/generativelanguage.googleapis.com/consumerQuotaMetrics?view=FULL&pageSize=200";
    if (pageToken != null && !pageToken.isBlank()) {
      url += "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
    }

    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
      .header("Authorization", "Bearer " + accessToken)
      .GET()
      .build();

    return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
      .thenCompose(response -> {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          return CompletableFuture.failedFuture(
            new IllegalStateException("Google quota request failed with HTTP " + response.statusCode())
          );
        }

        try {
          JsonNode body = OBJECT_MAPPER.readTree(response.body());
          for (JsonNode metric : body.path("metrics")) {
            if (textValue(metric, "metric") != null
              && textValue(metric, "metric").startsWith("generativelanguage.googleapis.com/")) {
              metrics.add(metric.deepCopy());
            }
          }

          String nextPageToken = textValue(body, "nextPageToken");
          if (nextPageToken != null && !nextPageToken.isBlank()) {
            return fetchQuotaMetrics(accessToken, projectId, nextPageToken, metrics);
          }
          return CompletableFuture.completedFuture(List.copyOf(metrics));
        } catch (IOException exception) {
          return CompletableFuture.failedFuture(exception);
        }
      });
  }

  private CompletableFuture<List<ModelInfo>> fetchModels(
    String apiKey,
    String pageToken,
    List<ModelInfo> models
  ) {
    String url = GOOGLE_MODELS_URL;
    if (pageToken != null && !pageToken.isBlank()) {
      url += "?pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
    }

    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
      .header("x-goog-api-key", apiKey)
      .GET()
      .build();

    return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
      .thenCompose(response -> {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          return CompletableFuture.failedFuture(
            new IllegalStateException(
              "Google model list request failed with HTTP " + response.statusCode()
            )
          );
        }

        try {
          JsonNode body = OBJECT_MAPPER.readTree(response.body());
          for (JsonNode model : body.path("models")) {
            models.add(new ModelInfo(
              textValue(model, "name"),
              textValue(model, "displayName"),
              textValue(model, "description"),
              stringValues(model.path("supportedGenerationMethods")),
              integerValue(model, "inputTokenLimit"),
              integerValue(model, "outputTokenLimit")
            ));
          }

          String nextPageToken = textValue(body, "nextPageToken");
          if (nextPageToken != null && !nextPageToken.isBlank()) {
            return fetchModels(apiKey, nextPageToken, models);
          }
          return CompletableFuture.completedFuture(List.copyOf(models));
        } catch (IOException exception) {
          return CompletableFuture.failedFuture(exception);
        }
      });
  }

  private static String textValue(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() ? value.textValue() : null;
  }

  private static Integer integerValue(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isIntegralNumber() ? value.intValue() : null;
  }

  private static List<String> stringValues(JsonNode node) {
    List<String> values = new ArrayList<>();
    if (node.isArray()) {
      for (JsonNode value : node) {
        if (value.isTextual()) {
          values.add(value.textValue());
        }
      }
    }
    return List.copyOf(values);
  }
}
