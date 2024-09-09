package org.matsim.prepare.beelines;

import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Set;

public class NetworkCarFilter implements MATSimAppCommand {

	@CommandLine.Option(names = "--input", defaultValue = "./beeline-data/berlin-v5.5-network.xml.gz")
	Path networkIn;

	@CommandLine.Option(names = "--output", defaultValue = "./beeline-data/car-network.xml.gz")
	Path networkOut;

	public static void main(String[] args) {
		new NetworkCarFilter().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Network network = NetworkUtils.readNetwork(networkIn.toString());

		TransportModeNetworkFilter filter = new TransportModeNetworkFilter(network);
		Network carOnlyNetwork = NetworkUtils.createNetwork();
		filter.filter(carOnlyNetwork, Set.of("car"));

		NetworkUtils.writeNetwork(carOnlyNetwork, networkOut.toString());

		return 0;
	}
}
