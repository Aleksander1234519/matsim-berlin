package org.matsim.analysis.beelines;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.IdentityTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CoordToLinkTable implements MATSimAppCommand {

	@CommandLine.Option(names = "--inputNetwork", defaultValue = "./beeline-data/.mapped/berlin-v6.0-mapped.output_network.xml.gz")
	Path networkIn;

	@CommandLine.Option(names = "--inputSchedule", defaultValue = "./beeline-data/.mapped/berlin-v6.0-mapped.output_transitSchedule.xml.gz")
	Path scheduleIn;

	@CommandLine.Option(names = "--inputCSV", defaultValue = "./beeline-data/csv/Stammdaten_Verkehrsdetektion_2022_07_20.csv")
	Path csvIn;

	@CommandLine.Option(names = "--outputAnalysis", description = "Folder in which to put the analysis-outputs", defaultValue = "./beeline-data/referenceTables")
	Path analysisOut;


	public static void main(String[] args) {
		new CoordToLinkTable().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Network network = NetworkUtils.readNetwork(networkIn.toString());

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(scenario).readFile(scheduleIn.toString());

		TransportModeNetworkFilter filter = new TransportModeNetworkFilter(network);
		Network carOnlyNetwork = NetworkUtils.createNetwork();
		filter.filter(carOnlyNetwork, Set.of("car"));

		Map<Id<Link>, Integer> linkId2busUsages = getBusUsageMap(scenario.getTransitSchedule());

		Reader in = new FileReader(csvIn.toString());
		CSVFormat csvFormat = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();

		CoordinateTransformation transformation = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, "EPSG:25832");

		Map<String, Tuple<Id<Link>, Id<Link>>> streetName2linkIds = new HashMap<>(); //Tuple for normal and inverse (if exists)
		for(CSVRecord record : csvFormat.parse(in)){
			String name = record.get("STRASSE");

			Coord wgsCoord = new Coord(Double.parseDouble(record.get("LÄNGE (WGS84)")), Double.parseDouble(record.get("BREITE (WGS84)")));
			Coord epsgCoord = transformation.transform(wgsCoord);

			Link nearestlink = NetworkUtils.getNearestLinkExactly(carOnlyNetwork, epsgCoord);
			Link oppositeLink = NetworkUtils.findLinkInOppositeDirection(nearestlink);
			if(oppositeLink != null){
				streetName2linkIds.put(name, new Tuple<>(nearestlink.getId(), oppositeLink.getId()));
			} else {
				streetName2linkIds.put(name, new Tuple<>(nearestlink.getId(), null));
			}
		}

		// File: Street Name, LinkId1, LinkId2, busLineUsages
		String file = analysisOut.resolve("streetReferenceTable.csv").toString();
		BufferedWriter bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "streetName", "linkId1", "linkId2", "busLineUsages_link1", "busLineUsages_link2"));
		bw.newLine();

		for (var e : streetName2linkIds.entrySet()) {
			bw.write(
				e.getKey() + ";" +
					e.getValue().getFirst() + ";" +
					e.getValue().getSecond() + ";" +
					linkId2busUsages.get(e.getValue().getFirst()) + ";" +
					linkId2busUsages.get(e.getValue().getSecond()) + ";");
			bw.newLine();
		}
		bw.close();

		return 0;
	}

	private Map<Id<Link>, Integer> getBusUsageMap(TransitSchedule transitSchedule){
		Map<Id<Link>, Integer> linkId2busUsages = new HashMap<>();

		for(var line : transitSchedule.getTransitLines().values()){
			Set<Id<Link>> checkedLinks = new HashSet<>();

			for(var route : line.getRoutes().values()){
				for(var linkId : route.getRoute().getLinkIds()){
					if(!checkedLinks.contains(linkId)){
						linkId2busUsages.putIfAbsent(linkId, 0);
						linkId2busUsages.put(linkId, linkId2busUsages.get(linkId)+1);
						checkedLinks.add(linkId);
					}
				}
			}
		}
		return linkId2busUsages;
	}
}
