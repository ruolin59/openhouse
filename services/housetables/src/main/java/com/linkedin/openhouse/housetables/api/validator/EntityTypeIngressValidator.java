package com.linkedin.openhouse.housetables.api.validator;

import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.housetables.api.spec.model.UserTable;
import com.linkedin.openhouse.housetables.model.EntityType;
import org.springframework.stereotype.Component;

/**
 * Resolves the entity type of a PUT payload at ingress, ahead of every other validation. A payload
 * may agree with its route or stay silent, never override it.
 *
 * <p>The field is required by the contract and every first-party writer sends it. Stamping an
 * absent value is kept as a defensive fallback rather than a compatibility crutch: rejecting null
 * here would turn a stale or third-party caller into an outage instead of a silently corrected
 * write, and the route already knows the answer with certainty.
 */
@Component
public class EntityTypeIngressValidator {

  public static final String EMPTY_ENTITY_MESSAGE = "entity cannot be empty";

  public static final String TYPE_MISMATCH_MESSAGE_FORMAT =
      "entityType provided: %s, but this endpoint serves %s only";

  /**
   * @param userTable the payload entity, which may be absent
   * @return the payload stamped with the route's canonical type
   * @throws RequestValidationFailureException the payload is absent, or names a type its route does
   *     not serve
   */
  public UserTable normalize(UserTable userTable, EntityType routeEntityType) {
    if (userTable == null) {
      throw new RequestValidationFailureException(EMPTY_ENTITY_MESSAGE);
    }
    String declaredEntityType = userTable.getEntityType();
    if (declaredEntityType != null
        && !declaredEntityType.equalsIgnoreCase(routeEntityType.name())) {
      throw new RequestValidationFailureException(
          String.format(TYPE_MISMATCH_MESSAGE_FORMAT, declaredEntityType, routeEntityType.name()));
    }
    return userTable.toBuilder().entityType(routeEntityType.name()).build();
  }
}
