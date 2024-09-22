package org.matsim.analysis.beelines;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Checks how many buses were late and how large the delay was.
 */
public class BusDelayAnalysis implements MATSimAppCommand {
	@CommandLine.Option(names = "--inputSchedule", defaultValue = "./beeline-data/")
	Path scheduleIn;

	@CommandLine.Option(names = "--inputEvents", defaultValue = "./beeline-data/events/berlin-v6.0-beelines.output_events.xml.gz")
	Path eventsIn;

	@CommandLine.Option(names = "--outputAnalysis", description = "Folder in which to put the analysis-ouputs", defaultValue = "./beeline-data/busDelayAnalysis")
	Path analysisOut;

	@CommandLine.Option(names = "--interval", description="Only important for averaged csv-data. Interval-length in which to compute the average values.", defaultValue = "900")
	double interval;

	@CommandLine.Option(names = "--punctualityMargin", description="Amount of time (in seconds) after which a delay is counted as a late arrival.", defaultValue = "300")
	double punctualityMargin;

	/**
	 * Handler for the events file.
	 */
	private static class AnalysisEventHandler implements VehicleDepartsAtFacilityEventHandler {
		/**
		 * List containing unmapped delay of all departures in the simulation-run in seconds. If a departure had no delay: delay=0
		 */
		private final List<DepartureEntry> allDelays = new LinkedList<>();

		/**
		 * Saves the delays mapped for every {@link TransitStopFacility}. If a departure had no delay: delay=0
		 */
		private final Map<Id<TransitStopFacility>, List<DepartureEntry>> facilityId2Delays = new HashMap<>();

		/**
		 * Saves the delays mapped for every {@link Vehicle}. If a departure had no delay: delay=0
		 */
		private final Map<Id<Vehicle>, List<DepartureEntry>> vehicleId2Delays = new HashMap<>();

		/**
		 * List of all used facilityIds
		 */
		private final List<Id<TransitStopFacility>> allFacilityIds = new LinkedList<>();

		/**
		 * List of all used vehicleIds
		 */
		private final List<Id<Vehicle>> allVehicleIds = new LinkedList<>();

		@Override
		public void handleEvent(VehicleDepartsAtFacilityEvent event) {
			DepartureEntry entry = new DepartureEntry(event.getFacilityId(), event.getVehicleId(), event.getTime(), event.getDelay());
			allDelays.add(entry);

			facilityId2Delays.putIfAbsent(event.getFacilityId(), new LinkedList<>());
			facilityId2Delays.get(event.getFacilityId()).add(entry);
			allFacilityIds.add(event.getFacilityId());

			vehicleId2Delays.putIfAbsent(event.getVehicleId(), new LinkedList<>());
			vehicleId2Delays.get(event.getVehicleId()).add(entry);
			allVehicleIds.add(event.getVehicleId());
		}
	}

	/**
	 * Structure class for readability.
	 */
	private static class DepartureEntry {
		Id<TransitStopFacility> facilityId;
		Id<Vehicle> vehicleId;
		Double time;
		Double delay;

		public DepartureEntry(Id<TransitStopFacility> facilityId, Id<Vehicle> vehicleId, Double time, Double delay) {
			this.facilityId = facilityId;
			this.vehicleId = vehicleId;
			this.time = time;
			this.delay = delay;
		}
	}

	public static void main(String[] args) {
		new BusDelayAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		// Read the data in
		//Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		//new TransitScheduleReader(scenario).readFile(scheduleIn.toString());

		EventsManager manager = EventsUtils.createEventsManager();
		AnalysisEventHandler analysisHandler = new AnalysisEventHandler();
		manager.addHandler(analysisHandler);
		EventsUtils.readEvents(manager, eventsIn.toString());

		// Save as csv-output

		// File 1: Save all the entries directly
		String file = analysisOut.resolve("allDelays.csv").toString();
		BufferedWriter bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "facilityId", "vehicleId", "time", "delay"));
		bw.newLine();
		for (DepartureEntry entry : analysisHandler.allDelays){
			bw.write(entry.facilityId.toString());
			bw.write(";" + entry.vehicleId);
			bw.write(";" + entry.time);
			bw.write(";" + entry.delay);
			bw.newLine();
		}

		// File 2: .csv with averaged data for all (timesliced)
		file = analysisOut.resolve("avgDelays.csv").toString();
		bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "intervalStart", "intervalEnd", "avgDelay"));
		bw.newLine();
		for (double t = interval; t < 86400; t += interval){
			int entries = 0;
			double sum = 0;

			// This second loop is not efficient, but it is still fast enough
			for (DepartureEntry entry : analysisHandler.allDelays){
				if (entry.time < t-interval) continue;
				if (entry.time >= t) break;
				entries++;
				sum += entry.delay;
			}

			System.out.println(sum + "/" + entries + "=" + (sum/entries));
			bw.write(String.valueOf(t-interval));
			bw.write(";" + t);
			bw.write(";" + sum/entries);
			bw.newLine();
		}
		bw.close();

		/*
		// File 3: .csv with averaged data aggregated by facilities (timesliced)
		file = analysisOut.resolve("facilityDelays.csv").toString();
		bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "facilityId", "intervalStart", "intervalEnd", "avgDelay"));
		bw.newLine();
		for (double t = interval; t < 86400; t += interval){
			for (Id<TransitStopFacility> id : analysisHandler.allFacilityIds){
				int entries = 0;
				double sum = 0;

				for (DepartureEntry entry : analysisHandler.facilityId2Delays.get(id)){
					if (entry.time < t-interval) continue;
					if (entry.time >= t) break;
					entries++;
					sum += entry.delay;
				}

				bw.write(id.toString());
				bw.write(";" + (t-interval));
				bw.write(";" + t);
				bw.write(";" + sum/entries);
				bw.newLine();
			}
		}
		bw.close();

		// File 4: .csv with averaged data aggregated by vehicle (timesliced)
		file = analysisOut.resolve("vehicleDelays.csv").toString();
		bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "vehicleId", "intervalStart", "intervalEnd", "avgDelay"));
		bw.newLine();
		for (double t = interval; t < 86400; t += interval){
			for (Id<Vehicle> id : analysisHandler.allVehicleIds){
				int entries = 0;
				double sum = 0;

				for (DepartureEntry entry : analysisHandler.vehicleId2Delays.get(id)){
					if (entry.time < t-interval) continue;
					if (entry.time >= t) break;
					entries++;
					sum += entry.delay;
				}

				bw.write(id.toString());
				bw.write(";" + (t-interval));
				bw.write(";" + t);
				bw.write(";" + sum/entries);
				bw.newLine();
			}
		}
		bw.close();*/

		//TODO General data (total punctuality, total avg, percentiles, ...)
		//TODO add punctuality to all .csv

		return 0;
	}
}
