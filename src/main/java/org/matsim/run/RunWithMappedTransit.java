package org.matsim.run;


import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;

/**
 * Run the {@link OpenBerlinScenario} with default configuration.
 */
public final class RunWithMappedTransit extends MATSimApplication {

	@Override
	protected Config prepareConfig(Config config) {
		config.qsim().setPcuThresholdForFlowCapacityEasing(0.3); // TODO Test this
		return config;
	}

	public static void main(String[] args) {
		// TODO Set iterations to 200
		// TODO Set population to 10pct
		// TODO Set pt pce to 10pct
		Config c = new Config();
		String[] mappedTransitArgs = new String[]{"run", "--config=./input/v6.0-10pct-mapped/berlin-v6.0-mapped.config.xml"};
		MATSimApplication.run(OpenBerlinScenario.class, mappedTransitArgs);
	}
}
