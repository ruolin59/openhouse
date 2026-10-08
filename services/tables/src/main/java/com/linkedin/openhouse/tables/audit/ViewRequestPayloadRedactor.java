package com.linkedin.openhouse.tables.audit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.linkedin.openhouse.common.audit.ServiceAuditPayloadRedactor;
import javax.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

/**
 * Keeps view definitions and the write CAS token out of service audit events.
 *
 * <p>{@link com.linkedin.openhouse.common.audit.ServiceAuditAspect} audits the complete cached
 * request body of every controller call, which for the view create and replace routes would retain
 * the caller's full SQL text, schema document and {@code baseMetadataLocation} CAS token. This
 * replaces {@code schema}, every {@code representations[*].sql} value, and {@code
 * baseMetadataLocation} with {@link #REDACTED_VALUE} before the event is built. The keys are kept,
 * so an auditor still sees that the fields were sent.
 *
 * <p>Scoped by route rather than by field name on purpose. {@code CreateUpdateTableRequestBody}
 * also carries a {@code schema}, and redacting by name alone would silently change table, database
 * and snapshot audit payloads. Matching the view routes leaves every other route's payload exactly
 * as it was. The scope follows the route Spring MVC resolved for the request (so a trailing slash
 * or matrix-variable alias is covered), or the decoded application lookup path when no route was
 * resolved. A non-null, non-object root payload on a view route is replaced in full because its
 * fields cannot be safely classified; JSON null and an absent body are left unchanged.
 *
 * <p>Every field that is not part of the view definition or CAS token — {@code viewId}, {@code
 * databaseId}, {@code sourceDialect}, {@code defaultCatalog}, {@code defaultNamespace}, and {@code
 * viewProperties} — is left intact, so an audit event still identifies what was operated on and by
 * whom.
 */
@Component
public class ViewRequestPayloadRedactor implements ServiceAuditPayloadRedactor {

  static final String SCHEMA_FIELD = "schema";
  static final String REPRESENTATIONS_FIELD = "representations";
  static final String SQL_FIELD = "sql";
  static final String BASE_METADATA_LOCATION_FIELD = "baseMetadataLocation";

  /** The view collection route, which POST creates against. */
  private static final String VIEW_COLLECTION_PATTERN = "/v1/databases/*/views";

  /** The view item route, which PUT replaces against. */
  private static final String VIEW_ITEM_PATTERN = "/v1/databases/*/views/*";

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  @Override
  public boolean appliesTo(HttpServletRequest request) {
    if (request.getRequestURI() == null) {
      return false;
    }
    Object resolved = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    String path =
        resolved instanceof String
            ? (String) resolved
            : UrlPathHelper.defaultInstance.getLookupPathForRequest(request);
    return matchesWithOptionalSlash(VIEW_COLLECTION_PATTERN, path)
        || matchesWithOptionalSlash(VIEW_ITEM_PATTERN, path);
  }

  private static boolean matchesWithOptionalSlash(String pattern, String path) {
    return PATH_MATCHER.match(pattern, path) || PATH_MATCHER.match(pattern + "/", path);
  }

  @Override
  public JsonElement redact(JsonElement requestPayload) {
    if (requestPayload == null || requestPayload.isJsonNull()) {
      return requestPayload;
    }
    if (!requestPayload.isJsonObject()) {
      // An invalid root-level primitive or array has no known-safe fields to preserve.
      return new JsonPrimitive(REDACTED_VALUE);
    }
    JsonObject redacted = requestPayload.deepCopy().getAsJsonObject();
    if (redacted.has(SCHEMA_FIELD)) {
      redacted.add(SCHEMA_FIELD, new JsonPrimitive(REDACTED_VALUE));
    }
    if (redacted.has(BASE_METADATA_LOCATION_FIELD)) {
      redacted.add(BASE_METADATA_LOCATION_FIELD, new JsonPrimitive(REDACTED_VALUE));
    }
    JsonElement representations = redacted.get(REPRESENTATIONS_FIELD);
    if (representations != null && representations.isJsonArray()) {
      redactRepresentationsArray(representations.getAsJsonArray());
    } else if (representations != null) {
      // A well-formed request never reaches here (representations is always an array), but the
      // aspect audits whatever JSON the caller actually sent, including a malformed body Jackson
      // would reject at validation. A representations value of any other shape — an object with a
      // bare sql field, a string, or anything else — cannot be interpreted element-by-element, so
      // the entire field is replaced rather than left to carry an unredacted sql value in some
      // shape the array branch above does not recognize.
      redacted.add(REPRESENTATIONS_FIELD, new JsonPrimitive(REDACTED_VALUE));
    }
    return redacted;
  }

  /**
   * Redacts every array element in place, preserving the array's length and every element's own
   * position. A well-formed element is a representation object, whose {@code sql} field (if
   * present) is redacted individually, exactly as before. Any other element shape — a bare string,
   * a nested array, a number, or a boolean — cannot be interpreted as a representation, and a
   * malformed body can place raw SQL directly in that position (e.g. {@code ["SELECT ..."]} or a
   * nested {@code [["SELECT ..."]]}); such an element is replaced wholesale rather than recursed
   * into, since nothing at that position is a known-safe field to preserve. A {@code null} element
   * carries nothing to redact and is left unchanged.
   */
  private static void redactRepresentationsArray(JsonArray representations) {
    for (int i = 0; i < representations.size(); i++) {
      JsonElement representation = representations.get(i);
      if (representation.isJsonObject()) {
        JsonObject representationObject = representation.getAsJsonObject();
        if (representationObject.has(SQL_FIELD)) {
          representationObject.add(SQL_FIELD, new JsonPrimitive(REDACTED_VALUE));
        }
      } else if (!representation.isJsonNull()) {
        representations.set(i, new JsonPrimitive(REDACTED_VALUE));
      }
    }
  }
}
