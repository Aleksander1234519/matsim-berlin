package org.matsim.analysis.beelines;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class ModalShareAnalysis implements MATSimAppCommand {
	@CommandLine.Option(names = "--inputPlans", defaultValue = "./beeline-data/.mapped/berlin-v6.0-mapped.output_plans.xml.gz")
	Path plansIn;

	@CommandLine.Option(names = "--inputSchedule", defaultValue = "./beeline-data/.mapped/berlin-v6.0-mapped.output_transitSchedule.xml.gz")
	Path scheduleIn;

	@CommandLine.Option(names = "--outputAnalysis", description = "Folder in which to put the analysis-outputs", defaultValue = "./beeline-data/modalShareAnalysis")
	Path analysisOut;

	@CommandLine.Option(names = "--interval", description="Only important for averaged csv-data. Interval-length in which to compute the average values.", defaultValue = "3600")
	double interval;

	@CommandLine.Option(names = "--modesToIgnore", defaultValue = "walk,freight", split = ",")
	List<String> modesToIgnore;

	public static void main(String[] args) {
		new ModalShareAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Population population = PopulationUtils.readPopulation(plansIn.toString());

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(scenario).readFile(scheduleIn.toString());

		// Get the general modal share of all legs
		//TODO pt -> bus, rail, ...
		List<Tuple<String, Double>> legsModeAndTime = new LinkedList<>();
		Set<String> usedModes = new HashSet<>();

		List<Tuple<String, Double>> transportModeAndTime = new LinkedList<>();
		Set<String> usedTransportModes = new HashSet<>();

		for (Person p : population.getPersons().values()){
			for (var el : p.getSelectedPlan().getPlanElements()){
				if (el instanceof Leg){
					if (modesToIgnore.contains(((Leg) el).getMode())) continue;
					if(((Leg) el).getMode().equals("pt")){
						String transportMode = getVehicleTypeFromLeg((Leg) el, scenario);
						transportModeAndTime.add(new Tuple<>(
							transportMode,
							((Leg) el).getDepartureTime().seconds()));
						usedTransportModes.add(transportMode);
					}
					legsModeAndTime.add(new Tuple<>(
						((Leg) el).getMode(),
						((Leg) el).getDepartureTime().seconds()));
					usedModes.add(((Leg) el).getMode());

				}
			}
		}

		// Write modalShare
		String file = analysisOut.resolve("modalShareOverDaytime.csv").toString();
		BufferedWriter bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "intervalStart", "intervalEnd", "totalLegs"));
		for(String mode : usedModes){
			bw.write(";");
			bw.write(mode);
		}
		bw.newLine();

		for (double t = interval; t < 86401; t += interval) {
			Map<String, Integer> mode2amount = new HashMap<>();
			int totalAmount = 0;

			for(String mode : usedModes){
				mode2amount.put(mode, 0);
			}
			for(var modeAndTime : legsModeAndTime){
				if (modeAndTime.getSecond() < t-interval) continue;
				if (modeAndTime.getSecond() >= t) continue;

				mode2amount.put(modeAndTime.getFirst(),
					mode2amount.get(modeAndTime.getFirst())+1);

				totalAmount++;
			}

			bw.write((t - interval) + ";");
			bw.write(t + ";");
			bw.write(totalAmount + ";");
			for(var e : mode2amount.entrySet()){
				double percentage = ((double) e.getValue()) / ((double) totalAmount);
				bw.write(percentage + ";");
			}
			bw.newLine();
		}
		bw.close();

		//Write ptShare
		file = analysisOut.resolve("ptShareOverDaytime.csv").toString();
		bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "intervalStart", "intervalEnd", "ptLegs"));
		for(String mode : usedTransportModes){
			bw.write(";");
			bw.write(mode);
		}
		bw.newLine();

		for (double t = interval; t < 86401; t += interval) {
			Map<String, Integer> mode2amount = new HashMap<>();
			int totalAmount = 0;

			for(String mode : usedTransportModes){
				mode2amount.put(mode, 0);
			}
			for(var modeAndTime : transportModeAndTime){
				if (modeAndTime.getSecond() < t-interval) continue;
				if (modeAndTime.getSecond() >= t) continue;

				mode2amount.put(modeAndTime.getFirst(),
					mode2amount.get(modeAndTime.getFirst())+1);

				totalAmount++;
			}

			bw.write((t - interval) + ";");
			bw.write(t + ";");
			bw.write(totalAmount + ";");
			int finalTotalAmount = totalAmount;
			bw.write(mode2amount.values().stream()
				.map(thisAmount -> Double.toString(((double) thisAmount) / ((double) finalTotalAmount)))
				.collect(Collectors.joining(";"))
			);
			bw.newLine();
		}
		bw.close();

		return 0;
	}

	/**
	 * Returns the vehicle-type, that has been used in the given Leg.
	 * @return name of the vehicle-type
	 */
	private String getVehicleTypeFromLeg(Leg leg, Scenario scenario){
		if (!(leg.getRoute() instanceof DefaultTransitPassengerRoute)) return null;

		return scenario.getTransitSchedule()
			.getTransitLines().get(((DefaultTransitPassengerRoute) leg.getRoute()).getLineId())
			.getRoutes().get(((DefaultTransitPassengerRoute) leg.getRoute()).getRouteId())
			.getTransportMode();
	}
}
