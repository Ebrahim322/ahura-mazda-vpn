package com.ahuramazda.cleanip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Groups the results by /24 (or /16) block so you can see where the clean addresses live. */
public final class RangeStats {

    public static final class Block {
        public final String cidr;
        public int total;
        public int alive;
        public int bestLatency = Integer.MAX_VALUE;

        Block(String cidr) {
            this.cidr = cidr;
        }

        public boolean isPromising() {
            return alive > 0 && (alive * 100 / Math.max(1, total)) >= 20;
        }
    }

    private RangeStats() {
    }

    /** @param prefix 16, 20 or 24 */
    public static List<Block> group(List<ProbeResult> results, int prefix) {
        Map<String, Block> map = new HashMap<String, Block>();
        for (ProbeResult r : results) {
            int[] cidr = Ipv4.cidr(r.ip + "/" + prefix);
            if (cidr == null) {
                continue;
            }
            String key = Ipv4.format(cidr[0]) + "/" + prefix;
            Block block = map.get(key);
            if (block == null) {
                block = new Block(key);
                map.put(key, block);
            }
            block.total++;
            if (r.isUsable()) {
                block.alive++;
                block.bestLatency = Math.min(block.bestLatency, r.latencyMs());
            }
        }
        List<Block> out = new ArrayList<Block>(map.values());
        Collections.sort(out, new Comparator<Block>() {
            @Override
            public int compare(Block a, Block b) {
                if (a.alive != b.alive) {
                    return b.alive - a.alive;
                }
                int la = a.bestLatency == Integer.MAX_VALUE ? Integer.MAX_VALUE : a.bestLatency;
                int lb = b.bestLatency == Integer.MAX_VALUE ? Integer.MAX_VALUE : b.bestLatency;
                if (la != lb) {
                    return la < lb ? -1 : 1;
                }
                return a.cidr.compareTo(b.cidr);
            }
        });
        return out;
    }
}
