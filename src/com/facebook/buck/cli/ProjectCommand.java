/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.cli.output.Mode;
import com.facebook.buck.cli.parameter_extractors.ProjectGeneratorParameters;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.support.cli.args.PluginBasedCommand;
import com.facebook.buck.support.cli.args.PluginBasedSubCommand;
import com.facebook.buck.support.cli.args.PluginBasedSubCommands;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

public class ProjectCommand extends BuildCommand implements PluginBasedCommand {

  public enum Ide {
    INTELLIJ,
    XCODE;

    public String getName() {
      return Ascii.toLowerCase(name());
    }

    public static Ide fromString(String string) {
      switch (Ascii.toLowerCase(string)) {
        case "intellij":
          return Ide.INTELLIJ;
        case "xcode":
          return Ide.XCODE;
        default:
          throw new HumanReadableException("Invalid ide value %s.", string);
      }
    }
  }

  @Option(name = "--process-annotations", usage = "Enable annotation processing")
  private boolean processAnnotations;

  @Option(
      name = "--without-tests",
      usage = "When generating a project slice, exclude tests that test the code in that slice")
  private boolean withoutTests = false;

  @Option(
      name = "--with-tests",
      usage = "When generating a project slice, generate with all the tests")
  private boolean withTests = false;

  @Option(
      name = "--without-dependencies-tests",
      usage =
          "When generating a project slice, includes tests that test code in main target, "
              + "but exclude tests that test dependencies")
  private boolean withoutDependenciesTests = false;

  @Option(
      name = "--ide",
      usage =
          "The type of IDE for which to generate a project. You may specify it in the "
              + ".buckconfig file. Please refer to https://buckbuild.com/concept/buckconfig.html#project")
  @Nullable
  private Ide ide = null;

  @Option(
      name = "--dry-run",
      usage =
          "Instead of actually generating the project, only print out the targets that "
              + "would be included.")
  private boolean dryRun = false;

  @Option(
      name = "--update",
      usage =
          "Instead of generating a whole project, only regenerate the module files for the "
              + "given targets, possibly updating the top-level modules list.")
  private boolean updateOnly = false;

  @PluginBasedSubCommands(commandClass = ProjectSubCommand.class)
  @SuppressFieldNotInitialized
  private ImmutableList<ProjectSubCommand> ides;

  private Optional<String> getPathToPreProcessScript(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "pre_process");
  }

  private Optional<String> getPathToPostProcessScript(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "post_process");
  }

  private Optional<Ide> getIdeFromBuckConfig(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "ide").map(Ide::fromString);
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    Ide projectIde =
        (ide == null) ? getIdeFromBuckConfig(params.getBuckConfig()).orElse(null) : ide;

    if (projectIde == null) {
      throw new CommandLineException("project IDE is not specified in Buck config or --ide");
    }

    int rc = runPreprocessScriptIfNeeded(params, projectIde);
    if (rc != 0) {
      return ExitCode.map(rc);
    }

    try (CommandThreadManager pool =
        new CommandThreadManager("Project", getConcurrencyLimit(params.getBuckConfig()))) {

      ImmutableMap<String, ProjectSubCommand> subcommands =
          ides.stream()
              .collect(
                  ImmutableMap.toImmutableMap(
                      ProjectSubCommand::getOptionValue, Function.identity()));

      ProjectSubCommand subCommand = subcommands.get(projectIde.getName());

      ProjectGeneratorParameters projectGeneratorParameters =
          new ProjectGeneratorParametersImplementation(params);

      params.getBuckEventBus().post(ProjectGenerationEvent.started());
      ExitCode result;
      try {
        result = subCommand.run(params, pool, projectGeneratorParameters, getArguments());
        rc = runPostprocessScriptIfNeeded(params, projectIde);
        if (rc != 0) {
          return ExitCode.map(rc);
        }
      } finally {
        params.getBuckEventBus().post(ProjectGenerationEvent.finished());
      }

      return result;
    }
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  private int runPreprocessScriptIfNeeded(CommandRunnerParams params, Ide projectIde)
      throws IOException, InterruptedException {
    Optional<String> script = getPathToPreProcessScript(params.getBuckConfig());
    return runScriptIfNeeded(script, params, projectIde);
  }

  private int runPostprocessScriptIfNeeded(CommandRunnerParams params, Ide projectIde)
      throws IOException, InterruptedException {
    Optional<String> script = getPathToPostProcessScript(params.getBuckConfig());
    return runScriptIfNeeded(script, params, projectIde);
  }

  private int runScriptIfNeeded(
      Optional<String> optionalPathToScript, CommandRunnerParams params, Ide projectIde)
      throws IOException, InterruptedException {
    if (!optionalPathToScript.isPresent()) {
      return 0;
    }
    String pathToScript = optionalPathToScript.get();
    if (!Paths.get(pathToScript).isAbsolute()) {
      pathToScript =
          params
              .getCell()
              .getFilesystem()
              .getPathForRelativePath(pathToScript)
              .toAbsolutePath()
              .toString();
    }

    ListeningProcessExecutor processExecutor = new ListeningProcessExecutor();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .addCommand(pathToScript)
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(params.getEnvironment())
                    .put("BUCK_PROJECT_TARGETS", Joiner.on(" ").join(getArguments()))
                    .put("BUCK_PROJECT_TYPE", projectIde.toString().toLowerCase())
                    .build())
            .setDirectory(params.getCell().getFilesystem().getRootPath())
            .build();
    ForwardingProcessListener processListener =
        new ForwardingProcessListener(
            // Using rawStream to avoid shutting down SuperConsole. This is safe to do
            // because this process finishes before we start parsing process.
            params.getConsole().getStdOut().getRawStream(),
            params.getConsole().getStdErr().getRawStream());
    ListeningProcessExecutor.LaunchedProcess process =
        processExecutor.launchProcess(processExecutorParams, processListener);
    try {
      return processExecutor.waitForProcess(process);
    } finally {
      processExecutor.destroyProcess(process, /* force */ false);
      processExecutor.waitForProcess(process);
    }
  }

  @Override
  public ImmutableList<? extends PluginBasedSubCommand> getSubCommands() {
    return ides;
  }

  @Override
  public String getTypeOptionName() {
    return "--ide";
  }

  @Override
  public void printUsage(PrintStream stream) {
    PluginBasedCommand.super.printUsage(stream);
  }

  @Override
  public String getShortDescription() {
    return "generates project configuration files for an IDE";
  }

  private class ProjectGeneratorParametersImplementation
      extends CommandRunnerParametersImplementation implements ProjectGeneratorParameters {

    private ProjectGeneratorParametersImplementation(CommandRunnerParams parameters) {
      super(parameters);
    }

    @Override
    public boolean isDryRun() {
      return dryRun;
    }

    @Override
    public boolean isWithTests() {
      return withTests;
    }

    @Override
    public boolean isWithoutTests() {
      return withoutTests;
    }

    @Override
    public boolean isWithoutDependenciesTests() {
      return withoutDependenciesTests;
    }

    @Override
    public boolean isProcessAnnotations() {
      return processAnnotations;
    }

    @Override
    public boolean isUpdateOnly() {
      return updateOnly;
    }

    @Override
    public Verbosity getVerbosity() {
      return getConsole().getVerbosity();
    }

    @Override
    public boolean getEnableParserProfiling() {
      return ProjectCommand.this.getEnableParserProfiling();
    }

    @Override
    public Function<Iterable<String>, ImmutableList<TargetNodeSpec>> getArgsParser() {
      return arguments -> parseArgumentsAsTargetNodeSpecs(parameters.getBuckConfig(), arguments);
    }

    @Override
    public Mode getOutputMode() {
      return ProjectCommand.this.getOutputMode();
    }
  }
}
