package com.linkedin.openhouse.tables.mock.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.tables.audit.ViewRequestUriRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Servlet binding percent-decodes query parameter names, so every spelling that binds to {@code
 * pageToken} must be redacted from the audited URI, not only the literal one.
 */
public class ViewRequestUriRedactorTest {

  private static final String PATH = "/v1/databases/db/views";
  private static final String SECRET_A = "TOKEN_SECRET_A";
  private static final String SECRET_B = "TOKEN_SECRET_B";

  private final ViewRequestUriRedactor redactor = new ViewRequestUriRedactor();

  @Test
  public void literalPageTokenIsRedactedAndSafeFieldsAreKept() {
    String redacted = redactor.redact(PATH + "?pageToken=" + SECRET_A + "&sortBy=viewId&size=1");

    assertFalse(redacted.contains(SECRET_A), redacted);
    assertTrue(redacted.startsWith(PATH + "?"), redacted);
    assertTrue(redacted.contains("sortBy=viewId"), redacted);
    assertTrue(redacted.contains("size=1"), redacted);
  }

  /** Each name below decodes to {@code pageToken}. */
  @ParameterizedTest
  @ValueSource(
      strings = {"%70ageToken", "page%54oken", "%70%61%67%65%54%6f%6b%65%6e", "%70ageT%6Fken"})
  public void percentEncodedPageTokenNamesAreRedacted(String encodedName) {
    String redacted =
        redactor.redact(PATH + "?" + encodedName + "=" + SECRET_A + "&sortBy=viewId&size=1");

    assertFalse(redacted.contains(SECRET_A), redacted);
    assertTrue(redacted.contains("sortBy=viewId"), redacted);
    assertTrue(redacted.contains("size=1"), redacted);
  }

  @Test
  public void mixedLiteralAndEncodedDuplicatesAreAllRedacted() {
    String redacted =
        redactor.redact(
            PATH + "?pageToken=" + SECRET_A + "&size=2&%70ageToken=" + SECRET_B + "&sortBy=viewId");

    assertFalse(redacted.contains(SECRET_A), redacted);
    assertFalse(redacted.contains(SECRET_B), redacted);
    assertTrue(redacted.contains("size=2"), redacted);
    assertTrue(redacted.contains("sortBy=viewId"), redacted);
  }

  @Test
  public void percentEncodedTokenValueIsRedacted() {
    String redacted = redactor.redact(PATH + "?%70ageToken=" + "TOKEN%5FSECRET%5FA" + "&size=1");

    assertFalse(redacted.contains("TOKEN%5FSECRET%5FA"), redacted);
    assertFalse(redacted.contains(SECRET_A), redacted);
  }

  /** A query that cannot be fully decoded must not let a token through. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "?%70ageToken=" + SECRET_A + "&size=%ZZ",
        "?size=1&%70ageToken=" + SECRET_A + "&sortBy=%E0%A4%A",
        "?%70ageToken=" + SECRET_A + "&%"
      })
  public void malformedQueryFailsClosedForPageToken(String query) {
    String redacted = redactor.redact(PATH + query);

    assertFalse(redacted.contains(SECRET_A), redacted);
  }

  // --- Scope: follows the route MVC resolved, else the normalized application lookup path ---

  private static final String COLLECTION_TEMPLATE = "/v1/databases/{databaseId}/views";

  private static MockHttpServletRequest request(String contextPath, String uri, String pattern) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setContextPath(contextPath);
    if (pattern != null) {
      request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
    }
    return request;
  }

  /** The resolved list template, as written or with MVC's trailing slash, over an alias URI. */
  @ParameterizedTest
  @CsvSource({
    "/v1/databases/db/views/," + COLLECTION_TEMPLATE,
    "/v1/databases/db/views/," + COLLECTION_TEMPLATE + "/",
    "/v1/databases/db/views;variant=x," + COLLECTION_TEMPLATE
  })
  public void appliesToTheResolvedListRouteWhateverAliasWasSent(String uri, String pattern) {
    assertTrue(redactor.appliesTo(request("", uri, pattern)), uri + " -> " + pattern);
  }

  /** A resolved non-list route is authoritative, even over a list-looking raw path. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/v1/databases/{databaseId}/tables",
        COLLECTION_TEMPLATE + "/{viewId}",
        "/v2/databases/{databaseId}/views"
      })
  public void aResolvedNonListRouteIsAuthoritative(String pattern) {
    assertFalse(redactor.appliesTo(request("", PATH, pattern)), pattern);
  }

  /** With no resolved route, the decoded application lookup path decides. */
  @ParameterizedTest
  @CsvSource({
    "'',/v1/databases/db/views/",
    "'',/v1/databases/db/views;variant=x",
    "/ctx,/ctx/v1/databases/db/views",
    "/ctx,/ctx/v1/databases/db/views;variant=x/"
  })
  public void withoutAResolvedRouteTheNormalizedLookupPathDecides(String contextPath, String uri) {
    assertTrue(redactor.appliesTo(request(contextPath, uri, null)), uri);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/v1/databases/db/views/v",
        "/v1/databases/db/views/v/extra",
        "/v2/databases/db/views",
        "/v1/databases/db/tables"
      })
  public void withoutAResolvedRouteOtherPathsAreDeclined(String uri) {
    assertFalse(redactor.appliesTo(request("", uri, null)), uri);
  }

  @Test
  public void aRequestWithoutAUriIsDeclined() {
    assertFalse(redactor.appliesTo(new MockHttpServletRequest()));
  }
}
