package com.linkedin.openhouse.internal.catalog.view;

import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewMetadataParser;

/** {@link ViewMetadataCodec} backed by Iceberg's {@code ViewMetadataParser}. */
public class IcebergViewMetadataCodec implements ViewMetadataCodec {

  @Override
  public ViewMetadata read(InputFile inputFile) {
    return ViewMetadataParser.read(inputFile);
  }

  /**
   * Writes to a path that does not exist yet. Every commit picks a fresh file name, so a create
   * (rather than an overwrite) is what we want: it makes an accidental collision fail loudly
   * instead of silently replacing a metadata file another commit may already be pointing at.
   */
  @Override
  public void write(ViewMetadata metadata, OutputFile outputFile) {
    ViewMetadataParser.write(metadata, outputFile);
  }
}
