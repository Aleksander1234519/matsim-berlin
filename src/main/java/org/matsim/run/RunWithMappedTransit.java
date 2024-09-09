package org.matsim.run;


import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;

/**
 * Run the {@link OpenBerlinScenario} with default configuration.
 */
public final class RunWithMappedTransit {

	private RunWithMappedTransit() {
	}

	public static void main(String[] args) {
		// TODO Set iterations to 500
		// TODO Set population to 10pct
		Config c = new Config();
		String[] mappedTransitArgs = new String[]{"run", "--config=./input/v6.0-10pct-mapped/berlin-v6.0-mapped.config.xml"};
		MATSimApplication.run(OpenBerlinScenario.class, mappedTransitArgs);
	}

}
