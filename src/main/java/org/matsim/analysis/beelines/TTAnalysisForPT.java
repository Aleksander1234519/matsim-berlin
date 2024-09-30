package org.matsim.analysis.beelines;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.matsim.application.MATSimAppCommand;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class TTAnalysisForPT implements MATSimAppCommand {

	@CommandLine.Option(names = "--inputLegs", defaultValue = "./beeline-data/.mapped/berlin-v6.0-mapped.output_legs.csv")
	Path csvIn;

	@CommandLine.Option(names = "--outputAnalysis", description = "Folder in which to put the analysis-outputs", defaultValue = "./beeline-data/tts")
	Path analysisOut;

	public static void main(String[] args) {
		new TTAnalysisForPT().execute();
	}

	@Override
	public Integer call() throws Exception {
		Reader in = new FileReader(csvIn.toString());
		CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
			.setHeader()
			.setSkipHeaderRecord(true)
			.setDelimiter(";")
			.build();

		DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
		long reference = dateFormat.parse("00:00:00").getTime();

		Set<String> ptModes = new HashSet<>();
		List<Long> allTT = new ArrayList<>();
		List<Long> allWT = new ArrayList<>();
		Map<String, List<Long>> ptMode2tt = new HashMap<>();
		Map<String, List<Long>> ptMode2wt = new HashMap<>();
		for (CSVRecord record : csvFormat.parse(in)){
			String mode = record.get("mode");
			if(!mode.equals("pt")) continue;
			String[] vehicleId = record.get("vehicle_id").split("_");
			String ptMode = vehicleId[vehicleId.length-1];

			long tt = (dateFormat.parse(record.get("trav_time")).getTime() - reference) / 1000L;
			long wt = (dateFormat.parse(record.get("wait_time")).getTime() - reference) / 1000L;

			ptModes.add(ptMode);
			allTT.add(tt);
			allWT.add(wt);

			ptMode2tt.putIfAbsent(ptMode, new LinkedList<>());
			ptMode2tt.get(ptMode).add(tt);

			ptMode2wt.putIfAbsent(ptMode, new LinkedList<>());
			ptMode2wt.get(ptMode).add(wt);
		}

		// File: Street Name, LinkId1, LinkId2, busLineUsages
		String file = analysisOut.resolve("avgTT_pt.csv").toString();
		BufferedWriter bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "mode", "avgTT", "avgTT_formatted", "avgWT", "avgWT_formatted"));
		bw.newLine();

		for (String ptMode : ptModes){
			double avgTT = ptMode2tt.get(ptMode).stream().mapToLong(l -> l).average().getAsDouble();
			double avgWT = ptMode2wt.get(ptMode).stream().mapToLong(l -> l).average().getAsDouble();

			bw.write(
				ptMode + ";" +
				avgTT + ";" +
				dateFormat.format(new Date((long) avgTT*1000 + reference)) + ";" +
				avgWT + ";" +
				dateFormat.format(new Date((long) avgWT*1000 + reference)));
			bw.newLine();
		}

		double allTTAvg = allTT.stream().mapToLong(l -> l).average().getAsDouble();
		double allWTAvg = allWT.stream().mapToLong(l -> l).average().getAsDouble();
		bw.write(
			"sum" + ";" +
				allTTAvg + ";" +
				dateFormat.format(new Date((long) allTTAvg*1000 + reference)) + ";" +
				allWTAvg + ";" +
				dateFormat.format(new Date((long) allWTAvg*1000 + reference)));
		bw.newLine();

		bw.close();

		return 0;
	}
}
