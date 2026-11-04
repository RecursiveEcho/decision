package dev.decision;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Config {
  @Value("${app.owner-code}")
  String ownerCode;

  @Value("${app.token-secret}")
  String tokenSecret;

  @Value("${app.model-key}")
  String modelKey;

  @Value("${app.model-base}")
  String modelBase;

  @Value("${app.model-name}")
  String modelName;

  @Value("${app.mock}")
  boolean mock;

  @Value("${app.wiki-base}")
  String wikiBase;

  @Value("${app.wiki-token}")
  String wikiToken;

  @Value("${app.wiki-public-url}")
  String wikiPublicUrl;

  @Value("${app.wiki-locale}")
  String wikiLocale;

  @PostConstruct
  void validate() {
    if (ownerCode.length() < 12 || tokenSecret.length() < 32)
      throw new IllegalStateException(
          "Set APP_OWNER_ACCESS_CODE (12+ chars) and APP_TOKEN_SECRET (32+ chars); no default"
              + " credentials are enabled");
  }

  boolean modelConfigured() {
    return !modelKey.isBlank();
  }

  boolean wikiConfigured() {
    return !wikiBase.isBlank() && !wikiToken.isBlank();
  }

  String mode() {
    return mock ? "mock" : "real";
  }
}
