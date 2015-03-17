package net.stemmaweb.rest;

import java.util.ArrayList;
import java.util.Iterator;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import net.stemmaweb.model.ReadingModel;
import net.stemmaweb.services.DbPathProblemService;

import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.cypher.javacompat.ExecutionResult;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Traverser;

import Exceptions.DataBaseException;

/**
 * 
 * @author jakob/ido
 *
 **/
@Path("/witness")
public class Witness implements IResource {
	private static GraphDatabaseService db = null;

	/**
	 * find a requested witness in the data base and return it as a string
	 * 
	 * @param userId
	 *            : the id of the user who owns the witness
	 * @param traditionName
	 *            : the name of the tradition which the witness is in
	 * @param textId
	 *            : the id of the witness
	 * @return a witness as a string
	 * @throws DataBaseException
	 */
	@GET
	@Path("string/{tradId}/{textId}")
	@Produces("text/plain")
	public String getWitnssAsPlainText(@PathParam("tradId") String tradId,
			@PathParam("textId") String textId) throws DataBaseException {

		db = new GraphDatabaseFactory().newEmbeddedDatabase(DB_PATH);
		String witnessAsText = "";
		final String WITNESS_ID = textId;
		ArrayList<ReadingModel> readingModels = new ArrayList<ReadingModel>();

		Node witnessNode = getStartNode(tradId);
		readingModels = getAllReadingsOfWitness(WITNESS_ID, witnessNode);

		for (ReadingModel readingModel : readingModels) {
			witnessAsText += readingModel.getDn15() + " ";
		}
		db.shutdown();
		return witnessAsText.trim();
	}

	/**
	 * find a requested witness in the data base and return it as a string
	 * according to define start and end readings
	 * 
	 * @param userId
	 *            : the id of the user who owns the witness
	 * @param traditionName
	 *            : the name of the tradition which the witness is in
	 * @param textId
	 *            : the id of the witness
	 * @return a witness as a string
	 * @throws DataBaseException
	 */
	@GET
	@Path("string/rank/{tradId}/{textId}/{startRank}/{endRank}")
	@Produces("text/plain")
	public Response getWitnssAsPlainText(@PathParam("tradId") String tradId,
			@PathParam("textId") String textId,
			@PathParam("startRank") String startRank,
			@PathParam("endRank") String endRank) {
		db = new GraphDatabaseFactory().newEmbeddedDatabase(DB_PATH);
		String witnessAsText = "";
		final String WITNESS_ID = textId;
		ArrayList<ReadingModel> readingModels = new ArrayList<ReadingModel>();

		Node witnessNode = getStartNode(tradId);

		readingModels = getAllReadingsOfWitness(WITNESS_ID, witnessNode);

		int includeReading = 0;
		for (ReadingModel readingModel : readingModels) {
			if (readingModel.getDn14().equals(startRank))
				includeReading = 1;
			if (readingModel.getDn14().equals(endRank)) {
				witnessAsText += readingModel.getDn15();
				includeReading = 0;
			}
			if (includeReading == 1)
				witnessAsText += readingModel.getDn15() + " ";
		}
		db.shutdown();
		if (witnessAsText.equals(""))
			return Response.status(Status.NOT_FOUND).build();
		return Response.ok(witnessAsText.trim()).build();
	}

	/**
	 * finds a witness in data base and return it as a list of readings
	 * 
	 * @param userId
	 *            : the id of the user who owns the witness
	 * @param traditionName
	 *            : the name of the tradition which the witness is in
	 * @param textId
	 *            : the id of the witness
	 * @return a witness as a list of readings
	 * @throws DataBaseException
	 */
	@Path("list/{tradId}/{textId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getWitnessAsReadings(@PathParam("tradId") String tradId,
			@PathParam("textId") String textId) {
		final String WITNESS_ID = textId;

		db = new GraphDatabaseFactory().newEmbeddedDatabase(DB_PATH);
		ArrayList<ReadingModel> readingModels = new ArrayList<ReadingModel>();

		Node witnessNode = getStartNode(tradId);
		if (witnessNode == null)
			return Response.status(Status.NOT_FOUND)
					.entity("Could not find tradition with this id").build();
		readingModels = getAllReadingsOfWitness(WITNESS_ID, witnessNode);
		if (readingModels.size() == 0)
			return Response.status(Status.NOT_FOUND)
					.entity("Could not found a witness with this id").build();
		db.shutdown();
		return Response.status(Status.OK).entity(readingModels).build();
	}

	/**
	 * gets the next readings from a given readings in the same witness
	 * 
	 * @param tradId
	 *            : tradition id
	 * @param textId
	 *            : witness id
	 * @param readId
	 *            : reading id
	 * @return the requested reading
	 */
	@Path("list/{tradId}/{textId}/{readId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getNextReadingInWitness(@PathParam("tradId") String tradId,
			@PathParam("textId") String textId,
			@PathParam("readId") String readId) {

		final String WITNESS_ID = textId;
		db = new GraphDatabaseFactory().newEmbeddedDatabase(DB_PATH);

		Node startNode = getStartNode(tradId);
		if (startNode == null)
			return Response.status(Status.NOT_FOUND)
					.entity("Could not find tradition with this id").build();

		ReadingModel reading = getNextReading(WITNESS_ID, readId, startNode);

		return Response.ok(reading).build();
	}

	/**
	 * gets the next reading to a given reading and a witness
	 * help method
	 * @param WITNESS_ID
	 * @param readId
	 * @param startNode
	 * @return the next reading to that of the readId
	 */
	private ReadingModel getNextReading(final String WITNESS_ID, String readId,
			Node startNode) {

		ArrayList<ReadingModel> readingModels = new ArrayList<ReadingModel>();
		ReadingModel reading = null;

		Evaluator e = createEvalForWitness(WITNESS_ID);

		try (Transaction tx = db.beginTx()) {
			int stop = 0;
			for (Node node : db.traversalDescription().depthFirst()
					.relationships(Relations.NORMAL, Direction.OUTGOING)
					.evaluator(e).traverse(startNode).nodes()) {
				if (stop == 1) {
					tx.success();
					return Reading.readingModelFromNode(node);
				}
				if (((String) node.getProperty("id")).equals(readId)) {
					stop = 1;
				}
			}
			throw new DataBaseException("requested readings not found");
		}
	}

	private Evaluator createEvalForWitness(final String WITNESS_ID) {
		Evaluator e = new Evaluator() {
			@Override
			public Evaluation evaluate(org.neo4j.graphdb.Path path) {

				if (path.length() == 0)
					return Evaluation.EXCLUDE_AND_CONTINUE;

				boolean includes = false;
				boolean continues = false;

				String[] arr = (String[]) path.lastRelationship().getProperty(
						"lexemes");
				for (String str : arr) {
					if (str.equals(WITNESS_ID)) {
						includes = true;
						continues = true;
					}
				}
				return Evaluation.of(includes, continues);
			}
		};
		return e;
	}

	/**
	 * gets the "start" node of a tradition
	 * @param traditionName
	 * @param userId
	 * 
	 * @return the start node of a witness
	 */
	private Node getStartNode(String tradId) {

		ExecutionEngine engine = new ExecutionEngine(db);
		DbPathProblemService problemFinder = new DbPathProblemService();
		Node startNode = null;

		/**
		 * this quarry gets the "Start" node of the witness
		 */
		String witnessQuarry = "match (tradition:TRADITION {id:'" + tradId
				+ "'})--(w:WORD  {text:'#START#'}) return w";

		try (Transaction tx = db.beginTx()) {

			ExecutionResult result = engine.execute(witnessQuarry);
			Iterator<Node> nodes = result.columnAs("w");

			if (!nodes.hasNext()) {
				throw new DataBaseException(
						problemFinder.findPathProblem(tradId));
			} else
				startNode = nodes.next();

			tx.success();
		}
		return startNode;
	}

	/**
	 * gets all readings of a single witness
	 * 
	 * @param WITNESS_ID
	 * @param startNode
	 *            : the start node of the tradition
	 * @return a list of the readings as readingModels
	 */
	private ArrayList<ReadingModel> getAllReadingsOfWitness(
			final String WITNESS_ID, Node startNode) {
		ArrayList<ReadingModel> readingModels = new ArrayList<ReadingModel>();

		Evaluator e = createEvalForWitness(WITNESS_ID);
		try (Transaction tx = db.beginTx()) {

			for (Node witnessNodes : db.traversalDescription().depthFirst()
					.relationships(Relations.NORMAL, Direction.OUTGOING)
					.evaluator(e).traverse(startNode).nodes()) {
				ReadingModel tempReading = Reading
						.readingModelFromNode(witnessNodes);

				readingModels.add(tempReading);
			}
			tx.success();
		}
		return readingModels;
	}

	@GET
	@Path("readings/{tradId}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response getAllReadingsOfTradition(@PathParam("tradId") String tradId) {

		ArrayList<ReadingModel> readList = new ArrayList<ReadingModel>();
		db = new GraphDatabaseFactory().newEmbeddedDatabase(DB_PATH);
		ExecutionEngine engine = new ExecutionEngine(db);

		try (Transaction tx = db.beginTx()) {
			Node traditionNode = null;
			Node startNode = null;
			ExecutionResult result = engine.execute("match (n:TRADITION {id: '"
					+ tradId + "'}) return n");
			Iterator<Node> nodes = result.columnAs("n");

			if (!nodes.hasNext())
				return Response.status(Status.NOT_FOUND)
						.entity("trad node not found").build();

			traditionNode = nodes.next();

			Iterable<Relationship> rels = traditionNode
					.getRelationships(Direction.OUTGOING);

			if (rels == null)
				return Response.status(Status.NOT_FOUND)
						.entity("rels not found").build();

			Iterator<Relationship> relIt = rels.iterator();

			while (relIt.hasNext()) {
				Relationship rel = relIt.next();
				startNode = rel.getEndNode();
				if (startNode != null && startNode.hasProperty("text")) {
					if (startNode.getProperty("text").equals("#START#")) {
						rels = startNode.getRelationships(Direction.OUTGOING);
						break;
					}
				}
			}

			if (rels == null)
				return Response.status(Status.NOT_FOUND)
						.entity("start node not found").build();

			TraversalDescription td = db.traversalDescription().breadthFirst()
					.relationships(Relations.NORMAL, Direction.OUTGOING)
					.evaluator(Evaluators.excludeStartPosition());

			Traverser traverser = td.traverse(startNode);
			for (org.neo4j.graphdb.Path path : traverser) {
				Node nd = path.endNode();
				ReadingModel rm = Reading.readingModelFromNode(nd);
				readList.add(rm);
			}

			tx.success();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			db.shutdown();
		}
		// return Response.status(Status.NOT_FOUND).build();

		return Response.ok(readList).build();
	}
}
