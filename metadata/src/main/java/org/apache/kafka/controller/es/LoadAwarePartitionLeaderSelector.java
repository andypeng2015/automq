/*
 * Copyright 2024, AutoMQ CO.,LTD.
 *
 * Use of this software is governed by the Business Source License
 * included in the file BSL.md
 *
 * As of the Change Date specified in that file, in accordance with
 * the Business Source License, use of this software will be governed
 * by the Apache License, Version 2.0
 */

package org.apache.kafka.controller.es;

import java.util.stream.Collectors;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.metadata.BrokerRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

public class LoadAwarePartitionLeaderSelector implements PartitionLeaderSelector {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoadAwarePartitionLeaderSelector.class);
    private final PriorityQueue<BrokerLoad> brokerLoads;
    private final RandomPartitionLeaderSelector randomSelector;
    private final Map<Integer, Double> brokerLoadMap;

    public LoadAwarePartitionLeaderSelector(List<BrokerRegistration> aliveBrokers, BrokerRegistration brokerToRemove) {
        Set<Integer> excludedBrokers = ClusterLoads.getInstance().excludedBrokers();
        if (excludedBrokers == null) {
            excludedBrokers = new HashSet<>();
        }
        List<BrokerRegistration> availableBrokers = new ArrayList<>();
        for (BrokerRegistration broker : aliveBrokers) {
            if (!excludedBrokers.contains(broker.id()) && broker.id() != brokerToRemove.id()) {
                availableBrokers.add(broker);
            }
        }
        brokerLoadMap = ClusterLoads.getInstance().brokerLoads();
        if (brokerLoadMap == null) {
            this.brokerLoads = null;
            LOGGER.warn("No broker loads available, using random partition leader selector");
        } else {
            this.brokerLoads = new PriorityQueue<>();
            for (BrokerRegistration broker : availableBrokers) {
                if (!broker.rack().equals(brokerToRemove.rack())) {
                    continue;
                }
                brokerLoads.offer(new BrokerLoad(broker.id(), brokerLoadMap.getOrDefault(broker.id(), 0.0)));
            }
        }
        this.randomSelector = new RandomPartitionLeaderSelector(availableBrokers.stream().map(BrokerRegistration::id).collect(Collectors.toList()), id -> true);
    }

    @Override
    public Optional<Integer> select(TopicPartition tp) {
        if (this.brokerLoads == null || brokerLoads.isEmpty()) {
            return randomSelector.select(tp);
        }
        double tpLoad = ClusterLoads.getInstance().partitionLoad(tp);
        if (tpLoad == ClusterLoads.INVALID) {
            return randomSelector.select(tp);
        }
        BrokerLoad candidate = brokerLoads.poll();
        if (candidate == null) {
            return randomSelector.select(tp);
        }
        double load = candidate.load() + tpLoad;
        candidate.setLoad(load);
        brokerLoadMap.put(candidate.brokerId(), load);
        brokerLoads.offer(candidate);
        return Optional.of(candidate.brokerId());
    }

    public static class BrokerLoad implements Comparable<BrokerLoad> {
        private final int brokerId;
        private double load;

        public BrokerLoad(int brokerId, double load) {
            this.brokerId = brokerId;
            this.load = load;
        }

        public int brokerId() {
            return brokerId;
        }

        public double load() {
            return load;
        }

        public void setLoad(double load) {
            this.load = load;
        }

        @Override
        public int compareTo(BrokerLoad o) {
            return Double.compare(load, o.load);
        }
    }
}
