package org.matsim.prepare.beelines;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class ConvertNetworkModes implements MATSimAppCommand {
	@CommandLine.Option(names = "--input", defaultValue = "./input/v6.0-10pct-mapped/mapped-network-v6.0.2.xml.gz")
	Path networkIn;

	@CommandLine.Option(names = "--output", defaultValue = "./input/v6.0-10pct-mapped/mapped-with-pt-network.xml.gz")
	Path networkOut;

	public static void main(String[] args) {
		new ConvertNetworkModes().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Network network = NetworkUtils.readNetwork(networkIn.toString());

		for(Link l : network.getLinks().values()){
			if(	l.getAllowedModes().contains("bus") ||
				l.getAllowedModes().contains("rail") ||
				l.getAllowedModes().contains("ferry") ||
				l.getAllowedModes().contains("tram")){
					Set<String> m = new HashSet<>(l.getAllowedModes());
					m.add("pt");
					m.remove("bus");
					m.remove("rail");
					m.remove("ferry");
					m.remove("tram");
					l.setAllowedModes(m);
			}
		}

		NetworkUtils.writeNetwork(network, networkOut.toString());

		return 0;
	}
}
