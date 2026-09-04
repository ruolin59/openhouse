package com.linkedin.openhouse.internal.catalog.view;

import java.util.List;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.view.ViewMetadata;

/**
 * Delegating codec that appends each parse and write to the shared event log, so the ordering
 * obligation "write the immutable file, then publish" can be asserted as a sequence.
 *
 * <p>It delegates to a Mockito spy, so interaction verification on the underlying codec keeps
 * working alongside the log.
 */
public class RecordingViewMetadataCodec implements ViewMetadataCodec {

  public static final String READ = "codec.read";
  public static final String WRITE = "codec.write";

  private final ViewMetadataCodec delegate;

  private final List<String> events;

  public RecordingViewMetadataCodec(ViewMetadataCodec delegate, List<String> events) {
    this.delegate = delegate;
    this.events = events;
  }

  @Override
  public ViewMetadata read(InputFile inputFile) {
    events.add(READ + "(" + (inputFile == null ? "null" : inputFile.location()) + ")");
    return delegate.read(inputFile);
  }

  @Override
  public void write(ViewMetadata metadata, OutputFile outputFile) {
    events.add(WRITE + "(" + (outputFile == null ? "null" : outputFile.location()) + ")");
    delegate.write(metadata, outputFile);
  }
}
