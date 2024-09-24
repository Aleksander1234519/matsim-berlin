package org.matsim.analysis.beelines;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


public class BusCongestionAnalysis implements MATSimAppCommand {
	@CommandLine.Option(names = "--inputNetworkBeelines", defaultValue = "./beeline-data/.beelines/berlin-v6.0-mapped.output_network.xml.gz")
	Path beelinesNetworkIn;

	@CommandLine.Option(names = "--inputNetworkMapped", defaultValue = "./beeline-data/.mapped/berlin-v6.0-mapped.output_network.xml.gz")
	Path mappedNetworkIn;

	@CommandLine.Option(names = "--inputScheduleBeelines", defaultValue = "./beeline-data/.beelines/berlin-v6.0-mapped.output_transitSchedule.xml.gz")
	Path beelinesScheduleIn;

	@CommandLine.Option(names = "--inputScheduleMapped", defaultValue = "./beeline-data/.mapped/berlin-v6.0-mapped.output_transitSchedule.xml.gz")
	Path mappedScheduleIn;

	@CommandLine.Option(names = "--inputEventsBeelines", defaultValue = "./beeline-data/.beelines/berlin-v6.0-mapped.output_events.xml.gz")
	Path beelinesEventsIn;

	@CommandLine.Option(names = "--inputEventsMapped", defaultValue = "./beeline-data/.mapped/berlin-v6.0-mapped.output_events.xml.gz")
	Path mappedEventsIn;

	@CommandLine.Option(names = "--outputAnalysis", description = "Folder in which to put the analysis-outputs", defaultValue = "./beeline-data/busCongestion")
	Path analysisOut;

	/**
	 * Handler for the events file.
	 */
	private static class AnalysisEventHandler implements LinkEnterEventHandler, LinkLeaveEventHandler {

		TravelTimeCalculator ttc;

		private AnalysisEventHandler(Network network, Path eventsIn){
			TravelTimeCalculator.Builder ttcb = new TravelTimeCalculator.Builder(network);
			ttcb.setCalculateLinkTravelTimes(true);
			ttc = ttcb.build();
			EventsManager manager = EventsUtils.createEventsManager();
			manager.addHandler(this);
			EventsUtils.readEvents(manager, eventsIn.toString());
		}

		private TravelTimeCalculator getTravelTimeCalculator(){
			return ttc;
		}

		@Override
		public void handleEvent(LinkEnterEvent event) {
			ttc.handleEvent(event);
		}

		@Override
		public void handleEvent(LinkLeaveEvent event) {
			ttc.handleEvent(event);
		}
	}


	public static void main(String[] args) {
		new BusCongestionAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Network beelinesNetwork = NetworkUtils.readNetwork(beelinesNetworkIn.toString());
		Network mappedNetwork = NetworkUtils.readNetwork(mappedNetworkIn.toString());

		Scenario beelinesScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(beelinesScenario).readFile(beelinesScheduleIn.toString());

		Scenario mappedScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(mappedScenario).readFile(mappedScheduleIn.toString());

		TravelTimeCalculator beelinesTtc = new AnalysisEventHandler(beelinesNetwork, beelinesEventsIn).getTravelTimeCalculator();
		TravelTimeCalculator mappedTtc = new AnalysisEventHandler(mappedNetwork, mappedEventsIn).getTravelTimeCalculator();

		// Read in the links and routes of the mapped scenario. We do not care about the beelines from the base-case
		List<Id<Link>> linksToConsider = new LinkedList<>();
		for (var line : mappedScenario.getTransitSchedule().getTransitLines().values()){
			for (var route : line.getRoutes().values()){
				for (var linkId: route.getRoute().getLinkIds()){
					// This link must exist in the base-case, otherwise we can not compare the travel-time. It should also allow car-mode, so that we know that it is a bus link
					if(beelinesNetwork.getLinks().containsKey(linkId) && beelinesNetwork.getLinks().get(linkId).getAllowedModes().contains("car")){
						linksToConsider.add(linkId);
					}
				}
			}
		}

		// Compute the deltaTT for every link, that got a bus-route on it
		Map<Id<Link>, Double> linkId2dTT = new HashMap<>();
		for(Id<Link> linkId : linksToConsider){
			double beelinesTT = beelinesTtc.getLinkTravelTimes().getLinkTravelTime(beelinesNetwork.getLinks().get(linkId), 0, null, null);
			double mappedTT = mappedTtc.getLinkTravelTimes().getLinkTravelTime(mappedNetwork.getLinks().get(linkId), 0, null, null);

			linkId2dTT.put(linkId, mappedTT-beelinesTT);
		}

		// Write deltaTT and link-length for links
		String file = analysisOut.resolve("busCongestion.csv").toString();
		BufferedWriter bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "linkId", "length", "deltaTravelTime"));
		bw.newLine();

		for (var e : linkId2dTT.entrySet()){
			bw.write(e.getKey() + ";" + mappedNetwork.getLinks().get(e.getKey()).getLength() + ";" + e.getValue());
			bw.newLine();
		}
		bw.close();

		return 0;
	}
}
