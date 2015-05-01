package net.stemmaweb.rest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import net.stemmaweb.model.DuplicateModel;
import net.stemmaweb.model.ReadingModel;
import net.stemmaweb.services.DatabaseService;
import net.stemmaweb.services.EvaluatorService;
import net.stemmaweb.services.ReadingService;
import net.stemmaweb.services.RelationshipService;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.traversal.Evaluator;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.Uniqueness;

/**
 * 
 * Comprises all the api calls related to a reading.
 *
 */
@Path("/reading")
public class Reading implements IResource {

	private String errorMessage;
	GraphDatabaseFactory dbFactory = new GraphDatabaseFactory();

	/**
	 * Returns a single reading in a specific tradition.
	 * 
	 * @param tradId
	 * @param readId
	 * @return
	 */
	@GET
	@Path("getreading/fromtradition/{tradId}/withreadingid/{readId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getReading(@PathParam("tradId") String tradId,
			@PathParam("readId") long readId) {
		GraphDatabaseService db = dbFactory.newEmbeddedDatabase(DB_PATH);

		ReadingModel reading = null;
		Node readingNode;

		try (Transaction tx = db.beginTx()) {
			try {
				readingNode = db.getNodeById(readId);
			} catch (NotFoundException e) {
				return Response.status(Status.NOT_FOUND)
						.entity("no reading with this id found").build();
			}
			reading = new ReadingModel(readingNode);

			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		return Response.ok(reading).build();
	}

	/**
	 * Duplicates a reading in a specific tradition. Opposite of merge
	 * 
	 * @param tradId
	 * @param duplicateModel
	 * @return
	 */
	@POST
	@Path("duplicatereading/fromtradition/{tradId}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response duplicateReading(@PathParam("tradId") String tradId,
			DuplicateModel duplicateModel) {
		GraphDatabaseService db = dbFactory.newEmbeddedDatabase(DB_PATH);

		ArrayList<ReadingModel> createdReadings = new ArrayList<ReadingModel>();

		Node originalReading = null;

		try (Transaction tx = db.beginTx()) {
			List<Long> readings = duplicateModel.getReadings();
			for (Long readId : readings) {
				try {
					originalReading = db.getNodeById(readId);
				} catch (NotFoundException e) {
					return Response.status(Status.NOT_FOUND)
							.entity("no reading with this id found: " + readId)
							.build();
				}

				List<String> newWitnesses = duplicateModel.getWitnesses();

				if (cannotBeDuplicated(originalReading, newWitnesses))
					return Response.status(Status.INTERNAL_SERVER_ERROR)
							.entity(errorMessage).build();

				Node newNode = db.createNode();

				duplicate(newWitnesses, originalReading, newNode);

				createdReadings.add(new ReadingModel(newNode));
			}

			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		return Response.ok(createdReadings).build();
	}

	/**
	 * Checks if the reading can be duplicated for the given witness list
	 * 
	 * @param originalReading
	 * @param newWitnesses
	 * @return
	 */
	private boolean cannotBeDuplicated(Node originalReading,
			List<String> newWitnesses) {
		List<String> allWitnesses = allWitnessesOfReading(originalReading);

		if (newWitnesses.isEmpty()) {
			errorMessage = "The witness list has to contain at least one witness";
			return true;
		}

		for (String newWitness : newWitnesses)
			if (!allWitnesses.contains(newWitness)) {
				errorMessage = "The reading has to be in the witnesses to be duplicated";
				return true;
			}

		if (allWitnesses.size() < 2) {
			errorMessage = "The reading has to be in at least two witnesses";
			return true;
		}

		return false;
	}

	/**
	 * Gets all the witnesses of a reading in all its normal relationships.
	 * 
	 * @param originalReading
	 * @return
	 */
	private List<String> allWitnessesOfReading(Node originalReading) {
		List<String> allWitnesses = new LinkedList<String>();
		String[] currentWitnesses;
		for (Relationship relationship : originalReading
				.getRelationships(ERelations.NORMAL)) {
			currentWitnesses = (String[]) relationship.getProperty("lexemes");
			for (String currentWitness : currentWitnesses)
				if (!allWitnesses.contains(currentWitness))
					allWitnesses.add(currentWitness);
		}
		return allWitnesses;
	}

	/**
	 * Performs all necessary steps in the database to duplicate the reading.
	 * 
	 * @param newWitnesses
	 * @param originalReading
	 * @param addedReading
	 */
	private void duplicate(List<String> newWitnesses, Node originalReading,
			Node addedReading) {
		// copy reading properties to newly added reading
		addedReading = ReadingService.copyReadingProperties(originalReading,
				addedReading);

		// copy relationships
		for (Relationship originalRel : originalReading
				.getRelationships(ERelations.RELATIONSHIP)) {
			Relationship newRel = addedReading.createRelationshipTo(
					originalRel.getOtherNode(originalReading),
					ERelations.RELATIONSHIP);
			for (String key : originalRel.getPropertyKeys())
				newRel.setProperty(key, originalRel.getProperty(key));
		}

		// add witnesses to normal relationships
		// Incoming
		for (Relationship originalRelationship : originalReading
				.getRelationships(ERelations.NORMAL, Direction.INCOMING))
			transferNewWitnessesFromOriginalReadingToAddedReading(newWitnesses,
					originalRelationship, originalRelationship.getStartNode(),
					addedReading);
		// Outgoing
		for (Relationship originalRelationship : originalReading
				.getRelationships(ERelations.NORMAL, Direction.OUTGOING))
			transferNewWitnessesFromOriginalReadingToAddedReading(newWitnesses,
					originalRelationship, addedReading,
					originalRelationship.getEndNode());
	}

	/**
	 * Transfers all the new witnesses from the relationships of the original
	 * reading to the relationships of the newly added reading.
	 * 
	 * @param newWitnesses
	 * @param originalRel
	 * @param originNode
	 * @param targetNode
	 */
	private void transferNewWitnessesFromOriginalReadingToAddedReading(
			List<String> newWitnesses, Relationship originalRel,
			Node originNode, Node targetNode) {
		String[] oldWitnesses = (String[]) originalRel.getProperty("lexemes");
		// if oldWitnesses only contains one witness and this one should be
		// duplicated, create new relationship for addedReading and delete
		// the one from the originalReading
		if (oldWitnesses.length == 1) {
			if (newWitnesses.contains(oldWitnesses[0])) {
				Relationship newRel = originNode.createRelationshipTo(
						targetNode, ERelations.NORMAL);
				newRel.setProperty("lexemes", oldWitnesses);
				originalRel.delete();
			}
			// if oldWitnesses contains more than one witnesses, create new
			// relationship and add those witnesses which should be duplicated
		} else {
			// add only those witnesses to oldWitnesses which are
			// not in newWitnesses
			ArrayList<String> remainingWitnesses = new ArrayList<String>();
			ArrayList<String> stayingWitnesses = new ArrayList<String>();
			for (String oldWitness : oldWitnesses)
				if (!newWitnesses.contains(oldWitness))
					stayingWitnesses.add(oldWitness);
				else
					remainingWitnesses.add(oldWitness);

			Relationship addedRelationship = originNode.createRelationshipTo(
					targetNode, ERelations.NORMAL);
			addedRelationship.setProperty("lexemes", remainingWitnesses
					.toArray(new String[remainingWitnesses.size()]));

			if (stayingWitnesses.isEmpty())
				originalRel.delete();
			else
				originalRel.setProperty("lexemes", stayingWitnesses
						.toArray(new String[stayingWitnesses.size()]));
		}
	}

	/**
	 * Merges two readings into one single reading in a specific tradition.
	 * Opposite of duplicate
	 * 
	 * @param tradId
	 * @param firstReadId
	 * @param secondReadId
	 * @return
	 */
	@POST
	@Path("mergereadings/fromtradition/{tradId}/firstreading/{firstReadId}/secondreading/{secondReadId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response mergeReadings(@PathParam("tradId") String tradId,
			@PathParam("firstReadId") long firstReadId,
			@PathParam("secondReadId") long secondReadId) {
		GraphDatabaseService db = dbFactory.newEmbeddedDatabase(DB_PATH);

		ArrayList<ReadingModel> stayingReadings = new ArrayList<ReadingModel>();

		Node stayingReading = null;
		Node deletingReading = null;

		try (Transaction tx = db.beginTx()) {
			try {
				stayingReading = db.getNodeById(firstReadId);
				deletingReading = db.getNodeById(secondReadId);
			} catch (NotFoundException e) {
				return Response.status(Status.NOT_FOUND)
						.entity("no readings with those ids found").build();
			}

			if (cannotBeMerged(db, stayingReading, deletingReading))
				return Response.status(Status.INTERNAL_SERVER_ERROR)
						.entity(errorMessage).build();

			// finally merge readings ;-)
			merge(stayingReading, deletingReading);

			stayingReadings.add(new ReadingModel(stayingReading));

			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}

		return Response.ok(stayingReadings).build();
	}

	/**
	 * Checks if the two readings can be merged or not.
	 * 
	 * @param db
	 * @param stayingReading
	 * @param deletingReading
	 * @return
	 */
	private boolean cannotBeMerged(GraphDatabaseService db,
			Node stayingReading, Node deletingReading) {
		if (doNotContainSameText(stayingReading, deletingReading)) {
			errorMessage = "Readings to be merged do not contain the same text";
			return true;
		}

		if (doNotContainRelationshipBetweenEachOther(stayingReading,
				deletingReading)) {
			errorMessage = "Readings to be merged have to be connected with each other through a relationship";
			return true;
		}

		if (containClassTwoRelationships(stayingReading, deletingReading)) {
			errorMessage = "Readings to be merged cannot contain class 2 relationships (transposition / repetition)";
			return true;
		}

		if (ReadingService.wouldGetCyclic(db, stayingReading, deletingReading)) {
			errorMessage = "Readings to be merged would make the graph cyclic";
			return true;
		}

		return false;
	}

	/**
	 * Checks if the two readings contain the same text or not.
	 * 
	 * @param stayingReading
	 * @param deletingReading
	 * @return
	 */
	private boolean doNotContainSameText(Node stayingReading,
			Node deletingReading) {
		return !stayingReading.getProperty("dn15").toString()
				.equals(deletingReading.getProperty("dn15").toString());
	}

	/**
	 * Checks if the two readings have a relationship between each other.
	 * 
	 * @param stayingReading
	 * @param deletingReading
	 * @return
	 */
	private boolean doNotContainRelationshipBetweenEachOther(
			Node stayingReading, Node deletingReading) {
		for (Relationship firstRel : stayingReading
				.getRelationships(ERelations.RELATIONSHIP))
			for (Relationship secondRel : deletingReading
					.getRelationships(ERelations.RELATIONSHIP))
				if (firstRel.equals(secondRel))
					return false;
		return true;
	}

	/**
	 * Checks if the two readings have a relationship between each other which
	 * is of class two (transposition / repetition).
	 * 
	 * @param stayingReading
	 * @param deletingReading
	 * @return
	 */
	private boolean containClassTwoRelationships(Node stayingReading,
			Node deletingReading) {
		for (Relationship stayingRel : stayingReading
				.getRelationships(ERelations.RELATIONSHIP))
			if (stayingRel.getOtherNode(stayingReading).equals(deletingReading))
				if (stayingRel.getProperty("de11").equals("transposition")
						|| stayingRel.getProperty("de11").equals("repetition"))
					return true;
		return false;
	}

	/**
	 * Performs all necessary steps in the database to merge two readings into
	 * one.
	 * 
	 * @param stayingReading
	 * @param deletingReading
	 */
	private void merge(Node stayingReading, Node deletingReading) {
		deleteRelationshipBetweenReadings(stayingReading, deletingReading);
		copyWitnesses(stayingReading, deletingReading, Direction.INCOMING);
		copyWitnesses(stayingReading, deletingReading, Direction.OUTGOING);
		addRelationshipsToStayingReading(stayingReading, deletingReading);
		deletingReading.delete();
	}

	/**
	 * Deletes the relationship between the two readings.
	 * 
	 * @param stayingReading
	 * @param deletingReading
	 */
	private void deleteRelationshipBetweenReadings(Node stayingReading,
			Node deletingReading) {
		for (Relationship firstRel : stayingReading
				.getRelationships(ERelations.RELATIONSHIP))
			for (Relationship secondRel : deletingReading
					.getRelationships(ERelations.RELATIONSHIP))
				if (firstRel.equals(secondRel))
					firstRel.delete();
	}

	/**
	 * Copies the witnesses from the reading to be deleted to the staying
	 * reading.
	 * 
	 * @param stayingReading
	 * @param deletingReading
	 */
	private void copyWitnesses(Node stayingReading, Node deletingReading,
			Direction direction) {
		for (Relationship stayingRel : stayingReading.getRelationships(
				ERelations.NORMAL, direction))
			for (Relationship deletingRel : deletingReading.getRelationships(
					ERelations.NORMAL, direction)) {
				// if (!firstRel.equals(secondRel))
				if (stayingRel.getOtherNode(stayingReading).equals(
						deletingRel.getOtherNode(deletingReading))) {
					// get Witnesses
					String[] stayingReadingWitnesses = (String[]) stayingRel
							.getProperty("lexemes");
					String[] deletingReadingWitnesses = (String[]) deletingRel
							.getProperty("lexemes");

					// combine witness lists into one list
					String[] combinedWitnesses = new String[stayingReadingWitnesses.length
							+ deletingReadingWitnesses.length];
					for (int i = 0; i < stayingReadingWitnesses.length; i++)
						combinedWitnesses[i] = stayingReadingWitnesses[i];
					for (int i = 0; i < deletingReadingWitnesses.length; i++)
						combinedWitnesses[stayingReadingWitnesses.length + i] = deletingReadingWitnesses[i];
					stayingRel.setProperty("lexemes", combinedWitnesses);

				} else {
					Relationship newRel = stayingReading.createRelationshipTo(
							deletingRel.getOtherNode(deletingReading),
							ERelations.NORMAL);
					newRel.setProperty("lexemes",
							deletingRel.getProperty("lexemes"));
				}
				deletingRel.delete();
			}
	}

	/**
	 * Adds the relationships from the reading to be deleted to the staying
	 * reading.
	 * 
	 * @param stayingReading
	 * @param deletingReading
	 */
	private void addRelationshipsToStayingReading(Node stayingReading,
			Node deletingReading) {
		// copy relationships from deletingReading to stayingReading
		for (Relationship oldRel : deletingReading.getRelationships(
				ERelations.RELATIONSHIP, Direction.OUTGOING)) {
			Relationship newRel = stayingReading.createRelationshipTo(
					oldRel.getEndNode(), ERelations.RELATIONSHIP);
			newRel = RelationshipService.copyRelationshipProperties(oldRel,
					newRel);
			oldRel.delete();
		}
		for (Relationship oldRel : deletingReading.getRelationships(
				ERelations.RELATIONSHIP, Direction.INCOMING)) {
			Relationship newRel = oldRel.getStartNode().createRelationshipTo(
					stayingReading, ERelations.RELATIONSHIP);
			newRel = RelationshipService.copyRelationshipProperties(oldRel,
					newRel);
			oldRel.delete();
		}
	}

	/**
	 * Splits up a single reading into several ones in a specific tradition.
	 * Opposite of compress
	 * 
	 * @param tradId
	 * @param readId
	 * @return
	 */
	@POST
	@Path("splitreading/fromtradition/{tradId}/ofreading/{readId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response splitReading(@PathParam("tradId") String tradId,
			@PathParam("readId") long readId) {
		GraphDatabaseService db = dbFactory.newEmbeddedDatabase(DB_PATH);
		ArrayList<ReadingModel> createdNodes = null;
		Node originalReading = null;

		try (Transaction tx = db.beginTx()) {
			try {
				originalReading = db.getNodeById(readId);
			} catch (NotFoundException e) {
				db.shutdown();
				return Response.status(Status.NOT_FOUND)
						.entity("no reading with this id found").build();
			}

			String[] splittedWords = originalReading.getProperty("dn15")
					.toString().split("\\s+");

			if (cannotBeSplitted(originalReading, splittedWords))
				return Response.status(Status.INTERNAL_SERVER_ERROR)
						.entity(errorMessage).build();

			createdNodes = split(db, originalReading, splittedWords);

			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		return Response.ok(createdNodes).build();
	}

	/**
	 * Checks if a reading can be splitted or not.
	 * 
	 * @param originalReading
	 * @param splittedWords
	 * @return
	 */
	private boolean cannotBeSplitted(Node originalReading,
			String[] splittedWords) {
		if (splittedWords.length < 2) {
			errorMessage = "A reading to be splitted has to contain at least 2 words";
			return true;
		}

		if (originalReading.hasRelationship(ERelations.RELATIONSHIP)) {
			errorMessage = "A reading to be splitted cannot be part of any relationship";
			return true;
		}

		if (!hasRankGap(originalReading, splittedWords.length)) {
			errorMessage = "There has to be a rank-gap after a reading to be splitted";
			return true;
		}

		return false;
	}

	/**
	 * Checks if there is a rank gap after the reading to be splitted. The rank
	 * gap has to have at least the size of the readings words. E.g. if the
	 * reading is "the little mouse" and has rank 5 the next reading has to have
	 * at least rank 8.
	 * 
	 * @param originalReading
	 * @param numberOfWords
	 * @return
	 */
	private boolean hasRankGap(Node originalReading, int numberOfWords) {
		String rankKey = "dn14";
		Long rank = (Long) originalReading.getProperty(rankKey);
		for (Relationship rel : originalReading.getRelationships(
				Direction.OUTGOING, ERelations.NORMAL)) {
			Node nextNode = rel.getEndNode();
			if (nextNode.hasProperty(rankKey)) {
				Long nextRank = (Long) nextNode.getProperty(rankKey);
				if (nextRank - rank >= numberOfWords)
					return true;
			}
		}
		return false;
	}

	/**
	 * Performs all necessary steps in the database to split the reading.
	 * 
	 * @param db
	 * @param originalReading
	 * @param splittedWords
	 * @return
	 */
	private ArrayList<ReadingModel> split(GraphDatabaseService db,
			Node originalReading, String[] splittedWords) {
		ArrayList<ReadingModel> createdNodes = new ArrayList<ReadingModel>();
		originalReading.setProperty("dn15", splittedWords[0]);
		createdNodes.add(new ReadingModel(originalReading));
		for (int i = 1; i < splittedWords.length; i++) {
			Node newReading = db.createNode();

			// is this assignment necessary or does that function
			// otherwise as well in this transaction?
			newReading = ReadingService.copyReadingProperties(originalReading,
					newReading);
			newReading.setProperty("dn15", splittedWords[i]);
			Long previousRank = (Long) originalReading.getProperty("dn14");
			newReading.setProperty("dn14", previousRank + 1);

			ArrayList<String> allWitnesses = new ArrayList<String>();
			Iterable<Relationship> rels = originalReading.getRelationships(
					ERelations.NORMAL, Direction.OUTGOING);
			for (Relationship relationship : rels) {
				String[] witnesses = (String[]) relationship
						.getProperty("lexemes");
				for (int j = 0; j < witnesses.length; j++)
					allWitnesses.add(witnesses[j]);

				newReading.createRelationshipTo(relationship.getEndNode(),
						ERelations.NORMAL);
				relationship.delete();
			}

			Relationship relationship = originalReading.createRelationshipTo(
					newReading, ERelations.NORMAL);
			relationship.setProperty("lexemes",
					allWitnesses.toArray(new String[allWitnesses.size()]));
			createdNodes.add(new ReadingModel(newReading));
		}
		return createdNodes;
	}

	/**
	 * gets the next readings from a given readings in the same witness
	 * 
	 * @param textId
	 *            : witness id
	 * @param readId
	 *            : reading id
	 * 
	 * @return the requested reading
	 */
	@GET
	@Path("getnextreading/fromwitness/{textId}/ofreading/{readId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getNextReadingInWitness(@PathParam("textId") String textId,
			@PathParam("readId") long readId) {

		final String WITNESS_ID = textId;
		GraphDatabaseService db = dbFactory.newEmbeddedDatabase(DB_PATH);
		EvaluatorService evaService = new EvaluatorService();
		Evaluator witnessEvaluator = evaService.getEvalForWitness(WITNESS_ID);

		try (Transaction tx = db.beginTx()) {
			Node reading = db.getNodeById(readId);

			for (Node node : db.traversalDescription().depthFirst()
					.relationships(ERelations.NORMAL, Direction.OUTGOING)
					.evaluator(witnessEvaluator)
					.evaluator(Evaluators.toDepth(1))
					.uniqueness(Uniqueness.NONE).traverse(reading).nodes()) {

				if (!new ReadingModel(node).getDn15().equals("#END#"))
					return Response.ok(new ReadingModel(node)).build();
				else
					return Response
							.status(Status.NOT_FOUND)
							.entity("this was the last reading of this witness")
							.build();
			}
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		return Response.status(Status.NOT_FOUND)
				.entity("given readings not found").build();
	}

	/**
	 * gets the next readings from a given readings in the same witness
	 * 
	 * @param textId
	 *            : witness id
	 * @param readId
	 *            : reading id
	 * 
	 * @return the requested reading
	 */
	@GET
	@Path("getpreviousreading/fromwitness/{textId}/ofreading/{readId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getPreviousReadingInWitness(
			@PathParam("textId") String textId, @PathParam("readId") long readId) {

		final String WITNESS_ID = textId;
		GraphDatabaseService db = dbFactory.newEmbeddedDatabase(DB_PATH);
		EvaluatorService evaService = new EvaluatorService();
		Evaluator wintessEvaluator = evaService.getEvalForWitness(WITNESS_ID);

		try (Transaction tx = db.beginTx()) {
			Node read = db.getNodeById(readId);

			for (Node node : db.traversalDescription().depthFirst()
					.relationships(ERelations.NORMAL, Direction.INCOMING)
					.evaluator(wintessEvaluator)
					.evaluator(Evaluators.toDepth(1))
					.uniqueness(Uniqueness.NONE).traverse(read).nodes()) {

				if (!new ReadingModel(node).getDn15().equals("#START#"))
					return Response.ok(new ReadingModel(node)).build();
				else
					return Response
							.status(Status.NOT_FOUND)
							.entity("there is no previous reading to this reading")
							.build();
			}
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		return Response.status(Status.NOT_FOUND)
				.entity("given readings not found").build();
	}

	@GET
	@Path("getallreadings/fromtradition/{tradId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getAllReadings(@PathParam("tradId") String tradId) {

		GraphDatabaseService db = dbFactory.newEmbeddedDatabase(DB_PATH);
		ArrayList<ReadingModel> readingModels = new ArrayList<ReadingModel>();

		Node startNode = DatabaseService.getStartNode(tradId, db);
		if (startNode == null) {
			return Response.status(Status.NOT_FOUND)
					.entity("Could not find tradition with this id").build();
		}
		try (Transaction tx = db.beginTx()) {
			readingModels = getAllReadingsFromTradition(startNode, db);
			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		return Response.ok(readingModels).build();
	}

	private ArrayList<ReadingModel> getAllReadingsFromTradition(Node startNode,
			GraphDatabaseService db) {

		ArrayList<ReadingModel> readingModels = new ArrayList<ReadingModel>();

		for (Node node : db.traversalDescription().depthFirst()
				.relationships(ERelations.NORMAL, Direction.OUTGOING)
				.evaluator(Evaluators.all()).uniqueness(Uniqueness.NODE_GLOBAL)
				.traverse(startNode).nodes()) {
			ReadingModel tempReading = new ReadingModel(node);
			readingModels.add(tempReading);
		}

		return readingModels;
	}

	private ArrayList<ReadingModel> getAllReadingsFromTraditionBetweenRanks(
			Node startNode, long startRank, long endRank,
			GraphDatabaseService db) {

		ArrayList<ReadingModel> readingModels = new ArrayList<ReadingModel>();

		for (Node node : db.traversalDescription().depthFirst()
				.relationships(ERelations.NORMAL, Direction.OUTGOING)
				.evaluator(Evaluators.all()).uniqueness(Uniqueness.NODE_GLOBAL)
				.traverse(startNode).nodes()) {
			long nodeRank = (long) node.getProperty("dn14");

			if (nodeRank < endRank && nodeRank > startRank) {
				ReadingModel tempReading = new ReadingModel(node);
				readingModels.add(tempReading);
			}
		}
		Collections.sort(readingModels);
		return readingModels;
	}

	@GET
	@Path("getidenticalreadings/fromtradition/{tradId}/fromstartrank/{startRank}/toendrank/{endRank}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getIdenticalReadings(@PathParam("tradId") String tradId,
			@PathParam("startRank") long startRank,
			@PathParam("endRank") long endRank) {

		GraphDatabaseService db = dbFactory.newEmbeddedDatabase(DB_PATH);
		ArrayList<ReadingModel> readingModels = new ArrayList<ReadingModel>();

		Node startNode = DatabaseService.getStartNode(tradId, db);
		if (startNode == null)
			return Response.status(Status.NOT_FOUND)
					.entity("Could not find tradition with this id").build();

		try (Transaction tx = db.beginTx()) {
			readingModels = getAllReadingsFromTraditionBetweenRanks(startNode,
					startRank, endRank, db);
			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		ArrayList<List<ReadingModel>> identicalReadings = new ArrayList<List<ReadingModel>>();
		identicalReadings = getIdenticalReadingsAsList(readingModels,
				startRank, endRank);

		Boolean isEmpty = true;
		for (List<ReadingModel> list : identicalReadings) {
			if (list.size() > 0)
				isEmpty = false;
		}
		if (isEmpty)
			return Response.status(Status.NOT_FOUND)
					.entity("no identical readings were found").build();
		db.shutdown();
		return Response.ok(identicalReadings).build();
	}

	/**
	 * Returns a list of a list of readingModels with could be one the same rank
	 * without problems
	 * 
	 * @param tradId
	 * @param startRank
	 * @param endRank
	 * @return
	 */
	@GET
	@Path("couldbeidenticalreadings/fromtradition/{tradId}/fromstartrank/{startRank}/toendrank/{endRank}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getCouldBeIdenticalReadings(
			@PathParam("tradId") String tradId,
			@PathParam("startRank") long startRank,
			@PathParam("endRank") long endRank) {

		GraphDatabaseService db = dbFactory.newEmbeddedDatabase(DB_PATH);
		ArrayList<ArrayList<ReadingModel>> couldBeIdenticalReadings = new ArrayList<ArrayList<ReadingModel>>();
		Node startNode = DatabaseService.getStartNode(tradId, db);
		if (startNode == null)
			return Response.status(Status.NOT_FOUND)
					.entity("Could not find tradition with this id").build();

		try (Transaction tx = db.beginTx()) {
			ArrayList<Node> questionedReadings = getReadingsBetweenRanks(
					startRank, endRank, db, startNode);

			couldBeIdenticalReadings = getCouldBeIdenticalAsList(
					questionedReadings, db);
			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		if (couldBeIdenticalReadings.size() == 0)
			return Response.status(Status.NOT_FOUND)
					.entity("no identical readings were found").build();
		db.shutdown();
		return Response.ok(couldBeIdenticalReadings).build();
	}

	/**
	 * Makes separate List for every group of Readings with identical text and
	 * different ranks and send the list for further test
	 * 
	 * @param questionedReadings
	 * @param db
	 * @return
	 */
	private ArrayList<ArrayList<ReadingModel>> getCouldBeIdenticalAsList(
			ArrayList<Node> questionedReadings, GraphDatabaseService db) {

		ArrayList<ArrayList<ReadingModel>> couldBeIdenticalReadings = new ArrayList<ArrayList<ReadingModel>>();

		for (Node nodeA : questionedReadings) {
			ArrayList<Node> sameText = new ArrayList<Node>();
			for (Node nodeB : questionedReadings) {
				if (nodeA.getProperty("dn15").toString()
						.equals(nodeB.getProperty("dn15").toString())
						&& !nodeA.equals(nodeB)
						&& !nodeA.getProperty("dn14").toString()
								.equals(nodeB.getProperty("dn14").toString())) {
					sameText.add(nodeB);
					sameText.add(nodeA);
				}
			}
				if (sameText.size() > 0)
					couldBeIdenticalCheck(sameText, couldBeIdenticalReadings,
							db);
		}
		return couldBeIdenticalReadings;
	}

	/**
	 * Adds all the words that could be on the same rank to the result list
	 * 
	 * @param sameText
	 * @param couldBeIdenticalReadings
	 * @param db
	 */
	private void couldBeIdenticalCheck(ArrayList<Node> sameText,
			ArrayList<ArrayList<ReadingModel>> couldBeIdenticalReadings,
			GraphDatabaseService db) {

		ArrayList<ReadingModel> couldBeIdentical = new ArrayList<ReadingModel>();

		for (int i = 0; i < sameText.size() - 1; i++) {
			Node biggerRankNode;
			Node smallerRankNode;
			long rankA = (long) sameText.get(i).getProperty("dn14");
			long rankB = (long) sameText.get(i + 1).getProperty("dn14");
			long biggerRank, smallerRank;

			if (rankA < rankB) {
				biggerRankNode = sameText.get(i + 1);
				smallerRankNode = sameText.get(i);
				smallerRank = rankA;
				biggerRank = rankB;
			} else {
				biggerRankNode = sameText.get(i);
				smallerRankNode = sameText.get(i + 1);
				smallerRank = rankB;
				biggerRank = rankA;
			}

			long rank = 0;
			boolean gotOne = false;

			Iterable<Relationship> rels = smallerRankNode.getRelationships(
					Direction.OUTGOING, ERelations.NORMAL);

			for (Relationship rel : rels) {
				rank = (long) rel.getEndNode().getProperty("dn14");
				if (rank <= biggerRank) {
					gotOne = true;
					break;
				}
			}

			if (gotOne) {
				rank = 0;
				gotOne = false;

				Iterable<Relationship> rels2 = biggerRankNode.getRelationships(
						Direction.INCOMING, ERelations.NORMAL);

				for (Relationship rel : rels2) {
					rank = (long) rel.getStartNode().getProperty("dn14");
					if (rank >= smallerRank) {
						gotOne = true;
						break;
					}
				}
			}
			if (!gotOne) {
				if (!couldBeIdentical
						.contains(new ReadingModel(smallerRankNode)))
					couldBeIdentical.add(new ReadingModel(smallerRankNode));
				if (!couldBeIdentical
						.contains(new ReadingModel(biggerRankNode)))
					couldBeIdentical.add(new ReadingModel(biggerRankNode));
			}
			if (couldBeIdentical.size() > 0)
				couldBeIdenticalReadings.add(couldBeIdentical);
		}
	}

	private ArrayList<Node> getReadingsBetweenRanks(long startRank,
			long endRank, GraphDatabaseService db, Node startNode) {
		ArrayList<Node> readings = new ArrayList<Node>();

		for (Node node : db.traversalDescription().breadthFirst()
				.relationships(ERelations.NORMAL, Direction.OUTGOING)
				.uniqueness(Uniqueness.NODE_GLOBAL).traverse(startNode).nodes()) {
			if ((Long) node.getProperty("dn14") > startRank
					&& (Long) node.getProperty("dn14") < endRank)
				readings.add(node);

		}
		return readings;
	}

	/**
	 * gets identical readings in a tradition between the given ranks
	 * 
	 * @param readingModels
	 *            list of all readings sorted according to rank
	 * @param startRank
	 * @param endRank
	 * @return list of the identical readings as readingModels
	 */
	private ArrayList<List<ReadingModel>> getIdenticalReadingsAsList(
			ArrayList<ReadingModel> readingModels, long startRank, long endRank) {
		ArrayList<List<ReadingModel>> identicalReadingsList = new ArrayList<List<ReadingModel>>();

		for (int i = 0; i <= readingModels.size() - 2; i++) {
			while (readingModels.get(i).getDn14() == readingModels.get(i + 1)
					.getDn14() && i + 1 < readingModels.size()) {
				ArrayList<ReadingModel> identicalReadings = new ArrayList<ReadingModel>();

				if (readingModels.get(i).getDn15()
						.equals(readingModels.get(i + 1).getDn15())
						&& readingModels.get(i).getDn14() < endRank
						&& readingModels.get(i).getDn14() > startRank) {
					identicalReadings.add(readingModels.get(i));
					identicalReadings.add(readingModels.get(i + 1));
				}
				identicalReadingsList.add(identicalReadings);
				i++;
			}
		}
		return identicalReadingsList;
	}

	/**
	 * compress two readings into one
	 * 
	 * @param tradId
	 * @param readId1
	 * @param readId2
	 * @return confirmation that the operation was completed
	 */
	@POST
	@Path("compressreadings/fromtradition/{tradId}/readingone/{readId1}/readingtwo/{readId2}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response compressReadings(@PathParam("tradId") String tradId,
			@PathParam("readId1") long readId1,
			@PathParam("readId2") long readId2) {
		GraphDatabaseService db = dbFactory.newEmbeddedDatabase(DB_PATH);
		Node read1, read2;
		errorMessage = "problem with a reading. could not compress";

		try (Transaction tx = db.beginTx()) {
			read1 = db.getNodeById(readId1);
			read2 = db.getNodeById(readId2);
			if ((long) read1.getProperty("dn14") > (long) read2
					.getProperty("dn14"))
				swapReadings(read1, read2);

			if (canCompress(read1, read2, db)) {
				compress(read1, read2, db);
				tx.success();
				return Response.ok("successfully compressed readings").build();
			}
			tx.success();
		}
		catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		return Response.status(Status.INTERNAL_SERVER_ERROR)
				.entity(errorMessage).build();
	}

	/**
	 * compress two readings
	 * 
	 * @param read1
	 *            the first reading
	 * @param read2
	 *            the second reading
	 */
	private void compress(Node read1, Node read2, GraphDatabaseService db) {
		String textRead1 = (String) read1.getProperty("dn15");
		String textRead2 = (String) read2.getProperty("dn15");
		read1.setProperty("dn15", textRead1 + " " + textRead2);

		Relationship from1to2 = getRealtionshipBetweenReadings(read1, read2, db);
		from1to2.delete();
		copyRelationships(read1, read2);
		read2.delete();
	}

	/**
	 * copy all NORMAL relationship from one node to another IMPORTANT: when
	 * called needs to be inside a try-catch
	 * 
	 * @param read1
	 *            the node which receives the relationships
	 * @param read2
	 *            the node from which relationships are copied
	 */
	private void copyRelationships(Node read1, Node read2) {
		for (Relationship tempRel : read2.getRelationships(Direction.OUTGOING)) {
			Node tempNode = tempRel.getOtherNode(read2);
			Relationship rel1 = read1.createRelationshipTo(tempNode,
					ERelations.NORMAL);
			for (String key : tempRel.getPropertyKeys()) {
				rel1.setProperty(key, tempRel.getProperty(key));
			}
			tempRel.delete();
		}

		for (Relationship tempRel : read2.getRelationships(Direction.INCOMING)) {
			Node tempNode = tempRel.getOtherNode(read2);
			Relationship rel1 = tempNode.createRelationshipTo(read1,
					ERelations.NORMAL);
			for (String key : tempRel.getPropertyKeys()) {
				rel1.setProperty(key, tempRel.getProperty(key));
			}
			tempRel.delete();
		}
	}

	/**
	 * checks if two readings could be compressed
	 * 
	 * @param read1
	 *            the first reading
	 * @param read2
	 *            the second reading
	 * @return true if ok to compress, false otherwise
	 */
	private boolean canCompress(Node read1, Node read2, GraphDatabaseService db) {
		Iterable<Relationship> rel;
		rel = read2.getRelationships(ERelations.NORMAL);

		Iterator<Relationship> normalFromRead2 = rel.iterator();
		if (!normalFromRead2.hasNext()) {
			errorMessage = "second readings is not connected. could not compress";
			return false;
		}
		Relationship from1to2 = getRealtionshipBetweenReadings(read1, read2, db);
		if (from1to2 == null) {
			errorMessage = "reading are not neighbors. could not compress";
			return false;
		}

		if (hasNotNormalRealtionships(read1, db)
				|| hasNotNormalRealtionships(read2, db)) {
			errorMessage = "reading has other relations. could not compress";
			return false;
		}
		return true;
	}

	/**
	 * checks if a reading has relationships which are not NORMAL
	 * 
	 * @param read
	 *            the reading
	 * @param db
	 *            the data base
	 * @return true if it has, false otherwise
	 */
	private boolean hasNotNormalRealtionships(Node read, GraphDatabaseService db) {
		String type = "", normal = "";

		for (Relationship rel : read.getRelationships()) {
			type = rel.getType().name();
			normal = ERelations.NORMAL.toString();

			if (!type.equals(normal))
				return true;
		}
		return false;
	}

	/**
	 * get the normal relationship between two readings
	 * 
	 * @param read1
	 *            the first reading
	 * @param read2
	 *            the second reading
	 * @return the NORMAL relationship
	 */
	private Relationship getRealtionshipBetweenReadings(Node read1, Node read2,
			GraphDatabaseService db) {
		Relationship from1to2 = null;
		for (Relationship tempRel : read1.getRelationships()) {
			if (tempRel.getOtherNode(read1).equals(read2)) {
				from1to2 = tempRel;
			}
		}
		return from1to2;
	}

	private void swapReadings(Node read1, Node read2) {
		Node tempRead = read1;
		read1 = read2;
		read2 = tempRead;
	}
}