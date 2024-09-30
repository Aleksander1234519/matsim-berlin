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

public class TTAnalysis implements MATSimAppCommand {

	@CommandLine.Option(names = "--inputTrips", defaultValue = "./beeline-data/.beelines/berlin-v6.0-mapped.output_trips.csv")
	Path csvIn;

	@CommandLine.Option(names = "--outputAnalysis", description = "Folder in which to put the analysis-outputs", defaultValue = "./beeline-data/tts")
	Path analysisOut;

	public static void main(String[] args) {
		new TTAnalysis().execute();
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

		Set<String> modes = new HashSet<>();
		List<Long> allTT = new ArrayList<>();
		List<Long> allWT = new ArrayList<>();
		Map<String, List<Long>> mode2tt = new HashMap<>();
		Map<String, List<Long>> mode2wt = new HashMap<>();
		for (CSVRecord record : csvFormat.parse(in)){
			String mode = record.get("main_mode");
			long tt = (dateFormat.parse(record.get("trav_time")).getTime() - reference) / 1000L;
			long wt = (dateFormat.parse(record.get("wait_time")).getTime() - reference) / 1000L;

			modes.add(mode);
			allTT.add(tt);
			allWT.add(wt);

			mode2tt.putIfAbsent(mode, new LinkedList<>());
			mode2tt.get(mode).add(tt);

			mode2wt.putIfAbsent(mode, new LinkedList<>());
			mode2wt.get(mode).add(wt);
		}

		// File: Street Name, LinkId1, LinkId2, busLineUsages
		String file = analysisOut.resolve("avgTT.csv").toString();
		BufferedWriter bw = new BufferedWriter(new FileWriter(file));
		bw.write(String.join(";", "mode", "avgTT", "avgTT_formatted", "avgWT", "avgWT_formatted"));
		bw.newLine();

		for (String mode : modes){
			double avgTT = mode2tt.get(mode).stream().mapToLong(l -> l).average().getAsDouble();
			double avgWT = mode2wt.get(mode).stream().mapToLong(l -> l).average().getAsDouble();

			bw.write(
				mode + ";" +
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

		bw.close();

		return 0;
	}
}
