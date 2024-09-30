package org.matsim.analysis.beelines;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.*;

public class IdReferenceTables implements MATSimAppCommand {
	@CommandLine.Option(names = "--inputSchedule", defaultValue = "./beeline-data/.beelines/berlin-v6.0-mapped.output_transitSchedule.xml.gz")
	Path scheduleIn;

	@CommandLine.Option(names = "--outputAnalysis", description = "Folder in which to put the analysis-outputs", defaultValue = "./beeline-data/referenceTables")
	Path analysisOut;

	public static void main(String[] args) {
		new IdReferenceTables().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(scenario).readFile(scheduleIn.toString());

		Map<Id<Vehicle>, String> vehicleId2lineName = new HashMap<>();
		Map<Id<TransitStopFacility>, Set<String>> facilityId2lineName = new HashMap<>();
		for (var line : scenario.getTransitSchedule().getTransitLines().values() ) {
			String lineName = line.getName();

			for (var route : line.getRoutes().values()) {
				for(var departure : route.getDepartures().values()) {
					vehicleId2lineName.put(departure.getVehicleId(), lineName);
				}
				for(var stop : route.getStops()) {
					facilityId2lineName.putIfAbsent(stop.getStopFacility().getId(), new HashSet<>());
					facilityId2lineName.get(stop.getStopFacility().getId()).add(lineName);
				}
			}
		}

		// File 1: VehicleId, Line Name
		String file = analysisOut.resolve("vehicleReferenceTable.csv").toString();
		BufferedWriter bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "vehicleId", "line"));
		bw.newLine();

		for (var e : vehicleId2lineName.entrySet()) {
			bw.write(
				e.getKey() + ";" +
				e.getValue() + ";");
			bw.newLine();
		}
		bw.close();

		// File 2: FacilityId, Line Name(s)
		file = analysisOut.resolve("facilityReferenceTable.csv").toString();
		bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "facilityId", "facilityName", "line"));
		bw.newLine();

		for (var e : facilityId2lineName.entrySet()) {
			bw.write(
				e.getKey() + ";" +
					scenario.getTransitSchedule().getFacilities().get(e.getKey()).getName() + ";" +
					e.getValue() + ";");
			bw.newLine();
		}
		bw.close();

		return 0;
	}
}
