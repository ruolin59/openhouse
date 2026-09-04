package com.linkedin.openhouse.internal.catalog.view;

import lombok.Getter;

/**
 * A view create collided with a House Table row that is not a view.
 *
 * <p>Carries the occupant's entity type so the later service layer can distinguish "a table already
 * owns this name" from a fail-closed unknown or legacy occupant, without importing that layer's
 * error-code types here.
 */
@Getter
public class ViewNameOccupiedException extends RuntimeException {

  /**
   * Entity type of the occupying row. A row that carried no discriminator is reported as {@code
   * TABLE}, which is what a legacy null means, so this is never null.
   */
  private final String occupantEntityType;

  private final String databaseId;

  private final String viewId;

  public ViewNameOccupiedException(String databaseId, String viewId, String occupantEntityType) {
    super(
        String.format(
            "Name %s.%s is already occupied by an entity of type %s",
            databaseId, viewId, occupantEntityType));
    this.databaseId = databaseId;
    this.viewId = viewId;
    this.occupantEntityType = occupantEntityType;
  }
}
