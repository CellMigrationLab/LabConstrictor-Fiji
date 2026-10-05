package org.cellmigrationlab.labconstrictor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptService;
import org.scijava.ui.UIService;

/**
 * Menu entry <i>Plugins &gt; LabConstrictor &gt; LabConstrictor Tools...</i>.
 *
 * <p>The behaviour lives in {@code LabConstrictor.groovy} (bundled in this jar): it reads the registry of installed
 * LabConstrictor apps, builds the tool dialog from each tool's schema and runs the tool in the app's own Python. This
 * class only starts it, so a failure to start is reported in a dialog and in the log instead of vanishing in the console.
 */
@Plugin(type = Command.class, menuPath = "Plugins>LabConstrictor>LabConstrictor Tools...", headless = false)
public class LabConstrictorCommand implements Command {

	static final String SCRIPT = "/org/cellmigrationlab/labconstrictor/LabConstrictor.groovy";

	@Parameter
	private Context context;

	@Parameter
	private ScriptService scriptService;

	@Parameter
	private LogService log;

	@Parameter(required = false)
	private UIService ui;

	@Override
	public void run() {
		try {
			// A macro call run("LabConstrictor Tools...", "app=... tool=...") keeps its options in a per-thread slot of ImageJ 1;
			// the script runs on another thread, so hand them over explicitly (the script clears the property).
			final String macroOptions = ij.Macro.getOptions();
			if (macroOptions != null && !macroOptions.trim().isEmpty()) System.setProperty("lc.macro.options", macroOptions);
			else System.clearProperty("lc.macro.options");
			final ScriptInfo script = new ScriptInfo(context, "LabConstrictor.groovy", new StringReader(readScript()));
			scriptService.run(script, true).get();
		}
		catch (final Throwable problem) {
			log.error("LabConstrictor could not start", problem);
			final StringWriter trace = new StringWriter();
			problem.printStackTrace(new PrintWriter(trace));
			if (ui != null) ui.showDialog("LabConstrictor could not start:\n" + problem + "\n\n" + trace, "LabConstrictor");
		}
	}

	/** Version of this plugin (from the jar manifest), for bug reports. */
	public static String version() {
		final Package p = LabConstrictorCommand.class.getPackage();
		return p != null && p.getImplementationVersion() != null ? p.getImplementationVersion() : "development";
	}

	private static String readScript() throws IOException {
		try (InputStream in = LabConstrictorCommand.class.getResourceAsStream(SCRIPT)) {
			if (in == null) throw new IOException("bundled script not found: " + SCRIPT);
			final StringBuilder text = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				for (String line; (line = reader.readLine()) != null;) text.append(line).append('\n');
			}
			return text.toString();
		}
	}
}
