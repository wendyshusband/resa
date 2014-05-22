package resa.optimize;

import backtype.storm.Config;
import backtype.storm.generated.Nimbus;
import backtype.storm.generated.TopologyInfo;
import backtype.storm.scheduler.ExecutorDetails;
import backtype.storm.task.GeneralTopologyContext;
import backtype.storm.topology.IRichBolt;
import backtype.storm.topology.IRichSpout;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.utils.NimbusClient;
import backtype.storm.utils.Utils;
import org.junit.Test;
import resa.topology.RandomSentenceSpout;
import resa.util.ResaConfig;
import resa.util.TopologyHelper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by Tom.fu on 5/5/2014.
 */
public class SimpleGeneralDecisionMakerTest {

    private TopologyBuilder builder = new TopologyBuilder();
    private Map<String, Object> conf = ResaConfig.create(true);
    private Map<Integer, String> t2c = new HashMap<>();

    @Test
    public void testMake() throws Exception {
        int numWorkers = 3;
        int numAckers = 1;

        conf.put(Config.TOPOLOGY_WORKERS, 3);
        conf.put(Config.TOPOLOGY_ACKER_EXECUTORS, 1);

        IRichSpout spout = new RandomSentenceSpout();
        builder.setSpout("sentenceSpout", spout, 1);

        double split_mu = 10.0;
        IRichBolt splitBolt = new TASplitSentence(() -> (long) (-Math.log(Math.random()) * 1000.0 / split_mu));
        builder.setBolt("split", splitBolt, 4).shuffleGrouping("sentenceSpout");

        double counter_mu = 5.0;
        IRichBolt wcBolt = new TAWordCounter(() -> (long) (-Math.log(Math.random()) * 1000.0 / counter_mu));
        builder.setBolt("counter", wcBolt, 2).shuffleGrouping("split");
        t2c.clear();
        t2c.put(5, "sentenceSpout");

        t2c.put(3, "counter");
        t2c.put(4, "counter");

        t2c.put(6, "split");
        t2c.put(7, "split");
        t2c.put(8, "split");
        t2c.put(9, "split");

        Map<String, Integer> currAllocation = new HashMap<>();
        currAllocation.put("counter", 2);
        currAllocation.put("split", 4);
        currAllocation.put("sentenceSpout", 1);

        SimpleGeneralDecisionMaker smdm = new SimpleGeneralDecisionMaker();
        smdm.init(conf, currAllocation, builder.createTopology());

        String host = "192.168.0.31";
        int port = 6379;
        String queue = "ta1wc";
        int maxLen = 50;

        Map<String, List<ExecutorDetails>> comp2Executors = new HashMap<>();
        comp2Executors.put("counter", Arrays.asList(new ExecutorDetails(3, 3), new ExecutorDetails(4, 4)));
        comp2Executors.put("sentenceSpout", Arrays.asList(new ExecutorDetails(5, 5)));
        comp2Executors.put("split", Arrays.asList(new ExecutorDetails(6, 6), new ExecutorDetails(7, 7),
                new ExecutorDetails(8, 8), new ExecutorDetails(9, 9)));

        AggResultCalculator resultCalculator = new AggResultCalculator(
                RedisDataSource.readData(host, port, queue, maxLen), comp2Executors, builder.createTopology());
        resultCalculator.calCMVStat();
        System.out.println(smdm.make(resultCalculator.getResults(), 6));

    }

    @Test
    public void testMakeUsingTopologyHelper() throws Exception {

        conf.put(Config.NIMBUS_HOST, "192.168.0.31");
        conf.put(Config.NIMBUS_THRIFT_PORT, 6627);

        conf.put("resa.opt.smd.qos.ms", 1500.0);
        conf.put("resa.opt.win.history.size", 3);

        GeneralTopologyContext gtc = TopologyHelper.getGeneralTopologyContext("ta1wc", conf);

        if (gtc == null) {
            System.out.println("gtc is null");
            return;
        }

        String host = "192.168.0.31";
        int port = 6379;
        String queue = "ta1wc";
        int maxLen = 500;

        String topoName = "ta1wc";

        NimbusClient nimbusClient = NimbusClient.getConfiguredClient(conf);
        Nimbus.Client nimbus = nimbusClient.getClient();
        String topoId = TopologyHelper.getTopologyId(nimbus, topoName);

        TopologyInfo topoInfo = nimbus.getTopologyInfo(topoId);

        Map<String, Integer> currAllocation = topoInfo.get_executors().stream().filter(e -> !Utils.isSystemId(e.get_component_id()))
                .collect(Collectors.groupingBy(e -> e.get_component_id(),
                        Collectors.reducing(0, e -> 1, (i1, i2) -> i1 + i2)));

        SimpleGeneralDecisionMaker smdm = new SimpleGeneralDecisionMaker();
        smdm.init(conf, currAllocation, gtc.getRawTopology());

        Map<String, List<ExecutorDetails>> comp2Executors = TopologyHelper.getTopologyExecutors(topoName, conf)
                .entrySet().stream().filter(e -> !Utils.isSystemId(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        for (int i = 0; i < 10000; i++) {
            Utils.sleep(30000);

            topoInfo = nimbus.getTopologyInfo(topoId);
            Map<String, Integer> updatedAllocation = topoInfo.get_executors().stream().filter(e -> !Utils.isSystemId(e.get_component_id()))
                    .collect(Collectors.groupingBy(e -> e.get_component_id(),
                            Collectors.reducing(0, e -> 1, (i1, i2) -> i1 + i2)));

            AggResultCalculator resultCalculator = new AggResultCalculator(
                    RedisDataSource.readData(host, port, queue, maxLen), comp2Executors, gtc.getRawTopology());
            resultCalculator.calCMVStat();

            System.out.println("-------------Report on: " + System.currentTimeMillis() + "------------------------------");
            if (currAllocation.equals(updatedAllocation)) {
                System.out.println(currAllocation + "-->" + smdm.make(resultCalculator.getResults(), 7));
            } else {
                currAllocation = updatedAllocation;
                smdm.allocationChanged(currAllocation);
                System.out.println("Allocation updated to " + currAllocation);
            }
        }
    }


    @Test
    public void testRebalanceUsingTopologyHelper() throws Exception {

        conf.put(Config.NIMBUS_HOST, "192.168.0.31");
        conf.put(Config.NIMBUS_THRIFT_PORT, 6627);

        conf.put("resa.opt.smd.qos.ms", 1500.0);
        conf.put("resa.opt.win.history.size", 3);

        GeneralTopologyContext gtc = TopologyHelper.getGeneralTopologyContext("ta1wc", conf);

        if (gtc == null) {
            System.out.println("gtc is null");
            return;
        }

        String host = "192.168.0.31";
        int port = 6379;
        String queue = "ta1wc";
        int maxLen = 500;

        String topoName = "ta1wc";

        NimbusClient nimbusClient = NimbusClient.getConfiguredClient(conf);
        Nimbus.Client nimbus = nimbusClient.getClient();
        String topoId = TopologyHelper.getTopologyId(nimbus, topoName);

        Map<String, List<ExecutorDetails>> comp2Executors = TopologyHelper.getTopologyExecutors(topoName, conf)
                .entrySet().stream().filter(e -> !Utils.isSystemId(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        for (int i = 0; i < 10000; i++) {
            Utils.sleep(10000);

            TopologyInfo topoInfo = nimbus.getTopologyInfo(topoId);
            Map<String, Integer> currAllocation = topoInfo.get_executors().stream().filter(e -> !Utils.isSystemId(e.get_component_id()))
                    .collect(Collectors.groupingBy(e -> e.get_component_id(),
                            Collectors.reducing(0, e -> 1, (i1, i2) -> i1 + i2)));

            System.out.println("-------------Report on: " + System.currentTimeMillis() + "------------------------------");
            System.out.println(currAllocation);
        }
    }
}