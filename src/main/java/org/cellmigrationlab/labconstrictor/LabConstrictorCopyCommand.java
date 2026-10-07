package org.cellmigrationlab.labconstrictor;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

/**
 * Menu entry <i>Plugins &gt; LabConstrictor &gt; Copy last run as command</i>: puts the terminal line of the last run on the
 * clipboard (the same script, started with the option {@code copy_last=true}; {@code kind=python} copies the Python snippet).
 */
@Plugin(type = Command.class, menuPath = "Plugins>LabConstrictor>Copy last run as command", headless = false)
public class LabConstrictorCopyCommand extends LabConstrictorCommand {

	@Override
	protected String macroOptions() {
		final String given = ij.Macro.getOptions();   // run("Copy last run as command", "kind=python") picks the Python snippet
		return "copy_last=true" + (given == null || given.trim().isEmpty() ? "" : " " + given);
	}
}
