/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.python.toolchain.impl;

import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.python.toolchain.PythonInterpreter;
import java.nio.file.Path;
import java.util.Optional;

public class PythonInterpreterFromConfig implements PythonInterpreter {

  private final PythonBuckConfig pythonBuckConfig;

  public PythonInterpreterFromConfig(PythonBuckConfig pythonBuckConfig) {
    this.pythonBuckConfig = pythonBuckConfig;
  }

  @Override
  public Path getPythonInterpreterPath(Optional<String> config) {
    return pythonBuckConfig.getPythonInterpreter(config);
  }

  @Override
  public Path getPythonInterpreterPath(String section) {
    return pythonBuckConfig.getPythonInterpreter(section);
  }

  @Override
  public Path getPythonInterpreterPath() {
    return pythonBuckConfig.getPythonInterpreter();
  }
}
