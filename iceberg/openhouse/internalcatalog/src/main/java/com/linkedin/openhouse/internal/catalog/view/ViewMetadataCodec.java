package com.linkedin.openhouse.internal.catalog.view;

import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.view.ViewMetadata;

/**
 * Injectable facade over the static {@code ViewMetadataParser} entry points, so tests can spy on
 * parser use and assert that a failed pointer probe never touches a metadata file.
 *
 * <p>This seam names Iceberg 1.5 view types on purpose: it is registered only from {@link
 * OpenHouseInternalViewConfiguration}, never as an unconditional component, so it never appears on
 * a shared bean signature under Iceberg 1.2.
 */
public interface ViewMetadataCodec {

  ViewMetadata read(InputFile inputFile);

  void write(ViewMetadata metadata, OutputFile outputFile);
}
