package org.matsim.analysis.beelines;

import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.collections.Tuple;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.*;

public class ModalShareAnalysis implements MATSimAppCommand {
	@CommandLine.Option(names = "--inputPlans", defaultValue = "./beeline-data/berlin-v6.0-mapped.output_plans.xml.gz")
	Path plansIn;

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

		// Get the general modal share of all legs
		//TODO pt -> bus, rail, ...
		List<Tuple<String, Double>> legsModeAndTime = new LinkedList<>();
		Set<String> usedModes = new HashSet<>();

		for (Person p : population.getPersons().values()){
			for (var el : p.getSelectedPlan().getPlanElements()){
				if (el instanceof Leg){
					if (modesToIgnore.contains(((Leg) el).getMode())) continue;
					legsModeAndTime.add(new Tuple<>(((Leg) el).getMode(), ((Leg) el).getDepartureTime().seconds()));
					usedModes.add(((Leg) el).getMode());
				}
			}
		}


		String file = analysisOut.resolve("modalShareOverDaytime.csv").toString();
		BufferedWriter bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "intervalStart", "intervalEnd", "totalLegs"));
		for(String mode : usedModes){
			bw.write(";");
			bw.write(mode);
		}
		bw.newLine();

		//
		for (double t = interval; t < 86400; t += interval) {
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

		return 0;
	}
}
