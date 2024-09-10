package org.matsim.prepare.beelines;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.GenericRouteFactory;
import org.matsim.core.population.routes.LinkNetworkRouteFactory;
import org.matsim.core.population.routes.RouteFactories;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.TransitScheduleUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.pt2matsim.osm.lib.*;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;

/**
 * Adds buslines to an already existing
 */
public class AddBusLanes implements MATSimAppCommand {

	@CommandLine.Option(names = "--inputOSM", defaultValue = "./beeline-data/berlin-latest.osm")
	Path osmIn;

	@CommandLine.Option(names = "--inputNetwork", defaultValue = "./input/v6.0-10pct-mapped/mapped-with-pt-network.xml.gz")
	Path networkIn;

	@CommandLine.Option(names = "--inputSchedule", defaultValue = "./input/v6.0-10pct-mapped/mapped-schedule-v6.0.2.xml.gz")
	Path scheduleIn;

	@CommandLine.Option(names = "--networkCRS", defaultValue = "EPSG:25832")
	String crs;

	@CommandLine.Option(names = "--outputNetwork", defaultValue = "./input/v6.0-10pct-mapped/mapped-with-bus-lanes-network.xml.gz")
	Path networkOut;

	@CommandLine.Option(names = "--outputSchedule", defaultValue = "./input/v6.0-10pct-mapped/mapped-with-bus-lanes-schedule.xml.gz")
	Path scheduleOut;

	public static void main(String[] args) {
		new AddBusLanes().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		//Read the MATSim files
		Network network = NetworkUtils.readNetwork(networkIn.toString());

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(scenario).readFile(scheduleIn.toString());

		// Prepare the OSM-Reader (Using the OsmReader form the pt2matsim contrib)
		AllowedTagsFilter filter = new AllowedTagsFilter();
		filter.add(Osm.ElementType.WAY, Osm.Key.HIGHWAY, null);

		OsmData osmData = new OsmDataImpl(filter);
		new OsmFileReader(osmData).readFile(osmIn.toString());

		//Save ways with buslanes
		Set<Osm.Way> busWays = new HashSet<>();
		for(Osm.Way way : osmData.getWays().values()){
			/*
			Buslanes are identified in one of the following ways:
			(official:)
				busway = *
				lanes:psv > 0
				bus:lanes = *
			(other:)
				cycleway:both = share_busway
				cycleway:left = share_busway
				cycleway:right = share_busway
				lanes:bus > 0
				psv:lanes = *
				lanes:psv:conditional = *
			 */
			for(Map.Entry<String, String> tag : way.getTags().entrySet()){
				if(tag.getKey().contains("busway") || tag.getKey().contains("bus:lanes") || tag.getKey().contains("psv:lanes")){
					System.out.println("HIT:" + way.getId());
					busWays.add(way);
					break;
				}
				if(tag.getKey().contains("lanes:psv") ||  tag.getKey().contains("lanes:bus")){
					if(!tag.getValue().equals("0")){
						System.out.println("HIT:" + way.getId());
						busWays.add(way);
						break;
					}
				}
				if(tag.getKey().contains("cycleway")){
					if(tag.getValue().equals("share_busway")){
						System.out.println("HIT:" + way.getId());
						busWays.add(way);
						break;
					}
				}
			}
		}

		//Add the bus-links into the network
		int noLinkFoundWarning = 0;
		Set<String> busMode = new HashSet<>();
		Map<Id<Link>, Id<Link>> normalLane2busLane = new HashMap<>();
		busMode.add("pt");
		for(Osm.Way way : busWays){
			Set<Link> links = mapWayToNetworkLinks(way, network);
			if(links.isEmpty()) noLinkFoundWarning++;
			if(links.size() > 1) System.out.println("Found " + links.size() + " links for "  + way.getId());
			int i = 0;
			for(Link link : links){
				if(normalLane2busLane.containsKey(link.getId())) continue;
				if(!link.getAllowedModes().contains("car")) continue;
				//Add a duplicate of this link with only pt as allowed mode
				Link busLane = NetworkUtils.createLink(Id.createLinkId(link.getId() + "_bus_" + i), link.getFromNode(), link.getToNode(), network, link.getLength(), link.getFreespeed(), 1200, 1); // TODO Set capacity to viable value
				busLane.setAllowedModes(busMode);
				network.addLink(busLane);
				normalLane2busLane.put(link.getId(), busLane.getId());
				i++;
			}
		}
		System.out.println(noLinkFoundWarning + " of " + busWays.size() + " ways could not be mapped to at least one link");

		//Rebase all the StopFacilities on a link with a buslane
		for(TransitStopFacility facility : scenario.getTransitSchedule().getFacilities().values()){
			if(normalLane2busLane.containsKey(facility.getLinkId())){
				facility.setLinkId(normalLane2busLane.get(facility.getLinkId()));
			}
		}

		//Reroute all the transitSchedules traversing a link with a buslane
		for(TransitLine line : scenario.getTransitSchedule().getTransitLines().values()){
			for(TransitRoute route : line.getRoutes().values()){

				//As there is no clean solution to get the first and last link of a NetworkRoute, we need to get it via the stops
				Id<Link> first = route.getStops().get(0).getStopFacility().getLinkId();
				Id<Link> last = route.getStops().get(route.getStops().size()-1).getStopFacility().getLinkId();

				//Now proceed with the inner links of the route
				LinkedList<Id<Link>> busLaneRoute = new LinkedList<>();
				for(Id<Link> linkId : route.getRoute().getLinkIds()){
					busLaneRoute.add(normalLane2busLane.getOrDefault(linkId, linkId));
				}

				//Apply the changes
				route.setRoute(RouteUtils.createLinkNetworkRouteImpl(first, busLaneRoute, last));
			}
		}

		//Write output files
		NetworkUtils.writeNetwork(network, networkOut.toString());
		new TransitScheduleWriter((scenario.getTransitSchedule())).writeFile(scheduleOut.toString());

		return 0;
	}

	private Set<Link> mapWayToNetworkLinks(Osm.Way way, Network network){
		//Convert the coordinates
		CoordinateTransformation transformation = TransformationFactory.getCoordinateTransformation("WGS84", crs);
		for(Osm.Node node : way.getNodes()){
			node.setCoord(transformation.transform(node.getCoord()));
		}

		//As we do not know, how much the network was compressed, we check for every single node combination
		Set<Link> mappedLinks = new HashSet<>();
		for(Osm.Node start : way.getNodes()){
			for(Osm.Node end : way.getNodes()){
				if(start == end) continue;
				//Get the nearest link in the center of both nodes
				Link candidate = NetworkUtils.getNearestLinkExactly(network, CoordUtils.getCenter(start.getCoord(), end.getCoord()));

				//Check if this candidate is correct
				//-> Check if distance of start or end to the found link is too high
				{
					//Compute the distance from start to the found link
					double x_s = start.getCoord().getX();
					double y_s = start.getCoord().getY();
					double x_e = end.getCoord().getX();
					double y_e = end.getCoord().getY();
					double x_1 = candidate.getFromNode().getCoord().getX();
					double y_1 = candidate.getFromNode().getCoord().getY();
					double x_2 = candidate.getToNode().getCoord().getX();
					double y_2 = candidate.getToNode().getCoord().getY();
					double distance_start = Math.abs((y_2-y_1)*x_s - (x_2-x_1)*y_s + x_2*y_1 - y_2*x_1) / Math.sqrt(Math.pow(x_2-y_1, 2) + Math.pow(x_2 - x_1, 2));
					double distance_end = Math.abs((y_2-y_1)*x_e - (x_2-x_1)*y_e + x_2*y_1 - y_2*x_1) / Math.sqrt(Math.pow(x_2-y_1, 2) + Math.pow(x_2 - x_1, 2));

					if(distance_start > 10 ||distance_end > 10) continue;
				}

				//-> Check if rotation difference is too high
				{
					double link_euclidean_length = NetworkUtils.getEuclideanDistance(candidate.getFromNode().getCoord(), candidate.getToNode().getCoord());
					double[] lineAsVector = new double[]{end.getCoord().getX() - start.getCoord().getX(), end.getCoord().getY() - start.getCoord().getY()};
					double[] linkAsVector = new double[]{
						candidate.getToNode().getCoord().getX() - candidate.getFromNode().getCoord().getX(),
						candidate.getToNode().getCoord().getY() - candidate.getFromNode().getCoord().getY()};
					// Compute the rotation-angle between the two using the dot-product: cos(rot)= (a*b) / (||a||*||b||)
					double rotation = (lineAsVector[0] * linkAsVector[0] + lineAsVector[1] * linkAsVector[1]) /
						(link_euclidean_length * NetworkUtils.getEuclideanDistance(start.getCoord(), end.getCoord()));
					//Convert into degree
					rotation = (Math.acos(Math.abs(rotation)) / Math.PI) * 180;

					if (rotation > 5) { //5 is an arbitrary choice
						continue;
					}
				}

				//Save the link
				mappedLinks.add(candidate);
				Link opposite = NetworkUtils.findLinkInOppositeDirection(candidate);
				if(opposite != null) mappedLinks.add(opposite);
			}
		}
		return mappedLinks;
	}
}
