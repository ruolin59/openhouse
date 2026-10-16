package com.linkedin.openhouse.tables.mock.audit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.tables.audit.DummyViewAuditHandler;
import com.linkedin.openhouse.tables.audit.ViewOperationAuditEmitter;
import com.linkedin.openhouse.tables.audit.model.ViewAuditEvent;
import com.linkedin.openhouse.tables.services.PreparedViewOperation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Real Spring constructor injection of the operation-audit sink. The default handler is always
 * registered, so a deployment's conventionally named {@code viewAuditHandler} bean coexists with it
 * (neither is primary) and must be the one the emitter receives; without it, the default is used.
 */
public class ViewOperationAuditHandlerWiringTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(DummyViewAuditHandler.class, ViewOperationAuditEmitter.class);

  @Test
  public void customViewAuditHandlerCoexistingWithTheDefaultReceivesOperationEvents() {
    contextRunner
        .withUserConfiguration(CustomViewAuditHandlerConfig.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              @SuppressWarnings("unchecked")
              AuditHandler<ViewAuditEvent> custom =
                  (AuditHandler<ViewAuditEvent>) context.getBean("viewAuditHandler");
              ViewOperationAuditEmitter emitter = context.getBean(ViewOperationAuditEmitter.class);
              assertSame(custom, ReflectionTestUtils.getField(emitter, "auditHandler"));

              emitter.emitFailed(PreparedViewOperation.observedAbsence(), "alice", null);

              verify(custom, times(1)).audit(any(ViewAuditEvent.class));
            });
  }

  @Test
  public void defaultViewAuditHandlerIsUsedWhenNoCustomHandlerIsDefined() {
    contextRunner.run(
        context -> {
          assertNull(context.getStartupFailure());
          assertSame(
              context.getBean(DummyViewAuditHandler.class),
              ReflectionTestUtils.getField(
                  context.getBean(ViewOperationAuditEmitter.class), "auditHandler"));
        });
  }

  @Configuration
  static class CustomViewAuditHandlerConfig {
    @Bean
    @SuppressWarnings("unchecked")
    AuditHandler<ViewAuditEvent> viewAuditHandler() {
      return mock(AuditHandler.class);
    }
  }
}
