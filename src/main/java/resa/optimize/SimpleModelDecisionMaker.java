package resa.optimize;

import backtype.storm.Config;
import backtype.storm.task.GeneralTopologyContext;
import org.apache.log4j.Logger;
import resa.util.ConfigUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by ding on 14-4-30.
 */
public class SimpleModelDecisionMaker extends DecisionMaker {
    private static final Logger LOG = Logger.getLogger(SimpleModelDecisionMaker.class);
    private AggregatedData spoutAregatedData;
    private AggregatedData boltAregatedData;

    @Override
    public void init(Map<String, Object> conf, GeneralTopologyContext context) {
        super.init(conf, context);
        int historySize = ConfigUtil.getInt(conf, "", 1);
        spoutAregatedData = new AggregatedData(context, historySize);
        boltAregatedData = new AggregatedData(context, historySize);
    }

    @Override
    public Map<String, Integer> make(Iterable<MeasuredData> dataStream, int maxAvailableExectors,
                                     Map<String, Integer> currAllocation) {
        AggResultCalculator aggResultCalculator = new AggResultCalculator(dataStream, topologyContext);
        aggResultCalculator.calCMVStat();

        aggResultCalculator.getSpoutResult().forEach(spoutAregatedData::putResult);
        aggResultCalculator.getBoltResult().forEach(boltAregatedData::putResult);
        spoutAregatedData.rotate();
        boltAregatedData.rotate();

        ///Temp use, assume only one running topology!
        double targetQoS = ConfigUtil.getDouble(conf, "QoS", 5000.0);
        int maxSendQSize = ConfigUtil.getInt(conf, Config.TOPOLOGY_EXECUTOR_SEND_BUFFER_SIZE, 1024);
        int maxRecvQSize = ConfigUtil.getInt(conf, Config.TOPOLOGY_EXECUTOR_RECEIVE_BUFFER_SIZE, 1024);
        double sendQSizeThresh = ConfigUtil.getDouble(conf, "sendQSizeThresh", 5.0);
        double recvQSizeThreshRatio = ConfigUtil.getDouble(conf, "recvQSizeThreshRatio", 0.6);
        double recvQSizeThresh = recvQSizeThreshRatio * maxRecvQSize;
        double updInterval = ConfigUtil.getDouble(conf, "messageUpdateInterval", 10.0);

        double mesuredCompleteTimeMilliSec = 0.0;

        Map<String, ServiceNode> components = new HashMap<>();

//        spoutAregatedData.compHistoryResults.

        double avgCompleteHis = spoutAregatedData.compHistoryResults.entrySet().stream().mapToDouble(e -> {
            Iterable<ComponentAggResult> results = e.getValue();
            ComponentAggResult hisCar = ComponentAggResult.getCombinedResult(results,
                    ComponentAggResult.ComponentType.SPOUT);
            CntMeanVar hisCarCombined = hisCar.getSimpleCombinedProcessedTuple();
            return hisCarCombined.getAvg();
        }).average().getAsDouble();
        conf.put("avgCompleteHisMilliSec", avgCompleteHis);
        Map<String, ServiceNode> queueingNetwork = boltAregatedData.compHistoryResults.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    Iterable<ComponentAggResult> results = e.getValue();
                    ComponentAggResult hisCar = ComponentAggResult.getCombinedResult(results,
                            ComponentAggResult.ComponentType.BOLT);
                    CntMeanVar hisCarCombined = hisCar.getSimpleCombinedProcessedTuple();

                    double avgSendQLenHis = hisCar.sendQueueLen.getAvg();
                    double avgRecvQLenHis = hisCar.recvQueueLen.getAvg();
                    double arrivalRateHis = hisCar.recvArrivalCnt.getAvg() / updInterval;
                    double avgServTimeHis = hisCarCombined.getAvg();

                    double rhoHis = arrivalRateHis * avgServTimeHis / 1000;
                    //TODO change to executor count
                    double lambdaHis = arrivalRateHis * topologyContext.getComponentTasks(e.getKey()).size();
                    double muHis = 1000.0 / avgServTimeHis;

                    boolean sendQLenNormalHis = avgSendQLenHis < sendQSizeThresh;
                    boolean recvQlenNormalHis = avgRecvQLenHis < recvQSizeThresh;

                    LOG.info("avgSendQLenHis: " + avgSendQLenHis);
                    LOG.info("avgRecvQLenHis: " + avgRecvQLenHis);
                    LOG.info("arrivalRateHis: " + arrivalRateHis);
                    LOG.info("avgServTimeHis: " + avgServTimeHis);
                    LOG.info("rhoHis: " + rhoHis);
                    LOG.info("lambdaHis: " + lambdaHis);
                    LOG.info("muHis: " + muHis);

                    return new ServiceNode(lambdaHis, muHis, ServiceNode.ServiceType.EXPONENTIAL, 1);
                }));
        int maxThreadAvailable4Bolt = maxAvailableExectors - currAllocation.entrySet().stream()
                .filter(e -> topologyContext.getRawTopology().get_spouts().containsKey(e.getKey()))
                .mapToInt(Map.Entry::getValue).sum();
        conf.put("maxThreadAvailable4Bolt", maxThreadAvailable4Bolt);
        Map<String, Integer> boltAllocation = currAllocation.entrySet().stream()
                .filter(e -> topologyContext.getRawTopology().get_bolts().containsKey(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        OptimizeDecision optimizeDecision = SimpleServiceModelAnalyzer.checkOptimized(queueingNetwork, conf,
                boltAllocation, maxThreadAvailable4Bolt);
                LOG.info("minReq: " + optimizeDecision.minReqOptAllocation);
                LOG.info("status: " + optimizeDecision.status);
        return optimizeDecision.currOptAllocation;
    }

}
