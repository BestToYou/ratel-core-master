// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.position.Position;
import java.util.List;

public class WhyAreYouNotInliningRule extends ProguardConfigurationRule {

  public static class Builder
      extends ProguardConfigurationRule.Builder<WhyAreYouNotInliningRule, Builder> {

    private Builder() {
      super();
    }

    @Override
    public Builder self() {
      return this;
    }

    @Override
    public WhyAreYouNotInliningRule build() {
      return new WhyAreYouNotInliningRule(
          origin,
          getPosition(),
          source,
          classAnnotation,
          classAccessFlags,
          negatedClassAccessFlags,
          classTypeNegated,
          classType,
          classNames,
          inheritanceAnnotation,
          inheritanceClassName,
          inheritanceIsExtends,
          memberRules);
    }
  }

  private WhyAreYouNotInliningRule(
      Origin origin,
      Position position,
      String source,
      ProguardTypeMatcher classAnnotation,
      ProguardAccessFlags classAccessFlags,
      ProguardAccessFlags negatedClassAccessFlags,
      boolean classTypeNegated,
      ProguardClassType classType,
      ProguardClassNameList classNames,
      ProguardTypeMatcher inheritanceAnnotation,
      ProguardTypeMatcher inheritanceClassName,
      boolean inheritanceIsExtends,
      List<ProguardMemberRule> memberRules) {
    super(
        origin,
        position,
        source,
        classAnnotation,
        classAccessFlags,
        negatedClassAccessFlags,
        classTypeNegated,
        classType,
        classNames,
        inheritanceAnnotation,
        inheritanceClassName,
        inheritanceIsExtends,
        memberRules);
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  String typeString() {
    return "whyareyounotinlining";
  }
}
