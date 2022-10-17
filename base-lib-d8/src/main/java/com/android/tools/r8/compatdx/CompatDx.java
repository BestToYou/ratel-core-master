// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.compatdx;

import com.android.tools.r8.ByteDataView;
import com.android.tools.r8.CompatDxHelper;
import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.ProgramConsumer;
import com.android.tools.r8.Version;
import com.android.tools.r8.compatdx.CompatDx.DxCompatOptions.DxUsageMessage;
import com.android.tools.r8.compatdx.CompatDx.DxCompatOptions.PositionInfo;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.origin.PathOrigin;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.ExceptionDiagnostic;
import com.android.tools.r8.utils.FileUtils;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.ZipUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

/**
 * Dx compatibility interface for d8.
 *
 * This should become a mostly drop-in replacement for uses of the DX dexer (eg, dx --dex ...).
 */
public class CompatDx {

  private static final String USAGE_HEADER = "Usage: compatdx [options] <input files>";

  /**
   * Compatibility options parsing for the DX --dex sub-command.
   */
  public static class DxCompatOptions {
    // Final values after parsing.
    // Note: These are ordered by their occurrence in "dx --help"
    public final boolean help;
    public final boolean version;
    public final boolean debug;
    public final boolean verbose;
    public final PositionInfo positions;
    public final boolean noLocals;
    public final boolean noOptimize;
    public final boolean statistics;
    public final String optimizeList;
    public final String noOptimizeList;
    public final boolean noStrict;
    public final boolean keepClasses;
    public final String output;
    public final String dumpTo;
    public final int dumpWidth;
    public final String dumpMethod;
    public final boolean verboseDump;
    public final boolean dump;
    public final boolean noFiles;
    public final boolean coreLibrary;
    public final int numThreads;
    public final boolean incremental;
    public final boolean forceJumbo;
    public final boolean noWarning;
    public final boolean multiDex;
    public final String mainDexList;
    public final boolean minimalMainDex;
    public final int minApiLevel;
    public final String inputList;
    public final ImmutableList<String> inputs;
    // Undocumented option
    public final int maxIndexNumber;

    private static final String FILE_ARG = "file";
    private static final String NUM_ARG = "number";
    private static final String METHOD_ARG = "method";

    public enum PositionInfo {
      NONE, IMPORTANT, LINES, THROWING
    }

    // Exception thrown on invalid dx compat usage.
    public static class DxUsageMessage extends Exception {
      public final String message;

      DxUsageMessage(String message) {
        this.message = message;
      }

      void printHelpOn(PrintStream sink) throws IOException {
        sink.println(message);
      }
    }

    // Parsing specification.
    private static class Spec {
      final OptionParser parser;

      // Note: These are ordered by their occurrence in "dx --help"
      final OptionSpec<Void> debug;
      final OptionSpec<Void> verbose;
      final OptionSpec<String> positions;
      final OptionSpec<Void> noLocals;
      final OptionSpec<Void> noOptimize;
      final OptionSpec<Void> statistics;
      final OptionSpec<String> optimizeList;
      final OptionSpec<String> noOptimizeList;
      final OptionSpec<Void> noStrict;
      final OptionSpec<Void> keepClasses;
      final OptionSpec<String> output;
      final OptionSpec<String> dumpTo;
      final OptionSpec<Integer> dumpWidth;
      final OptionSpec<String> dumpMethod;
      final OptionSpec<Void> dump;
      final OptionSpec<Void> verboseDump;
      final OptionSpec<Void> noFiles;
      final OptionSpec<Void> coreLibrary;
      final OptionSpec<Integer> numThreads;
      final OptionSpec<Void> incremental;
      final OptionSpec<Void> forceJumbo;
      final OptionSpec<Void> noWarning;
      final OptionSpec<Void> multiDex;
      final OptionSpec<String> mainDexList;
      final OptionSpec<Void> minimalMainDex;
      final OptionSpec<Integer> minApiLevel;
      final OptionSpec<String> inputList;
      final OptionSpec<String> inputs;
      final OptionSpec<Void> version;
      final OptionSpec<Void> help;
      final OptionSpec<Integer> maxIndexNumber;

      Spec() {
        parser = new OptionParser();
        parser.accepts("dex");
        debug = parser.accepts("debug", "Print debug information");
        verbose = parser.accepts("verbose", "Print verbose information");
        positions = parser
            .accepts("positions",
                "What source-position information to keep. One of: none, lines, important")
            .withOptionalArg()
            .describedAs("keep")
            .defaultsTo("lines");
        noLocals = parser.accepts("no-locals", "Don't keep local variable information");
        statistics = parser.accepts("statistics", "Print statistics information");
        noOptimize = parser.accepts("no-optimize", "Don't optimize");
        optimizeList = parser
            .accepts("optimize-list", "File listing methods to optimize")
            .withRequiredArg()
            .describedAs(FILE_ARG);
        noOptimizeList = parser
            .accepts("no-optimize-list", "File listing methods not to optimize")
            .withRequiredArg()
            .describedAs(FILE_ARG);
        noStrict = parser.accepts("no-strict", "Disable strict file/class name checks");
        keepClasses = parser.accepts("keep-classes", "Keep input class files in in output jar");
        output = parser
            .accepts("output", "Output file or directory")
            .withRequiredArg()
            .describedAs(FILE_ARG);
        dumpTo = parser
            .accepts("dump-to", "File to dump information to")
            .withRequiredArg()
            .describedAs(FILE_ARG);
        dumpWidth = parser
            .accepts("dump-width", "Max width for columns in dump output")
            .withRequiredArg()
            .ofType(Integer.class)
            .defaultsTo(0)
            .describedAs(NUM_ARG);
        dumpMethod = parser
            .accepts("dump-method", "Method to dump information for")
            .withRequiredArg()
            .describedAs(METHOD_ARG);
        dump = parser.accepts("dump", "Dump information");
        verboseDump = parser.accepts("verbose-dump", "Dump verbose information");
        noFiles = parser.accepts("no-files", "Don't fail if given no files");
        coreLibrary = parser.accepts("core-library", "Construct a core library");
        numThreads = parser
            .accepts("num-threads", "Number of threads to run with")
            .withRequiredArg()
            .ofType(Integer.class)
            .defaultsTo(1)
            .describedAs(NUM_ARG);
        incremental = parser.accepts("incremental", "Merge result with the output if it exists");
        forceJumbo = parser.accepts("force-jumbo", "Force use of string-jumbo instructions");
        noWarning = parser.accepts("no-warning", "Suppress warnings");
        maxIndexNumber = parser.accepts("set-max-idx-number",
            "Undocumented: Set maximal index number to use in a dex file.")
            .withRequiredArg()
            .ofType(Integer.class)
            .defaultsTo(0)
            .describedAs("Maximum index");
        minimalMainDex = parser.accepts("minimal-main-dex", "Produce smallest possible main dex");
        mainDexList = parser
            .accepts("main-dex-list", "File listing classes that must be in the main dex file")
            .withRequiredArg()
            .describedAs(FILE_ARG);
        multiDex =
            parser
                .accepts("multi-dex", "Allow generation of multi-dex")
                .requiredIf(minimalMainDex, mainDexList, maxIndexNumber);
        minApiLevel = parser
            .accepts("min-sdk-version", "Minimum Android API level compatibility.")
            .withRequiredArg().ofType(Integer.class);
        inputList = parser
            .accepts("input-list", "File listing input files")
            .withRequiredArg()
            .describedAs(FILE_ARG);
        inputs = parser.nonOptions("Input files");
        version = parser.accepts("version", "Print the version of this tool").forHelp();
        help = parser.accepts("help", "Print this message").forHelp();
      }
    }

    private DxCompatOptions(OptionSet options, Spec spec) {
      help = options.has(spec.help);
      version = options.has(spec.version);
      debug = options.has(spec.debug);
      verbose = options.has(spec.verbose);
      if (options.has(spec.positions)) {
        switch (options.valueOf(spec.positions)) {
          case "none":
            positions = PositionInfo.NONE;
            break;
          case "important":
            positions = PositionInfo.IMPORTANT;
            break;
          case "lines":
            positions = PositionInfo.LINES;
            break;
          case "throwing":
            positions = PositionInfo.THROWING;
            break;
          default:
            positions = PositionInfo.IMPORTANT;
            break;
        }
      } else {
        positions = PositionInfo.LINES;
      }
      noLocals = options.has(spec.noLocals);
      noOptimize = options.has(spec.noOptimize);
      statistics = options.has(spec.statistics);
      optimizeList = options.valueOf(spec.optimizeList);
      noOptimizeList = options.valueOf(spec.noOptimizeList);
      noStrict = options.has(spec.noStrict);
      keepClasses = options.has(spec.keepClasses);
      output = options.valueOf(spec.output);
      dumpTo = options.valueOf(spec.dumpTo);
      dumpWidth = options.valueOf(spec.dumpWidth);
      dumpMethod = options.valueOf(spec.dumpMethod);
      dump = options.has(spec.dump);
      verboseDump = options.has(spec.verboseDump);
      noFiles = options.has(spec.noFiles);
      coreLibrary = options.has(spec.coreLibrary);
      numThreads = lastIntOf(options.valuesOf(spec.numThreads));
      incremental = options.has(spec.incremental);
      forceJumbo = options.has(spec.forceJumbo);
      noWarning = options.has(spec.noWarning);
      multiDex = options.has(spec.multiDex);
      mainDexList = options.valueOf(spec.mainDexList);
      minimalMainDex = options.has(spec.minimalMainDex);
      if (options.has(spec.minApiLevel)) {
        List<Integer> allMinApiLevels = options.valuesOf(spec.minApiLevel);
        minApiLevel = allMinApiLevels.get(allMinApiLevels.size() - 1);
      } else {
        minApiLevel = AndroidApiLevel.getDefault().getLevel();
      }
      inputList = options.valueOf(spec.inputList);
      inputs = ImmutableList.copyOf(options.valuesOf(spec.inputs));
      maxIndexNumber = options.valueOf(spec.maxIndexNumber);
    }

    public static DxCompatOptions parse(String[] args) {
      Spec spec = new Spec();
      return new DxCompatOptions(spec.parser.parse(args), spec);
    }

    private static int lastIntOf(List<Integer> values) {
      assert !values.isEmpty();
      return values.get(values.size() - 1);
    }
  }

  public static void main(String[] args) throws IOException {
    try {
      run(args);
    } catch (DxUsageMessage e) {
      System.err.println(USAGE_HEADER);
      e.printHelpOn(System.err);
      System.exit(1);
    } catch (CompilationFailedException e) {
      System.exit(1);
    }
  }

  private static void run(String[] args)
      throws DxUsageMessage, IOException, CompilationFailedException {
    DxCompatOptions dexArgs = DxCompatOptions.parse(args);
    if (dexArgs.help) {
      printHelpOn(System.out);
      return;
    }
    if (dexArgs.version) {
      System.out.println("CompatDx " + Version.getVersionString());
      return;
    }
    CompilationMode mode = CompilationMode.RELEASE;
    Path output = null;
    List<Path> inputs = new ArrayList<>();
    boolean singleDexFile = !dexArgs.multiDex;
    Path mainDexList = null;
    int numberOfThreads = 1;

    for (String path : dexArgs.inputs) {
      processPath(new File(path), inputs);
    }
    if (inputs.isEmpty()) {
      if (dexArgs.noFiles) {
        return;
      }
      throw new DxUsageMessage("No input files specified");
    }

    if (!Log.ENABLED && dexArgs.debug) {
      System.out.println("Warning: logging is not enabled for this build.");
    }

    if (dexArgs.dump && dexArgs.verbose) {
      System.out.println("Warning: dump is not supported");
    }

    if (dexArgs.verboseDump) {
      throw new Unimplemented("verbose dump file not yet supported");
    }

    if (dexArgs.dumpMethod != null) {
      throw new Unimplemented("method-dump not yet supported");
    }

    if (dexArgs.output != null) {
      output = Paths.get(dexArgs.output);
      if (FileUtils.isDexFile(output)) {
        if (!singleDexFile) {
          throw new DxUsageMessage("Cannot output to a single dex-file when running with multidex");
        }
      } else if (!FileUtils.isArchive(output)
          && (!output.toFile().exists() || !output.toFile().isDirectory())) {
        throw new DxUsageMessage("Unsupported output file or output directory does not exist. "
            + "Output must be a directory or a file of type dex, apk, jar or zip.");
      }
    }

    if (dexArgs.dumpTo != null && dexArgs.verbose) {
      System.out.println("dump-to file not yet supported");
    }

    if (dexArgs.positions == PositionInfo.NONE && dexArgs.verbose) {
      System.out.println("Warning: no support for positions none.");
    }

    if (dexArgs.positions == PositionInfo.LINES && !dexArgs.noLocals) {
      mode = CompilationMode.DEBUG;
    }

    if (dexArgs.incremental) {
      throw new Unimplemented("incremental merge not supported yet");
    }

    if (dexArgs.forceJumbo && dexArgs.verbose) {
      System.out.println(
          "Warning: no support for forcing jumbo-strings.\n"
              + "Strings will only use jumbo-string indexing if necessary.\n"
              + "Make sure that any dex merger subsequently used "
              + "supports correct handling of jumbo-strings (eg, D8/R8 does).");
    }

    if (dexArgs.noOptimize && dexArgs.verbose) {
      System.out.println("Warning: no support for not optimizing");
    }

    if (dexArgs.optimizeList != null) {
      throw new Unimplemented("no support for optimize-method list");
    }

    if (dexArgs.noOptimizeList != null) {
      throw new Unimplemented("no support for dont-optimize-method list");
    }

    if (dexArgs.statistics && dexArgs.verbose) {
      System.out.println("Warning: no support for printing statistics");
    }

    if (dexArgs.numThreads > 1) {
      numberOfThreads = dexArgs.numThreads;
    }

    if (dexArgs.mainDexList != null) {
      mainDexList = Paths.get(dexArgs.mainDexList);
    }

    if (dexArgs.noStrict) {
      if (dexArgs.verbose) {
        System.out.println("Warning: conservative main-dex list not yet supported");
      }
    } else {
      if (dexArgs.verbose) {
        System.out.println("Warning: strict name checking not yet supported");
      }
    }

    if (dexArgs.minimalMainDex && dexArgs.verbose) {
      System.out.println("Warning: minimal main-dex support is not yet supported");
    }

    if (dexArgs.maxIndexNumber != 0 && dexArgs.verbose) {
      System.out.println("Warning: internal maximum-index setting is not supported");
    }

    if (numberOfThreads < 1) {
      throw new DxUsageMessage("Invalid numThreads value of " + numberOfThreads);
    }
    ExecutorService executor = ThreadUtils.getExecutorService(numberOfThreads);

    try {
      D8Command.Builder builder = D8Command.builder();
      CompatDxHelper.ignoreDexInArchive(builder);
      builder
          .addProgramFiles(inputs)
          .setProgramConsumer(
              createConsumer(inputs, output, singleDexFile, dexArgs.keepClasses))
          .setMode(mode)
          .setMinApiLevel(dexArgs.minApiLevel);
      if (mainDexList != null) {
        builder.addMainDexListFiles(mainDexList);
      }
      CompatDxHelper.run(builder.build(), dexArgs.minimalMainDex);
    } finally {
      executor.shutdown();
    }
  }

  private static ProgramConsumer createConsumer(
      List<Path> inputs, Path output, boolean singleDexFile, boolean keepClasses)
      throws DxUsageMessage {
    if (output == null) {
      return DexIndexedConsumer.emptyConsumer();
    }
    if (singleDexFile) {
      return new SingleDexFileConsumer(
          FileUtils.isDexFile(output)
              ? new NamedDexFileConsumer(output)
              : createDexConsumer(output, inputs, keepClasses));
    }
    return createDexConsumer(output, inputs, keepClasses);
  }

  private static DexIndexedConsumer createDexConsumer(
      Path output, List<Path> inputs, boolean keepClasses)
      throws DxUsageMessage {
    if (keepClasses) {
      if (!FileUtils.isArchive(output)) {
        throw new DxCompatOptions.DxUsageMessage(
            "Output must be an archive when --keep-classes is set.");
      }
      return new DexKeepClassesConsumer(output, inputs);
    }
    return FileUtils.isArchive(output)
        ? new DexIndexedConsumer.ArchiveConsumer(output)
        : new DexIndexedConsumer.DirectoryConsumer(output);
  }

  private static class SingleDexFileConsumer extends DexIndexedConsumer.ForwardingConsumer {

    private byte[] bytes = null;

    public SingleDexFileConsumer(DexIndexedConsumer consumer) {
      super(consumer);
    }

    @Override
    public void accept(
        int fileIndex, ByteDataView data, Set<String> descriptors, DiagnosticsHandler handler) {
      if (fileIndex > 0) {
        throw new CompilationError(
            "Compilation result could not fit into a single dex file. "
                + "Reduce the input-program size or run with --multi-dex enabled");
      }
      assert bytes == null;
      // Store a copy of the bytes as we may not assume the backing is valid after accept returns.
      bytes = data.copyByteData();
    }

    @Override
    public void finished(DiagnosticsHandler handler) {
      if (bytes != null) {
        super.accept(0, ByteDataView.of(bytes), null, handler);
      }
      super.finished(handler);
    }
  }

  private static class NamedDexFileConsumer extends DexIndexedConsumer.ForwardingConsumer {
    private final Path output;

    public NamedDexFileConsumer(Path output) {
      super(null);
      this.output = output;
    }

    @Override
    public void accept(
        int fileIndex, ByteDataView data, Set<String> descriptors, DiagnosticsHandler handler) {
      StandardOpenOption[] options = {
        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
      };
      try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output, options))) {
        stream.write(data.getBuffer(), data.getOffset(), data.getLength());
      } catch (IOException e) {
        handler.error(new ExceptionDiagnostic(e, new PathOrigin(output)));
      }
    }
  }

  private static class DexKeepClassesConsumer extends DexIndexedConsumer.ArchiveConsumer {

    private final List<Path> inputs;

    public DexKeepClassesConsumer(Path archive, List<Path> inputs) {
      super(archive);
      this.inputs = inputs;
    }

    @Override
    public void finished(DiagnosticsHandler handler) {
      try {
        writeZipWithClasses(handler);
      } catch (IOException e) {
        handler.error(new ExceptionDiagnostic(e, getOrigin()));
      }
      super.finished(handler);
    }

    private void writeZipWithClasses(DiagnosticsHandler handler) throws IOException {
      // For each input archive file, add all class files within.
      for (Path input : inputs) {
        if (FileUtils.isArchive(input)) {
          try (ZipFile zipFile = FileUtils.createZipFile(input.toFile(), StandardCharsets.UTF_8)) {
            final Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
              ZipEntry entry = entries.nextElement();
              if (ZipUtils.isClassFile(entry.getName())) {
                try (InputStream entryStream = zipFile.getInputStream(entry)) {
                  byte[] bytes = ByteStreams.toByteArray(entryStream);
                  outputBuilder.addFile(entry.getName(), ByteDataView.of(bytes), handler);
                }
              }
            }
          }
        }
      }
    }
  }

  static void printHelpOn(PrintStream sink) throws IOException {
    sink.println(USAGE_HEADER);
    new DxCompatOptions.Spec().parser.printHelpOn(sink);
  }

  private static void processPath(File file, List<Path> files) {
    if (!file.exists()) {
      throw new CompilationError("File does not exist: " + file);
    }
    if (file.isDirectory()) {
      processDirectory(file, files);
      return;
    }
    Path path = file.toPath();
    if (FileUtils.isZipFile(path) || FileUtils.isJarFile(path) || FileUtils.isClassFile(path)) {
      files.add(path);
      return;
    }
    if (FileUtils.isApkFile(path)) {
      throw new Unimplemented("apk files not yet supported");
    }
  }

  private static void processDirectory(File directory, List<Path> files) {
    assert directory.exists();
    for (File file : directory.listFiles()) {
      processPath(file, files);
    }
  }
}
