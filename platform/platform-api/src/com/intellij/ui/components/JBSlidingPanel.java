// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.components;

import com.intellij.openapi.util.ActionCallback;
import com.intellij.ui.JBCardLayout;

import java.awt.*;

/**
 * @author Konstantin Bulenkov
 */
public final class JBSlidingPanel extends JBPanel<JBSlidingPanel> {

  public JBSlidingPanel() {
    setLayout(new JBCardLayout());
  }

  @Override
  public JBCardLayout getLayout() {
    return (JBCardLayout)super.getLayout();
  }

  public ActionCallback swipe(String id, JBCardLayout.SwipeDirection direction) {
    final ActionCallback done = new ActionCallback();
    getLayout().swipe(this, id, direction, () -> done.setDone());
    return done;
  }

  /**
   * @deprecated MUST use {@link #add(String, Component)}
   */
  @Override
  @Deprecated
  public Component add(Component comp) {
    throw new AddMethodIsNotSupportedException();
  }

  /**
   * @deprecated MUST use {@link #add(String, Component)}
   */
  @Override
  @Deprecated
  public Component add(Component comp, int index) {
    throw new AddMethodIsNotSupportedException();
  }

  /**
   * @deprecated MUST use {@link #add(String, Component)}
   */
  @Override
  @Deprecated
  public void add(Component comp, Object constraints) {
    throw new AddMethodIsNotSupportedException();
  }

  /**
   * @deprecated MUST use {@link #add(String, Component)}
   */
  @Override
  @Deprecated
  public void add(Component comp, Object constraints, int index) {
    throw new AddMethodIsNotSupportedException();
  }

  private static final class AddMethodIsNotSupportedException extends RuntimeException {
    AddMethodIsNotSupportedException() {
      super("Use add(String, Component) method");
    }
  }
}
