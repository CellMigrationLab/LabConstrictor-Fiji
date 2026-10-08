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
 * <p>The behaviour lives in {@code LabConstrictor_Tools.groovy} (bundled in this jar): it reads the registry of installed
 * LabConstrictor apps, builds the tool dialog from each tool's schema and runs the tool in the app's own Python. This
 * class only starts it, so a failure to start is reported in a dialog and in the log instead of vanishing in the console.
 */
@Plugin(type = Command.class, menuPath = "Plugins>LabConstrictor>LabConstrictor Tools...", headless = false)
public class LabConstrictorCommand implements Command {

	static final String SCRIPT = "/org/cellmigrationlab/labconstrictor/LabConstrictor_Tools.groovy";
	private static final Object HANDOFF = new Object();
	private static final String OPTIONS_PROPERTY = "lc.macro.options";
	private static final long HANDOFF_TIMEOUT_MS = 30000;   // how long the script gets to read the macro options
	private static final long HANDOFF_POLL_MS = 20;          // how often the property is looked at while waiting

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
			// the script runs on another thread, so they travel through a property that the script clears as soon as it has
			// read it. The hand-off is serialised: a second call cannot overwrite the options of the first before the first
			// script has consumed them (the property is JVM-wide, the wait makes it behave like a private channel).
			final String macroOptions = macroOptions();
			final ScriptInfo script = new ScriptInfo(context, "LabConstrictor_Tools.groovy", new StringReader(readScript()));
			final java.util.concurrent.Future<?> running;
			synchronized (HANDOFF) {
				if (macroOptions != null && !macroOptions.trim().isEmpty()) System.setProperty(OPTIONS_PROPERTY, macroOptions);
				else System.clearProperty(OPTIONS_PROPERTY);
				running = scriptService.run(script, true);
				final long deadline = System.currentTimeMillis() + HANDOFF_TIMEOUT_MS;
				while (System.getProperty(OPTIONS_PROPERTY) != null && System.currentTimeMillis() < deadline) Thread.sleep(HANDOFF_POLL_MS);
				if (System.getProperty(OPTIONS_PROPERTY) != null) {
					System.clearProperty(OPTIONS_PROPERTY);
					log.warn("LabConstrictor: the script did not read the macro options within " + HANDOFF_TIMEOUT_MS + " ms");
				}
			}
			running.get();
		}
		catch (final Throwable problem) {   // the menu entry: broad on purpose, anything that stops the start must reach the log and a dialog, never vanish
			log.error("LabConstrictor could not start", problem);
			final StringWriter trace = new StringWriter();
			problem.printStackTrace(new PrintWriter(trace));
			if (ui != null) ui.showDialog("LabConstrictor could not start:\n" + problem + "\n\n" + trace, "LabConstrictor");
		}
	}

	/** The options handed to the script: those of a macro call; a subclass can fix its own. */
	protected String macroOptions() {
		return ij.Macro.getOptions();
	}

	/** Version of this plugin (from the jar manifest), for bug reports. */
	public static String version() {
		final Package p = LabConstrictorCommand.class.getPackage();
		return p != null && p.getImplementationVersion() != null ? p.getImplementationVersion() : "development";
	}

	/** The bundled script as text (UTF-8, newline-normalised). */
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
