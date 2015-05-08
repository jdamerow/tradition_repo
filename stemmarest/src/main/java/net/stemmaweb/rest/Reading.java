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
import net.stemmaweb.model.ReadingChangePropertyModel;
import net.stemmaweb.model.ReadingModel;
import net.stemmaweb.model.GraphModel;
import net.stemmaweb.model.RelationshipModel;
import net.stemmaweb.services.DatabaseService;
import net.stemmaweb.services.EvaluatorService;
import net.stemmaweb.services.GraphDatabaseServiceProvider;
import net.stemmaweb.services.ReadingService;
import net.stemmaweb.services.RelationshipService;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.traversal.Evaluator;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.Uniqueness;

/**
 * Comprises all Rest API calls related to a reading.
 * Can be called via http://BASE_URL/reading
 * 
 * @author PSE FS 2015 Team2
 */

@Path("/reading")
public class Reading implements IResource {

	private String errorMessage; // global error message used for submethod calls
	
	GraphDatabaseServiceProvider dbServiceProvider = new GraphDatabaseServiceProvider();
	GraphDatabaseService db = dbServiceProvider.getDatabase();

	/**
	 * Returns a single reading by global neo4j id
	 * 
	 * @param readId
	 * @return
	 */
	@GET
	@Path("getreading/withreadingid/{readId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getReading(@PathParam("readId") long readId) {

		ReadingModel reading = null;
		Node readingNode;
		try (Transaction tx = db.beginTx()) {
			readingNode = db.getNodeById(readId);

			reading = new ReadingModel(readingNode);

			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		} finally {

		}
		return Response.ok(reading).build();
	}

	/**
	 * Changes properties of a reading according to its keys
	 * 
	 * @param readId
	 *            the id of the reading to be changed
	 * @param changeModels
	 *            an array of changeReadingModel objects. Will be converted from a json
	 *            string. Example: a json string for an array size 1 which
	 *            should change the value of 'language' to 'german' will look like
	 *            this:[{\"key\":\"language\",\"newProperty\":\"german\"}]
	 * @return ok response with the modified reading as json
	 */
	@POST
	@Path("changeproperties/ofreading/{readId}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response changeReadingProperties(@PathParam("readId") long readId,
			ReadingChangePropertyModel[] changeModels) {
		ReadingModel modelToReturn = new ReadingModel();
		Node reading;
		try (Transaction tx = db.beginTx()) {
			reading = db.getNodeById(readId);
			for (ReadingChangePropertyModel keyCheckModel : changeModels) {
				if (!reading.hasProperty(keyCheckModel.getKey()))
					return Response
							.status(Status.INTERNAL_SERVER_ERROR)
							.entity("the reading does not have such property: '"
									+ keyCheckModel.getKey()
									+ "'. no changes to the reading have been done")
							.build();
			}
				for (ReadingChangePropertyModel changeModel : changeModels) {
					reading.setProperty(changeModel.getKey(),
							changeModel.getNewProperty());
				}			
			modelToReturn = new ReadingModel(reading);
			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		return Response.status(Response.Status.OK).entity(modelToReturn)
				.build();
	}

	/**
	 * Duplicates a reading in a specific tradition. Opposite of merge
	 * 
	 * @param duplicateModel
	 * @return a readingsAndRelationshipsModel in JSON containing all the
	 *         created readings and the deleted relationships on success or an
	 *         ERROR as JSON else
	 */
	@POST
	@Path("duplicatereading")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response duplicateReading(DuplicateModel duplicateModel) {

		ArrayList<ReadingModel> createdReadings = new ArrayList<ReadingModel>();
		ArrayList<RelationshipModel> deletedRelationships = null;

		Node originalReading = null;

		try (Transaction tx = db.beginTx()) {
			List<Long> readings = duplicateModel.getReadings();
			for (Long readId : readings) {
				originalReading = db.getNodeById(readId);
				List<String> newWitnesses = duplicateModel.getWitnesses();

				if (!canBeDuplicated(originalReading, newWitnesses))
					return Response.status(Status.INTERNAL_SERVER_ERROR)
							.entity(errorMessage).build();

				Node newNode = db.createNode();
				deletedRelationships = duplicate(newWitnesses, originalReading, newNode);
				createdReadings.add(new ReadingModel(newNode));
			}

			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		GraphModel readingsAndRelationships = new GraphModel(createdReadings,
				deletedRelationships);
		return Response.ok(readingsAndRelationships).build();
	}

	/**
	 * Checks if the reading can be duplicated for the given witness list
	 * 
	 * @param originalReading
	 * @param newWitnesses
	 * @return true if specific reading can be duplicated, false else
	 */
	private boolean canBeDuplicated(Node originalReading,
			List<String> newWitnesses) {
		List<String> allWitnesses = allWitnessesOfReading(originalReading);

		if (newWitnesses.isEmpty()) {
			errorMessage = "The witness list has to contain at least one witness";
			return false;
		}

		for (String newWitness : newWitnesses)
			if (!allWitnesses.contains(newWitness)) {
				errorMessage = "The reading has to be in the witnesses to be duplicated";
				return false;
			}

		if (allWitnesses.size() < 2) {
			errorMessage = "The reading has to be in at least two witnesses";
			return false;
		}

		return true;
	}

	/**
	 * Gets all witnesses of a reading in all its normal relationships.
	 * 
	 * @param originalReading
	 * @return the list of witnesses of a reading
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
	 * @param newWitnesses: the new witnesses to be split from the original path
	 * @param originalReading: the original witnesses of the original path
	 * @param addedReading: the duplicated reading
	 * @return a list of the deleted relationships
	 */
	private ArrayList<RelationshipModel> duplicate(List<String> newWitnesses, Node originalReading,
			Node addedReading) {
		ArrayList<RelationshipModel> deletedRelationships = new ArrayList<RelationshipModel>();
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
			deletedRelationships.addAll(transferNewWitnessesFromOriginalReadingToAddedReading(newWitnesses,
					originalRelationship, originalRelationship.getStartNode(),
					addedReading));
		// Outgoing
		for (Relationship originalRelationship : originalReading
				.getRelationships(ERelations.NORMAL, Direction.OUTGOING))
			deletedRelationships.addAll(transferNewWitnessesFromOriginalReadingToAddedReading(newWitnesses,
					originalRelationship, addedReading,
					originalRelationship.getEndNode()));
		
		return deletedRelationships;
	}

	/**
	 * Transfers all the new witnesses from the relationships of the original
	 * reading to the relationships of the newly added reading.
	 * 
	 * @param newWitnesses
	 * @param originalRel
	 * @param originNode
	 * @param targetNode
	 * @return a list of the deleted edges
	 */
	private ArrayList<RelationshipModel> transferNewWitnessesFromOriginalReadingToAddedReading(
			List<String> newWitnesses, Relationship originalRel,
			Node originNode, Node targetNode) {
		ArrayList<RelationshipModel> deletedRelationships = new ArrayList<RelationshipModel>();
		String[] oldWitnesses = (String[]) originalRel.getProperty("lexemes");
		// if oldWitnesses only contains one witness and this one should be
		// duplicated, create new relationship for addedReading and delete
		// the one from the originalReading
		if (oldWitnesses.length == 1) {
			if (newWitnesses.contains(oldWitnesses[0])) {
				Relationship newRel = originNode.createRelationshipTo(
						targetNode, ERelations.NORMAL);
				newRel.setProperty("lexemes", oldWitnesses);
				deletedRelationships.add(new RelationshipModel(originalRel));
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

			if (stayingWitnesses.isEmpty()) {
				deletedRelationships.add(new RelationshipModel(originalRel));
				originalRel.delete();
			}
			else
				originalRel.setProperty("lexemes", stayingWitnesses
						.toArray(new String[stayingWitnesses.size()]));
		}

		return deletedRelationships;
	}

	/**
	 * Merges two readings into one single reading in a specific tradition.
	 * Opposite of duplicate
	 * 
	 * @param firstReadId
	 * @param secondReadId
	 * @return Status.ok if merge was successful. Status.INTERNAL_SERVER_ERROR
	 *         with a detailed message if not
	 */
	@POST
	@Path("mergereadings/first/{firstReadId}/second/{secondReadId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response mergeReadings(@PathParam("firstReadId") long firstReadId,
			@PathParam("secondReadId") long secondReadId) {

		Node stayingReading = null;
		Node deletingReading = null;

		try (Transaction tx = db.beginTx()) {
			stayingReading = db.getNodeById(firstReadId);
			deletingReading = db.getNodeById(secondReadId);

			if (!canBeMerged(db, stayingReading, deletingReading))
				return Response.status(Status.INTERNAL_SERVER_ERROR)
						.entity(errorMessage).build();
			merge(stayingReading, deletingReading);

			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		} finally {

		}
		return Response.ok().build();
	}

	/**
	 * Checks if the two readings can be merged or not.
	 * 
	 * @param db
	 * @param stayingReading
	 * @param deletingReading
	 * @return true if readings can be merged
	 */
	private boolean canBeMerged(GraphDatabaseService db,
			Node stayingReading, Node deletingReading) {
		if (!doContainSameText(stayingReading, deletingReading)) {
			errorMessage = "Readings to be merged do not contain the same text";
			return false;
		}

		if (!doContainRelationshipBetweenEachOther(stayingReading,
				deletingReading)) {
			errorMessage = "Readings to be merged have to be connected with each other through a relationship";
			return false;
		}

		if (containClassTwoRelationships(stayingReading, deletingReading)) {
			errorMessage = "Readings to be merged cannot contain class 2 relationships (transposition / repetition)";
			return false;
		}

		if (ReadingService.wouldGetCyclic(db, stayingReading, deletingReading)) {
			errorMessage = "Readings to be merged would make the graph cyclic";
			return false;
		}

		return true;
	}

	/**
	 * Checks if the two readings contain the same text or not.
	 * 
	 * @param stayingReading
	 * @param deletingReading
	 * @return true if they contain the same text
	 */
	private boolean doContainSameText(Node stayingReading,
			Node deletingReading) {
		return stayingReading.getProperty("text").toString()
				.equals(deletingReading.getProperty("text").toString());
	}

	/**
	 * Checks if the two readings have a relationship between them.
	 * 
	 * @param stayingReading
	 * @param deletingReading
	 * @return check whether two readings contain a relationship between them
	 */
	private boolean doContainRelationshipBetweenEachOther(
			Node stayingReading, Node deletingReading) {
		for (Relationship firstRel : stayingReading
				.getRelationships(ERelations.RELATIONSHIP))
			for (Relationship secondRel : deletingReading
					.getRelationships(ERelations.RELATIONSHIP))
				if (firstRel.equals(secondRel))
					return true;
		return false;
	}

	/**
	 * Checks if the two readings have a relationship between them which
	 * is of class two (transposition / repetition).
	 * 
	 * @param stayingReading
	 * @param deletingReading
	 * @return true if a relationship between two readings is of class 2
	 */
	private boolean containClassTwoRelationships(Node stayingReading,
			Node deletingReading) {
		for (Relationship stayingRel : stayingReading
				.getRelationships(ERelations.RELATIONSHIP))
			if (stayingRel.getOtherNode(stayingReading).equals(deletingReading))
				if (stayingRel.getProperty("type").equals("transposition")
						|| stayingRel.getProperty("type").equals("repetition"))
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
	 * Adds relationships from deletedReading to staying reading.
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
	 * @param readId
	 * @param separator
	 *            the string which is between the words to be split, default:
	 *            whitespace
	 * @param splitIndex
	 *            the index of the first letter of second word: "unto" with
	 *            index 2 gets "un" and "to" if the index is zero the reading is
	 *            split using the separator
	 * @return a readingsAndRelationshipsModel in JSON containing all the
	 *         created and modified readings and the deleted relationships on
	 *         success or an ERROR as JSON
	 */
	@POST
	@Path("splitreading/ofreading/{readId}/withseparator/{separator}/withsplitindex/{splitIndex}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response splitReading(@PathParam("readId") long readId,
			@PathParam("separator") String separator,
			@PathParam("splitIndex") int splitIndex) {

		GraphModel readingsAndRelationships = null;
		Node originalReading = null;

		try (Transaction tx = db.beginTx()) {
			originalReading = db.getNodeById(readId);
			String originalText = originalReading.getProperty("text")
					.toString();
			String[] splittedWords;
			if (splitIndex > 0) {
				if (splitIndex >= originalText.length())
					return Response
							.status(Status.INTERNAL_SERVER_ERROR)
							.entity("The splitIndex must be smaller than the text length")
							.build();
				splittedWords = new String[2];
				splittedWords[0] = originalText.substring(0, splitIndex);
				splittedWords[1] = originalText.substring(splitIndex);
			} else {
				if (separator == null || separator.equals("")
						|| separator.equals("0"))
					separator = "\\s+";
				splittedWords = originalText.split(separator);
			}

			if (cannotBeSplitted(originalReading, splittedWords))
				return Response.status(Status.INTERNAL_SERVER_ERROR)
						.entity(errorMessage).build();

			readingsAndRelationships = split(db, originalReading, splittedWords);

			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();

		}
		return Response.ok(readingsAndRelationships).build();
	}

	/**
	 * Checks if a reading can be split or not.
	 * 
	 * @param originalReading
	 * @param splitWords
	 * @return
	 */
	private boolean cannotBeSplitted(Node originalReading,
			String[] splitWords) {
		if (splitWords.length < 2) {
			errorMessage = "A reading to be split has to contain at least 2 words";
			return true;
		}

		if (originalReading.hasRelationship(ERelations.RELATIONSHIP)) {
			errorMessage = "A reading to be split cannot be part of any relationship";
			return true;
		}

		if (!hasRankGap(originalReading, splitWords.length)) {
			errorMessage = "There has to be a rank-gap after a reading to be split";
			return true;
		}

		return false;
	}

	/**
	 * Checks if there is a rank gap after the reading to be split. The rank
	 * gap has to have at least the size of the readings words. E.g. if the
	 * reading is "the little mouse" and has rank 5 the next reading has to have
	 * at least rank 8.
	 * 
	 * @param originalReading
	 * @param numberOfWords
	 * @return true if there is a rank gap
	 */
	private boolean hasRankGap(Node originalReading, int numberOfWords) {
		String rankKey = "rank";
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
	 * @return a model of the split graph
	 */
	private GraphModel split(GraphDatabaseService db,
			Node originalReading, String[] splittedWords) {
		ArrayList<ReadingModel> createdOrChangedReadings = new ArrayList<ReadingModel>();
		ArrayList<RelationshipModel> deletedRelationships = new ArrayList<RelationshipModel>();

		Iterable<Relationship> originalOutgoingRels = originalReading
				.getRelationships(ERelations.NORMAL, Direction.OUTGOING);
		ArrayList<String> allWitnesses = new ArrayList<String>();
		for (Relationship relationship : originalReading.getRelationships(
				ERelations.NORMAL, Direction.INCOMING)) {
			String[] witnesses = (String[]) relationship.getProperty("lexemes");
			for (int j = 0; j < witnesses.length; j++)
				allWitnesses.add(witnesses[j]);

		}
		originalReading.setProperty("text", splittedWords[0]);

		createdOrChangedReadings.add(new ReadingModel(originalReading));

		Node lastReading = originalReading;

		for (int i = 1; i < splittedWords.length; i++) {
			Node newReading = db.createNode();

			newReading = ReadingService.copyReadingProperties(lastReading,
					newReading);
			newReading.setProperty("text", splittedWords[i]);
			Long previousRank = (Long) lastReading.getProperty("rank");
			newReading.setProperty("rank", previousRank + 1);

			Relationship relationship = lastReading.createRelationshipTo(
					newReading, ERelations.NORMAL);
			relationship.setProperty("lexemes",
					allWitnesses.toArray(new String[allWitnesses.size()]));

			lastReading = newReading;
			createdOrChangedReadings.add(new ReadingModel(newReading));
		}
		for (Relationship oldRel : originalOutgoingRels) {
			Relationship newRel = lastReading.createRelationshipTo(
					oldRel.getEndNode(), ERelations.NORMAL);
			newRel = RelationshipService.copyRelationshipProperties(oldRel,
					newRel);
			deletedRelationships.add(new RelationshipModel(oldRel));
			oldRel.delete();
		}

		return new GraphModel(createdOrChangedReadings, deletedRelationships);
	}

	/**
	 * gets the next readings from a given readings in the same witness
	 * 
	 * @param witnessId
	 *            : the id (name) of the witness 
	 * @param readId
	 *            : the id of the reading 
	 * 
	 * @return the requested reading as json ok response
	 */
	@GET
	@Path("getnextreading/fromwitness/{witnessId}/ofreading/{readId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getNextReadingInWitness(
			@PathParam("witnessId") String witnessId,
			@PathParam("readId") long readId) {

		final String WITNESS_ID = witnessId;

		Evaluator witnessEvaluator = EvaluatorService
				.getEvalForWitness(WITNESS_ID);

		try (Transaction tx = db.beginTx()) {
			Node reading = db.getNodeById(readId);

			for (Node node : db.traversalDescription().depthFirst()
					.relationships(ERelations.NORMAL, Direction.OUTGOING)
					.evaluator(witnessEvaluator)
					.evaluator(Evaluators.toDepth(1))
					.uniqueness(Uniqueness.NONE).traverse(reading).nodes()) {

				if (!new ReadingModel(node).getText().equals("#END#"))
					return Response.ok(new ReadingModel(node)).build();
				else
					return Response
							.status(Status.NOT_FOUND)
							.entity("this was the last reading of this witness")
							.build();
			}
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		} finally {

		}
		return Response.status(Status.NOT_FOUND)
				.entity("given readings not found").build();
	}

	/**
	 * gets the next readings from a given readings in the same witness
	 * 
	 * @param witnessId
	 *            : the id (name) of the witness
	 * @param readId
	 *            : the id of the reading
	 * 
	 * @return the requested reading as json ok response
	 */
	@GET
	@Path("getpreviousreading/fromwitness/{witnessId}/ofreading/{readId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getPreviousReadingInWitness(
			@PathParam("witnessId") String witnessId,
			@PathParam("readId") long readId) {

		final String WITNESS_ID = witnessId;

		Evaluator wintessEvaluator = EvaluatorService
				.getEvalForWitness(WITNESS_ID);

		try (Transaction tx = db.beginTx()) {
			Node read = db.getNodeById(readId);

			for (Node node : db.traversalDescription().depthFirst()
					.relationships(ERelations.NORMAL, Direction.INCOMING)
					.evaluator(wintessEvaluator)
					.evaluator(Evaluators.toDepth(1))
					.uniqueness(Uniqueness.NONE).traverse(read).nodes()) {

				if (!new ReadingModel(node).getText().equals("#START#"))
					return Response.ok(new ReadingModel(node)).build();
				else
					return Response
							.status(Status.NOT_FOUND)
							.entity("there is no previous reading to this reading")
							.build();
			}
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		} finally {

		}
		return Response.status(Status.NOT_FOUND)
				.entity("given readings not found").build();
	}

	/**
	 * Returns a list of all readings in a tradition
	 * @param tradId
	 * @return the list of readings
	 */
	@GET
	@Path("getallreadings/fromtradition/{tradId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getAllReadings(@PathParam("tradId") String tradId) {

		ArrayList<ReadingModel> readingModels = new ArrayList<ReadingModel>();

		Node startNode = DatabaseService.getStartNode(tradId, db);
		if (startNode == null) {
			return Response.status(Status.NOT_FOUND)
					.entity("Could not find tradition with this id").build();
		}
		try (Transaction tx = db.beginTx()) {
			for (Node node : db.traversalDescription().depthFirst()
					.relationships(ERelations.NORMAL, Direction.OUTGOING)
					.evaluator(Evaluators.all()).uniqueness(Uniqueness.NODE_GLOBAL)
					.traverse(startNode).nodes()) {
				ReadingModel tempReading = new ReadingModel(node);
				readingModels.add(tempReading);
			}
			tx.success();
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).build();
		} finally {

		}
		return Response.ok(readingModels).build();
	}

	/**
	 * Get all readings which have the same text and the same rank between given
	 * ranks
	 * 
	 * @param tradId
	 *            the id of the tradition in which to look for identical
	 *            readings
	 * @param startRank
	 *            the rank from where to start the search
	 * @param endRank
	 *            the end rank of the search range
	 * @return a list of lists as a json ok response: each list contain identical readings
	 */
	@GET
	@Path("getidenticalreadings/fromtradition/{tradId}/fromstartrank/{startRank}/toendrank/{endRank}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getIdenticalReadings(@PathParam("tradId") String tradId,
			@PathParam("startRank") long startRank,
			@PathParam("endRank") long endRank) {

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
		} finally {

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

		return Response.ok(identicalReadings).build();
	}
	
	/**
	 * Helper method to retrieve all readings from a tradition
	 * @param startNode
	 * @param startRank
	 * @param endRank
	 * @param db
	 * @return list of readings of a tradition
	 */
	private ArrayList<ReadingModel> getAllReadingsFromTraditionBetweenRanks(
			Node startNode, long startRank, long endRank,
			GraphDatabaseService db) {

		ArrayList<ReadingModel> readingModels = new ArrayList<ReadingModel>();

		for (Node node : db.traversalDescription().depthFirst()
				.relationships(ERelations.NORMAL, Direction.OUTGOING)
				.evaluator(Evaluators.all()).uniqueness(Uniqueness.NODE_GLOBAL)
				.traverse(startNode).nodes()) {
			long nodeRank = (long) node.getProperty("rank");

			if (nodeRank < endRank && nodeRank > startRank) {
				ReadingModel tempReading = new ReadingModel(node);
				readingModels.add(tempReading);
			}
		}
		Collections.sort(readingModels);
		return readingModels;
	}

	/**
	 * Returns a list of a list of readingModels with could be one the same rank
	 * without problems
	 * 
	 * @param tradId
	 * @param startRank
	 * @param endRank
	 * @return list of readings that could be at the same rank
	 */
	@GET
	@Path("couldbeidenticalreadings/fromtradition/{tradId}/fromstartrank/{startRank}/toendrank/{endRank}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getCouldBeIdenticalReadings(
			@PathParam("tradId") String tradId,
			@PathParam("startRank") long startRank,
			@PathParam("endRank") long endRank) {

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
		} finally {

		}
		if (couldBeIdenticalReadings.size() == 0)
			return Response.status(Status.NOT_FOUND)
					.entity("no identical readings were found").build();

		return Response.ok(couldBeIdenticalReadings).build();
	}

	/**
	 * Makes separate lists for every group of readings with identical text and
	 * different ranks and send the list for further test
	 * 
	 * @param questionedReadings
	 * @param db
	 * @return list of lists of identical readings
	 */
	private ArrayList<ArrayList<ReadingModel>> getCouldBeIdenticalAsList(
			ArrayList<Node> questionedReadings, GraphDatabaseService db) {

		ArrayList<ArrayList<ReadingModel>> couldBeIdenticalReadings = new ArrayList<ArrayList<ReadingModel>>();

		for (Node nodeA : questionedReadings) {
			ArrayList<Node> sameText = new ArrayList<Node>();
			for (Node nodeB : questionedReadings) {
				if (nodeA.getProperty("text").toString()
						.equals(nodeB.getProperty("text").toString())
						&& !nodeA.equals(nodeB)
						&& !nodeA.getProperty("rank").toString()
								.equals(nodeB.getProperty("rank").toString())) {
					sameText.add(nodeB);
					sameText.add(nodeA);
				}
			}
			if (sameText.size() > 0)
				couldBeIdenticalCheck(sameText, couldBeIdenticalReadings, db);
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
			long rankA = (long) sameText.get(i).getProperty("rank");
			long rankB = (long) sameText.get(i + 1).getProperty("rank");
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
				rank = (long) rel.getEndNode().getProperty("rank");
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
					rank = (long) rel.getStartNode().getProperty("rank");
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
			if ((Long) node.getProperty("rank") > startRank
					&& (Long) node.getProperty("rank") < endRank)
				readings.add(node);

		}
		return readings;
	}

	/**
	 * Gets identical readings in a tradition between the given ranks
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
			while (readingModels.get(i).getRank() == readingModels.get(i + 1)
					.getRank() && i + 1 < readingModels.size()) {
				ArrayList<ReadingModel> identicalReadings = new ArrayList<ReadingModel>();

				if (readingModels.get(i).getText()
						.equals(readingModels.get(i + 1).getText())
						&& readingModels.get(i).getRank() < endRank
						&& readingModels.get(i).getRank() > startRank) {
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
	 * Compress two readings into one. Texts will be concatenated together (with
	 * or without a space or extra text. The reading with the lower rank will
	 * be given first. Opposite of split
	 * 
	 * @param readId1
	 *            the id of the first reading
	 * @param readId2
	 *            the id of the second reading
	 * @param con
	 *            concatenate must be 0 (for 'no') or 1 (for 'yes'). if
	 *            concatenate is set to 1, the compressing will be done with
	 *            conString between the texts of the readings. If it is 0, texts
	 *            will be concatenate with a single space. If con is 1 and
	 *            conString is a single space, the texts will be concatenated
	 *            without a gap
	 * @param conString
	 *            the string which will come between the texts of the readings
	 *            if con is set to 1 could also be an empty string
	 * 
	 * @return status.ok if compress was successful.
	 *         Status.INTERNAL_SERVER_ERROR with a detailed message if not concatenated
	 */
	@POST
	@Path("compressreadings/read1id/{read1Id}/read2id/{read2Id}/concatenate/{con}/with_str/{conString}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response compressReadings(@PathParam("read1Id") long readId1,
			@PathParam("read2Id") long readId2, @PathParam("con") String con,
			@PathParam("conString") String conString) {

		Node read1, read2;
		errorMessage = "problem with a reading. could not compress";
		boolean toConcatenate;
		if (con.equals("0"))
			toConcatenate = false;
		else if (con.equals("1"))
			toConcatenate = true;
		else
			return Response.status(Status.INTERNAL_SERVER_ERROR)
					.entity("argument concatenate has an invalid value")
					.build();

		try (Transaction tx = db.beginTx()) {
			read1 = db.getNodeById(readId1);
			read2 = db.getNodeById(readId2);
			if ((long) read1.getProperty("rank") > (long) read2
					.getProperty("rank"))
				return Response
						.status(Status.INTERNAL_SERVER_ERROR)
						.entity("the first reading has a higher rank then the second reading")
						.build();
			if (canCompress(read1, read2, db)) {
				compress(read1, read2, toConcatenate, conString, db);
				tx.success();
				return Response.ok().build();
			}
			tx.success();
		} catch (Exception e) {
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
	 * @param conString
	 *            the string to come between the texts of the readings.
	 * @param toConcatenate
	 *            boolean: if true - texts of readings will be concatenated with
	 *            conString in between if false: texts of readings will be
	 *            concatenated with one empty space in between. If true and
	 *            conString is a single space, the texts will be concatenated
	 *            without a gap
	 */
	private void compress(Node read1, Node read2, boolean toConcatenate,
			String conString, GraphDatabaseService db) {
		String textRead1 = (String) read1.getProperty("text");
		String textRead2 = (String) read2.getProperty("text");
		if (!toConcatenate)
			read1.setProperty("text", textRead1 + " " + textRead2);
		else if (conString.equals(" "))
			read1.setProperty("text", textRead1 + textRead2);
		else
			read1.setProperty("text", textRead1 + conString + textRead2);

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
}