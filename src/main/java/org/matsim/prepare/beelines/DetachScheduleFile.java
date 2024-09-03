package org.matsim.prepare.beelines;

import org.apache.commons.lang.StringUtils;
import org.jaitools.jts.CoordinateSequence2D;
import org.locationtech.jts.geom.*;
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
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.pt.transitSchedule.TransitScheduleUtils;
import org.matsim.pt.transitSchedule.api.*;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;

public class DetachScheduleFile implements MATSimAppCommand {

	@CommandLine.Option(names = "--originalScheduleInput", defaultValue = "./beeline-data/berlin-v5.5-transit-schedule.xml.gz")
	Path originalScheduleIn;

	@CommandLine.Option(names = "--preparedScheduleInput", defaultValue = "./beeline-data/mapped-schedule.xml.gz")
	Path preparedScheduleIn;

	@CommandLine.Option(names = "--originalNetworkInput", defaultValue = "./beeline-data/berlin-v5.5-network.xml.gz")
	Path originalNetworkIn;

	@CommandLine.Option(names = "--preparedNetworkInput", defaultValue = "./beeline-data/mapped-network.xml.gz")
	Path preparedNetworkIn;

	@CommandLine.Option(names = "--output", defaultValue = "./beeline-data/pre-schedule.xml.gz")
	Path scheduleOut;

	public static void main(String[] args) {
		new DetachScheduleFile().execute(args);
	}

	/**
	 *
	 * <i>NOTE: This class works mainly with Link-Objects instead of its ids because it reduces the risk of bugs,
	 * since we are working with many different networks.</i>
	 * @return
	 * @throws Exception
	 */
	@Override
	public Integer call() throws Exception {
		// ===== Load data =====
		Network originalNetwork = NetworkUtils.readNetwork(originalNetworkIn.toString());
		Network preparedNetwork = NetworkUtils.readNetwork(preparedNetworkIn.toString());

		Scenario originalScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(originalScenario).readFile(originalScheduleIn.toString());

		Scenario preparedScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(preparedScenario).readFile(preparedScheduleIn.toString());

		TransitScheduleFactory factory = originalScenario.getTransitSchedule().getFactory();

		// ===== Filter car and non-highway links =====
		Network filteredNetwork = filterCityStreets(originalNetwork);

		// ===== Apply links to StopFacilities =====
		Map<Link, Set<String>> link2busLines = computeTransferedFacilityMap(preparedScenario, preparedNetwork, filteredNetwork);

		//Create a shallow-copy of the facility-collection to avoid a ConcurrentModificationException
		List<TransitStopFacility> facilities = new LinkedList<>(originalScenario.getTransitSchedule().getFacilities().values());
		int i = 0;
		for(TransitStopFacility f : facilities){
			System.out.println(f.getName() + ": " + i++ + "/" + facilities.size());

			//Skip if non-bus TODO What do if it is used by bus and something else?
			Set<String> usingLines = getUsingLines(originalScenario, f);
			if(usingLines.isEmpty()) continue;

			//Find the car network link for this factory
			List<Link> linksInRadius = getLinksInRadius(link2busLines.keySet(), f.getCoord(), 100);
			if(linksInRadius.isEmpty()){
				linksInRadius.add(NetworkUtils.getNearestLinkExactly(filteredNetwork, f.getCoord()));
				//TODO
			}
			/*if(linksInRadius.isEmpty()) {
				//TODO
				linksInRadius = getLinksInRadius(network, NetworkUtils.getNearestLink(filteredNetwork, f.getCoord()).getCoord(), 50);
				continue;
			}
			assert !linksInRadius.isEmpty();*/

			Link bestCandidate = linksInRadius.get(0);
			double candidateSimilarity = 0;

			Link ptLink = originalNetwork.getLinks().get(f.getLinkId());
			double dx = ptLink.getToNode().getCoord().getX() - ptLink.getFromNode().getCoord().getX();
			double dy = ptLink.getToNode().getCoord().getY() - ptLink.getFromNode().getCoord().getY();
			double[] ptVector = new double[]{
				dx,
				dy};

			for(Link l : linksInRadius){
				//Only proceed if this link can be a station at all
				if(!link2busLines.containsKey(l)){
					System.out.println("WARNING#:" + l.getId());
					continue;
				}
				if(Collections.disjoint(link2busLines.get(l), usingLines)) continue;

				dx = l.getToNode().getCoord().getX() - l.getFromNode().getCoord().getX();
				dy = l.getToNode().getCoord().getY() - l.getFromNode().getCoord().getY();
				double similarity = normalizedDotProduct(
					ptVector,
					new double[]{
						dx,
						dy
					}
				);
				if(similarity > candidateSimilarity){
					bestCandidate = l;
					candidateSimilarity = similarity;
				}
			}

			TransitStopFacility replacement = factory.createTransitStopFacility(Id.create(f.getId(), TransitStopFacility.class), f.getCoord(), f.getIsBlockingLane());
			replacement.setLinkId(bestCandidate.getId());
			originalScenario.getTransitSchedule().removeStopFacility(f);
			originalScenario.getTransitSchedule().addStopFacility(replacement);
		}

		new TransitScheduleWriter((originalScenario.getTransitSchedule())).writeFile(scheduleOut.toString());

		return 0;
	}

	/**
	 * Filters out all non-car and highway links.
	 * @param originalNetwork Original: network (will not be modified)
	 * @return filtered network
	 */
	private Network filterCityStreets(Network originalNetwork){
		TransportModeNetworkFilter filter = new TransportModeNetworkFilter(originalNetwork);
		Network filteredNetwork = NetworkUtils.createNetwork();
		filter.filter(filteredNetwork, Set.of("car"));
		Collection<? extends Link> links = filteredNetwork.getLinks().values();
		for(Link l : filteredNetwork.getLinks().values()){
			if(StringUtils.containsIgnoreCase((String) l.getAttributes().getAttribute("type"), "motorway")) originalNetwork.removeLink(l.getId());
		}
		return filteredNetwork;
	}

	/**
	 * Creates a map, which is needed to apply the facilities of the original scenario to its network.
	 * Reads out the facilities of a scenario and transfers the links to the target network and maps it to the corresponding bus-line name.
	 * @param preparedScenario Prepared: scenario to read the facilities from.
	 * @param preparedNetwork Prepared: network to get the facility links from.
	 * @param filteredNetwork Original: filtered network to map the facility-links to (filter with {@link DetachScheduleFile#filterCityStreets}).
	 * @return A Map containing all bus-station links of the target network and its corresponding lines. link2busLines
	 */
	private Map<Link, Set<String>> computeTransferedFacilityMap(Scenario preparedScenario,
																Network preparedNetwork,
																Network filteredNetwork){
		//TODO Refactor this absolute mess

		//Get the links of the prepared schedule with bus stations
		List<Id<Link>> preparedBusStationLinkIds = new LinkedList<>(); // bus station links
		for(TransitStopFacility facility : preparedScenario.getTransitSchedule().getFacilities().values()){
			if(preparedNetwork.getLinks().get(facility.getLinkId()).getAllowedModes().contains("bus")){
				preparedBusStationLinkIds.add(facility.getLinkId());
			}
			TransitScheduleUtils.createQuadTreeOfTransitStopFacilities(preparedScenario.getTransitSchedule());
		}


		Map<Link, Set<String>> link2busLines = new HashMap<>(); // transferred bus station links
		Map<Link, Link> preparedLink2OriginalLink = new HashMap<>();

		for(Id<Link> id : preparedBusStationLinkIds){
			Link link = transferLink(filteredNetwork, preparedNetwork.getLinks().get(id));
			link2busLines.putIfAbsent(link, new HashSet<>());
			preparedLink2OriginalLink.put(preparedNetwork.getLinks().get(id), link);
		}

		for(Map.Entry<Id<TransitLine>, TransitLine> line : preparedScenario.getTransitSchedule().getTransitLines().entrySet()){
			if(line.getValue().getRoutes().isEmpty()) continue;
			if(!Objects.equals(line.getValue().getRoutes().values().stream().toList().get(0).getTransportMode(), "bus")) continue;

			String lineName = line.getKey().toString().split(":")[line.getKey().toString().split(":").length-1];
			for(TransitRoute r : line.getValue().getRoutes().values()){
				for(TransitRouteStop stop : r.getStops()){
					Link link = preparedLink2OriginalLink.get(preparedNetwork.getLinks().get(stop.getStopFacility().getLinkId()));
					System.out.println(link.getId() + " " + lineName);
					link2busLines.get(link).add(lineName); //TODO Does every entry really exist?
				}
			}
		}

		return link2busLines;
	}

	private Link transferLink(Network targetNetwork,
							  Link link){
		Link foundLink = NetworkUtils.getNearestLinkExactly(targetNetwork, CoordUtils.getCenter(link.getFromNode().getCoord(), link.getToNode().getCoord()));

		double dx_o = link.getToNode().getCoord().getX() - link.getFromNode().getCoord().getX();
		double dy_o = link.getToNode().getCoord().getY() - link.getFromNode().getCoord().getY();

		double dx_t = foundLink.getToNode().getCoord().getX() - foundLink.getFromNode().getCoord().getX();
		double dy_t = foundLink.getToNode().getCoord().getY() - foundLink.getFromNode().getCoord().getY();

		double[] originVector = new double[]{
			dx_o,
			dy_o};

		double[] targetVector = new double[]{
			dx_t,
			dy_t};

		if (normalizedDotProduct(originVector, targetVector) > 0) return foundLink;

		//Make a deeper search
		List<Link> linksInRadius = getLinksInRadius(targetNetwork, CoordUtils.getCenter(link.getFromNode().getCoord(), link.getToNode().getCoord()), 100);
		if(linksInRadius.isEmpty()){
			//TODO just use artificial link
			linksInRadius = getLinksInRadius(
				targetNetwork,
				NetworkUtils.getNearestLinkExactly(
					targetNetwork,
					CoordUtils.getCenter(link.getFromNode().getCoord(), link.getToNode().getCoord())).getCoord(),
				100);
		}
		Link bestCandidate = linksInRadius.get(0);
		double candidateSimilarity = 0;


		for(Link l : linksInRadius){
			dx_t = l.getToNode().getCoord().getX() - l.getFromNode().getCoord().getX();
			dy_t = l.getToNode().getCoord().getY() - l.getFromNode().getCoord().getY();

			targetVector = new double[]{
				dx_t,
				dy_t};

			if (normalizedDotProduct(originVector, targetVector) > candidateSimilarity){
				bestCandidate = l;
				candidateSimilarity = normalizedDotProduct(originVector, targetVector);
			}
		}
		return bestCandidate;
	}

	/**
	 * Returns a set of bus-line-names this stop has been used in.
	 * @param scenario Scenario to search in
	 * @param facility StopFacility to search for
	 * @return Set of bus-line-names, empty if none was found
	 */
	private Set<String> getUsingLines(Scenario scenario,
								TransitStopFacility facility){
		Set<String> ret = new HashSet<>();
		for(TransitLine line : scenario.getTransitSchedule().getTransitLines().values()){
			if(!Objects.equals(line.getRoutes().values().stream().toList().get(0).getTransportMode(), "bus")) continue;

			//Check if facility is part of this line
			search:
			{
				for(TransitRoute transitRoute : line.getRoutes().values()){
					for(TransitRouteStop stop : transitRoute.getStops()){
						if(stop.getStopFacility() == facility){
							ret.add(line.getId().toString().split("---")[0]);
							break search;
						}
					}
				}

			}

		}
		return ret;
	}

	private List<Link> getLinksInRadius(Collection<Link> links,
										Coord coord,
										double radius){
		GeometryFactory geometryFactory = new GeometryFactory();
		Geometry pointGeo = new Point(new CoordinateSequence2D(coord.getX(), coord.getY()), geometryFactory).buffer(radius);

		List<Link> inRadius = new LinkedList<>();
		for(Link l : links){
			Geometry linkGeometry = new LineString(
				new CoordinateSequence2D(
					l.getFromNode().getCoord().getX(),
					l.getFromNode().getCoord().getY(),
					l.getToNode().getCoord().getX(),
					l.getToNode().getCoord().getY()),
				geometryFactory);
			if(pointGeo.crosses(linkGeometry)) inRadius.add(l);
		}

		return inRadius;
	}

	private List<Link> getLinksInRadius(Network network,
										Coord coord,
										double radius){
		GeometryFactory geometryFactory = new GeometryFactory();
		Geometry pointGeo = new Point(new CoordinateSequence2D(coord.getX(), coord.getY()), geometryFactory).buffer(radius);

		List<Link> inRadius = new LinkedList<>();
		for(Link l : network.getLinks().values()){
			Geometry linkGeometry = new LineString(
				new CoordinateSequence2D(
					l.getFromNode().getCoord().getX(),
					l.getFromNode().getCoord().getY(),
					l.getToNode().getCoord().getX(),
					l.getToNode().getCoord().getY()),
				geometryFactory);
			if(pointGeo.crosses(linkGeometry)) inRadius.add(l);
		}

		return inRadius;
	}

	private double normalizedDotProduct(double[] vectorA,
										double[] vectorB){
		if(vectorA.length != vectorB.length) throw new IllegalArgumentException("Vectors must be of same length for dot product!");

		double eucLen_a = 0;
		for(double a : vectorA) eucLen_a += a*a;
		eucLen_a = Math.sqrt(eucLen_a);

		double eucLen_b = 0;
		for(double b : vectorB) eucLen_b += b*b;
		eucLen_b = Math.sqrt(eucLen_b);

		double sum = 0;
		for(int i = 0; i < vectorA.length; i++){
			sum += (vectorA[i]/eucLen_a) * (vectorB[i]/eucLen_b);
		}

		return sum;
	}
}
