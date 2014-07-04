package resa.topology.video;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import org.apache.commons.lang.StringUtils;
import resa.util.ConfigUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.stream.Stream;

import static resa.topology.video.Constant.*;

/**
 * Created by ding on 14-7-3.
 */
public class DistCalculator extends BaseRichBolt {

    private static final int[] EMPTY_MATCH = new int[0];
    private Map<float[], int[]> featDesc2Image;
    private OutputCollector collector;
    private double distThreshold;

    @Override

    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        featDesc2Image = new HashMap<>();
        loadIndex();
        this.collector = collector;
        distThreshold = ConfigUtil.getDouble(stormConf, CONF_FEAT_DIST_THRESHOLD, 100);
    }

    private void loadIndex() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(this.getClass().getResourceAsStream("/index.txt")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                StringTokenizer tokenizer = new StringTokenizer(line);
                String[] tmp = StringUtils.split(tokenizer.nextToken(), ',');
                float[] feat = new float[tmp.length];
                for (int i = 0; i < feat.length; i++) {
                    feat[i] = Float.parseFloat(tmp[i]);
                }
                int[] images = Stream.of(StringUtils.split(tokenizer.nextToken(), ',')).mapToInt(Integer::parseInt)
                        .toArray();
                featDesc2Image.put(feat, images);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void execute(Tuple input) {
        String frameId = input.getStringByField(FIELD_FRAME_ID);
        double dist = Double.MAX_VALUE;
        int[] matches = EMPTY_MATCH;
        float[] desc = (float[]) input.getValueByField(FIELD_FEATURE_DESC);
        for (Map.Entry<float[], int[]> e : featDesc2Image.entrySet()) {
            double d = distance(e.getKey(), desc);
            if (d < dist) {
                dist = d;
                matches = e.getValue();
            }
        }
        if (dist > distThreshold) {
            matches = EMPTY_MATCH;
        }
        collector.emit(STREAM_MATCH_IMAGES, input, new Values(frameId, matches));
        collector.ack(input);
    }

    private double distance(float[] v1, float[] v2) {
        double sum = 0;
        for (int i = 0; i < v1.length; i++) {
            double d = v1[i] - v2[1];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declareStream(STREAM_MATCH_IMAGES, new Fields(FIELD_FRAME_ID, FIELD_MATCH_IMAGES));
    }

}
