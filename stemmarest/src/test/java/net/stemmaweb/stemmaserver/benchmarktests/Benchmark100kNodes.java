package net.stemmaweb.stemmaserver.benchmarktests;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Iterator;

import net.stemmaweb.rest.Root;
import net.stemmaweb.services.GraphDatabaseServiceProvider;
import net.stemmaweb.parser.GraphMLParser;
import net.stemmaweb.stemmaserver.JerseyTestServerFactory;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import com.carrotsearch.junitbenchmarks.annotation.AxisRange;
import com.carrotsearch.junitbenchmarks.annotation.BenchmarkMethodChart;
import org.neo4j.test.TestGraphDatabaseFactory;

/**
 * 
 * @author PSE FS 2015 Team2
 *
 */
@AxisRange(min = 0, max = 0.2)
@BenchmarkMethodChart(filePrefix = "benchmark/benchmark-100kNodes")
public class Benchmark100kNodes extends BenchmarkTests {

    @BeforeClass
    public static void prepareTheDatabase(){

        RandomGraphGenerator rgg = new RandomGraphGenerator();

        GraphDatabaseService db = new GraphDatabaseServiceProvider(
                new TestGraphDatabaseFactory().newImpermanentDatabase()).getDatabase();

        webResource = new Root();
        jerseyTest = JerseyTestServerFactory.newJerseyTestServer()
                .addResource(webResource)
                .create();
        try {
            jerseyTest.setUp();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        rgg.role(db, 10, 10, 10, 100);

        //importResource = new GraphMLParser();
        testfile = new File("src/TestFiles/ReadingstestTradition.xml");
        try {
            String fileName = testfile.getPath();
            tradId = createTraditionFromFile("Tradition", "LR", "1", fileName, "graphml");
//			tradId = importResource.parseGraphML(testfile.getPath(), "1","Tradition").getEntity().toString().replace("{\"tradId\":", "").replace("}", "");
        } catch (FileNotFoundException f) {
            // this error should not occur
            assertTrue(false);
        }

        Result result = db.execute("match (w:READING {text:'showers'}) return w");
        Iterator<Node> nodes = result.columnAs("w");
        duplicateReadingNodeId = nodes.next().getId();

        result = db.execute("match (w:READING {text:'the root'}) return w");
        nodes = result.columnAs("w");
        theRoot = nodes.next().getId();

        result = db.execute("match (w:READING {text:'unto me'}) return w");
        nodes = result.columnAs("w");
        untoMe = nodes.next().getId();
    }

    @AfterClass
    public static void shutdown() throws Exception{
        GraphDatabaseServiceProvider dbServiceProvider = new GraphDatabaseServiceProvider();
        dbServiceProvider.getDatabase().shutdown();
        jerseyTest.tearDown();
    }
}
