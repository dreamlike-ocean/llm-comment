package io.github.dreamlike.llmcomment.desktop;

import io.github.dreamlike.llmcomment.core.CommentException;

/** 使用实际应用控件，但启动流程只允许 smoke 创建的临时工作区。 */
public final class DesktopCheckApplication extends DesktopApplication {
  @Override
  protected void openInitialWorkspace() {
    DesktopSmoke.start(this, DesktopCheckMain.screenshot);
  }

  @Override
  protected void report(Exception error) {
    if (!(error instanceof CommentException)) {
      DesktopCheckMain.exitCode = 1;
      error.printStackTrace(System.err);
    }
    super.report(error);
  }
}
