// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexDebugEvent;
import com.android.tools.r8.graph.DexDebugEvent.SetFile;
import com.android.tools.r8.graph.DexDebugInfo;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.shaking.ProguardConfiguration;
import java.util.Arrays;

/**
 * Visit program {@link DexClass}es and replace their sourceFile with the given string.
 *
 * If -keepattribute SourceFile is not set, we rather remove that attribute.
 */
public class SourceFileRewriter {

  private final AppView<?> appView;

  public SourceFileRewriter(AppView<?> appView) {
    this.appView = appView;
  }

  public void run() {
    ProguardConfiguration proguardConfiguration = appView.options().getProguardConfiguration();
    String renameSourceFile = proguardConfiguration.getRenameSourceFileAttribute();
    // Return early if a user wants to keep the current source file attribute as-is.
    if (renameSourceFile == null && proguardConfiguration.getKeepAttributes().sourceFile) {
      return;
    }
    // Now, the user wants either to remove source file attribute or to rename it.
    DexString dexRenameSourceFile =
        renameSourceFile == null
            ? appView.dexItemFactory().createString("")
            : appView.dexItemFactory().createString(renameSourceFile);
    for (DexClass clazz : appView.appInfo().classes()) {
      clazz.sourceFile = dexRenameSourceFile;
      clazz.forEachMethod(encodedMethod -> {
        // Abstract methods do not have code_item.
        if (encodedMethod.shouldNotHaveCode()) {
          return;
        }
        Code code = encodedMethod.getCode();
        if (code == null) {
          return;
        }
        if (code.isDexCode()) {
          DexDebugInfo dexDebugInfo = code.asDexCode().getDebugInfo();
          if (dexDebugInfo == null) {
            return;
          }
          // Thanks to a single global source file, we can safely remove DBG_SET_FILE entirely.
          dexDebugInfo.events =
              Arrays.stream(dexDebugInfo.events)
                  .filter(dexDebugEvent -> !(dexDebugEvent instanceof SetFile))
                  .toArray(DexDebugEvent[]::new);
        } else {
          assert code.isCfCode();
          // CF has nothing equivalent to SetFile, so there is nothing to remove.
        }
      });
    }
  }
}
