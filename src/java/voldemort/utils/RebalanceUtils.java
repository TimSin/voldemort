/*
 * Copyright 2008-2010 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import voldemort.VoldemortException;
import voldemort.client.protocol.admin.AdminClient;
import voldemort.client.protocol.admin.AdminClientConfig;
import voldemort.client.rebalance.RebalancePartitionsInfo;
import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.routing.RoutingStrategy;
import voldemort.routing.RoutingStrategyFactory;
import voldemort.server.VoldemortConfig;
import voldemort.server.rebalance.VoldemortRebalancingException;
import voldemort.store.StoreDefinition;
import voldemort.store.memory.InMemoryStorageConfiguration;
import voldemort.store.metadata.MetadataStore;
import voldemort.store.metadata.MetadataStore.VoldemortState;
import voldemort.store.mysql.MysqlStorageConfiguration;
import voldemort.store.readonly.ReadOnlyStorageConfiguration;
import voldemort.store.readonly.ReadOnlyStorageFormat;
import voldemort.versioning.Occured;
import voldemort.versioning.VectorClock;
import voldemort.versioning.Versioned;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * RebalanceUtils provide basic functionality for rebalancing.
 * 
 */
public class RebalanceUtils {

    private static Logger logger = Logger.getLogger(RebalanceUtils.class);

    public final static List<String> rebalancingStoreEngineBlackList = Arrays.asList(MysqlStorageConfiguration.TYPE_NAME,
                                                                                     InMemoryStorageConfiguration.TYPE_NAME);

    /**
     * Get the latest cluster from all available nodes in the cluster<br>
     * 
     * Throws exception if:<br>
     * A) Any node in the required nodes list fails to respond.<br>
     * B) Cluster is in inconsistent state with concurrent versions for cluster
     * metadata on any two nodes.<br>
     * 
     * @param requiredNodes List of nodes from which we definitely need an
     *        answer
     * @param adminClient Admin client used to query the nodes
     * @return Returns the latest cluster metadata
     */
    public static Versioned<Cluster> getLatestCluster(List<Integer> requiredNodes,
                                                      AdminClient adminClient) {
        Versioned<Cluster> latestCluster = new Versioned<Cluster>(adminClient.getAdminClientCluster());
        ArrayList<Versioned<Cluster>> clusterList = new ArrayList<Versioned<Cluster>>();

        clusterList.add(latestCluster);
        for(Node node: adminClient.getAdminClientCluster().getNodes()) {
            try {
                Versioned<Cluster> versionedCluster = adminClient.getRemoteCluster(node.getId());
                VectorClock newClock = (VectorClock) versionedCluster.getVersion();
                if(null != newClock && !clusterList.contains(versionedCluster)) {
                    // check no two clocks are concurrent.
                    checkNotConcurrent(clusterList, newClock);

                    // add to clock list
                    clusterList.add(versionedCluster);

                    // update latestClock
                    Occured occured = newClock.compare(latestCluster.getVersion());
                    if(Occured.AFTER.equals(occured))
                        latestCluster = versionedCluster;
                }
            } catch(Exception e) {
                if(null != requiredNodes && requiredNodes.contains(node.getId()))
                    throw new VoldemortException("Failed to get Cluster version from node:" + node,
                                                 e);
                else
                    logger.info("Failed to get Cluster version from node:" + node, e);
            }
        }

        return latestCluster;
    }

    private static void checkNotConcurrent(ArrayList<Versioned<Cluster>> clockList,
                                           VectorClock newClock) {
        for(Versioned<Cluster> versionedCluster: clockList) {
            VectorClock clock = (VectorClock) versionedCluster.getVersion();
            if(Occured.CONCURRENTLY.equals(clock.compare(newClock)))
                throw new VoldemortException("Cluster is in inconsistent state got conflicting clocks "
                                             + clock + " and " + newClock);

        }
    }

    /**
     * Check the execution state of the server by checking the state of
     * {@link MetadataStore.VoldemortState} <br>
     * 
     * This function checks if the nodes are all in normal state (
     * {@link VoldemortState#NORMAL_SERVER}).
     * 
     * @param cluster Cluster metadata whose nodes we are checking
     * @param adminClient Admin client used to query
     * @throws VoldemortRebalancingException if any node is not in normal state
     */
    public static void validateClusterState(final Cluster cluster, final AdminClient adminClient) {
        for(Node node: cluster.getNodes()) {
            Versioned<String> versioned = adminClient.getRemoteMetadata(node.getId(),
                                                                        MetadataStore.SERVER_STATE_KEY);

            if(!VoldemortState.NORMAL_SERVER.name().equals(versioned.getValue())) {
                throw new VoldemortRebalancingException("Cannot rebalance since node "
                                                        + node.getId() + " (" + node.getHost()
                                                        + ") is not in normal state, but in "
                                                        + versioned.getValue());
            } else {
                if(logger.isInfoEnabled()) {
                    logger.info("Node " + node.getId() + " (" + node.getHost()
                                + ") is ready for rebalance.");
                }
            }
        }
    }

    /**
     * Given the current cluster and a target cluster, generates a cluster with
     * new nodes ( which in turn contain empty partition lists )
     * 
     * @param currentCluster Current cluster metadata
     * @param targetCluster Target cluster metadata
     * @return Returns a new cluster which contains nodes of the current cluster
     *         + new nodes
     */
    public static Cluster getClusterWithNewNodes(Cluster currentCluster, Cluster targetCluster) {
        ArrayList<Node> newNodes = new ArrayList<Node>();
        for(Node node: targetCluster.getNodes()) {
            if(!RebalanceUtils.containsNode(currentCluster, node.getId())) {
                newNodes.add(RebalanceUtils.updateNode(node, new ArrayList<Integer>()));
            }
        }
        return RebalanceUtils.updateCluster(currentCluster, newNodes);
    }

    /**
     * Concatenates the list of current nodes in the given cluster with the new
     * nodes provided and returns an updated cluster metadata
     * 
     * @param currentCluster The current cluster metadata
     * @param updatedNodeList The list of new nodes to be added
     * @return New cluster metadata containing both the sets of nodes
     */
    public static Cluster updateCluster(Cluster currentCluster, List<Node> updatedNodeList) {
        List<Node> newNodeList = new ArrayList<Node>(updatedNodeList);
        for(Node currentNode: currentCluster.getNodes()) {
            if(!updatedNodeList.contains(currentNode))
                newNodeList.add(currentNode);
        }

        Collections.sort(newNodeList);
        return new Cluster(currentCluster.getName(),
                           newNodeList,
                           Lists.newArrayList(currentCluster.getZones()));
    }

    /**
     * Given a cluster and a node id checks if the node exists
     * 
     * @param cluster The cluster metadata to check in
     * @param nodeId The node id to search for
     * @return True if cluster contains the node id, else false
     */
    public static boolean containsNode(Cluster cluster, int nodeId) {
        try {
            cluster.getNodeById(nodeId);
            return true;
        } catch(VoldemortException e) {
            return false;
        }
    }

    /**
     * Updates the existing cluster such that we remove partitions mentioned
     * from the stealer node and add them to the donor node
     * 
     * @param cluster Existing cluster metadata
     * @param stealerNode Node from which we are stealing the partitions
     * @param donorNode Node to which we are donating
     * @param partitionList List of partitions we are moving
     * @return Updated cluster metadata
     */
    public static Cluster createUpdatedCluster(Cluster cluster,
                                               Node stealerNode,
                                               Node donorNode,
                                               List<Integer> partitionList) {
        List<Integer> stealerPartitionList = new ArrayList<Integer>(stealerNode.getPartitionIds());
        List<Integer> donorPartitionList = new ArrayList<Integer>(donorNode.getPartitionIds());

        for(int p: cluster.getNodeById(stealerNode.getId()).getPartitionIds()) {
            if(!stealerPartitionList.contains(p))
                stealerPartitionList.add(p);
        }

        for(int partition: partitionList) {
            for(int i = 0; i < donorPartitionList.size(); i++) {
                if(partition == donorPartitionList.get(i)) {
                    donorPartitionList.remove(i);
                }
            }
            if(!stealerPartitionList.contains(partition))
                stealerPartitionList.add(partition);
        }

        // sort both list
        Collections.sort(stealerPartitionList);
        Collections.sort(donorPartitionList);

        // update both nodes
        stealerNode = updateNode(stealerNode, stealerPartitionList);
        donorNode = updateNode(donorNode, donorPartitionList);

        Cluster updatedCluster = updateCluster(cluster, Arrays.asList(stealerNode, donorNode));
        return updatedCluster;
    }

    /**
     * Creates a new cluster by adding a donated partition to a new or existing
     * node.
     * 
     * @param currentCluster current cluster used to copy from.
     * @param stealerNode now or existing node being updated.
     * @param donatedPartition partition donated to the <code>stealerNode</code>
     */
    public static Cluster createUpdatedCluster(final Cluster currentCluster,
                                               Node stealerNode,
                                               final int donatedPartition) {
        // Gets the donor Node that owns this donated partition
        Node donorNode = RebalanceUtils.getNodeByPartitionId(currentCluster, donatedPartition);

        // Removes the node from the list,
        final List<Node> nodes = new ArrayList<Node>(currentCluster.getNodes());
        nodes.remove(donorNode);
        nodes.remove(stealerNode);

        // Update the list of partitions for this node
        donorNode = RebalanceUtils.removePartitionToNode(donorNode, donatedPartition);
        stealerNode = RebalanceUtils.addPartitionToNode(stealerNode, donatedPartition);

        // Add the updated nodes (donor and stealer).
        nodes.add(donorNode);
        nodes.add(stealerNode);

        // After the stealer & donor were fixed recreate the cluster.
        // Sort the nodes so they will appear in the same order all the time.
        Collections.sort(nodes);
        return new Cluster(currentCluster.getName(),
                           nodes,
                           Lists.newArrayList(currentCluster.getZones()));
    }

    /**
     * Creates a replica of the node with the new partitions list
     * 
     * @param node The node whose replica we are creating
     * @param partitionsList The new partitions list
     * @return Replica of node with new partitions list
     */
    public static Node updateNode(Node node, List<Integer> partitionsList) {
        return new Node(node.getId(),
                        node.getHost(),
                        node.getHttpPort(),
                        node.getSocketPort(),
                        node.getAdminPort(),
                        node.getZoneId(),
                        partitionsList);
    }

    /**
     * Add a partition to the node provided
     * 
     * @param node The node to which we'll add the partition
     * @param donatedPartition The partition to add
     * @return The new node with the new partition
     */
    public static Node addPartitionToNode(final Node node, Integer donatedPartition) {
        return addPartitionToNode(node, Sets.newHashSet(donatedPartition));
    }

    /**
     * Remove a partition from the node provided
     * 
     * @param node The node from which we're removing the partition
     * @param donatedPartition The partitions to remove
     * @return The new node without the partition
     */
    public static Node removePartitionToNode(final Node node, Integer donatedPartition) {
        return removePartitionToNode(node, Sets.newHashSet(donatedPartition));
    }

    /**
     * Add the set of partitions to the node provided
     * 
     * @param node The node to which we'll add the partitions
     * @param donatedPartitions The list of partitions to add
     * @return The new node with the new partitions
     */
    public static Node addPartitionToNode(final Node node, final Set<Integer> donatedPartitions) {
        List<Integer> deepCopy = new ArrayList<Integer>(node.getPartitionIds());
        deepCopy.addAll(donatedPartitions);
        return RebalanceUtils.updateNode(node, deepCopy);
    }

    /**
     * Remove the set of partitions from the node provided
     * 
     * @param node The node from which we're removing the partitions
     * @param donatedPartitions The list of partitions to remove
     * @return The new node without the partitions
     */
    public static Node removePartitionToNode(final Node node, final Set<Integer> donatedPartitions) {
        List<Integer> deepCopy = new ArrayList<Integer>(node.getPartitionIds());
        deepCopy.removeAll(donatedPartitions);
        return RebalanceUtils.updateNode(node, deepCopy);
    }

    /**
     * Given the cluster metadata returns a mapping of partition to node
     * 
     * @param currentCluster Cluster metadata
     * @return Map of partition id to node id
     */
    public static Map<Integer, Integer> getCurrentPartitionMapping(Cluster currentCluster) {

        Map<Integer, Integer> partitionToNode = new LinkedHashMap<Integer, Integer>();

        for(Node node: currentCluster.getNodes()) {
            for(Integer partition: node.getPartitionIds()) {
                // Check if partition is on another node
                Integer previousRegisteredNodeId = partitionToNode.get(partition);
                if(previousRegisteredNodeId != null) {
                    throw new IllegalArgumentException("Partition id " + partition
                                                       + " found on two nodes : " + node.getId()
                                                       + " and " + previousRegisteredNodeId);
                }

                partitionToNode.put(partition, node.getId());
            }
        }

        return partitionToNode;
    }

    /**
     * Attempt to propagate cluster definition to all nodes in the cluster.
     * 
     * @throws VoldemortException If we can't propagate to a list of require
     *         nodes.
     * @param adminClient {@link voldemort.client.protocol.admin.AdminClient}
     *        instance to use
     * @param cluster Cluster definition we wish to propagate
     * @param clock Vector clock to attach to the cluster definition
     * @param requireNodeIds If we can't propagate to these node ids, roll back
     *        and throw an exception
     */
    public static void propagateCluster(AdminClient adminClient,
                                        Cluster cluster,
                                        VectorClock clock,
                                        List<Integer> requireNodeIds) {
        List<Integer> allNodeIds = new ArrayList<Integer>();
        for(Node node: cluster.getNodes()) {
            allNodeIds.add(node.getId());
        }
        propagateCluster(adminClient, cluster, clock, allNodeIds, requireNodeIds);
    }

    /**
     * Attempt to propagate a cluster definition to specified nodes.
     * 
     * @throws VoldemortException If we can't propagate to a list of require
     *         nodes.
     * @param adminClient {@link voldemort.client.protocol.admin.AdminClient}
     *        instance to use.
     * @param cluster Cluster definition we wish to propagate
     * @param clock Vector clock to attach to the cluster definition
     * @param attemptNodeIds Attempt to propagate to these node ids
     * @param requiredNodeIds If we can't propagate can't propagate to these
     *        node ids, roll back and throw an exception
     */
    public static void propagateCluster(AdminClient adminClient,
                                        Cluster cluster,
                                        VectorClock clock,
                                        List<Integer> attemptNodeIds,
                                        List<Integer> requiredNodeIds) {
        List<Integer> failures = new ArrayList<Integer>();

        // copy everywhere else first
        for(int nodeId: attemptNodeIds) {
            if(!requiredNodeIds.contains(nodeId)) {
                try {
                    adminClient.updateRemoteCluster(nodeId, cluster, clock);
                } catch(VoldemortException e) {
                    // ignore these
                    logger.debug("Failed to copy new cluster.xml(" + cluster
                                 + ") on non-required node:" + nodeId, e);
                }
            }
        }

        // attempt copying on all required nodes.
        for(int nodeId: requiredNodeIds) {
            Node node = cluster.getNodeById(nodeId);
            try {
                logger.debug("Updating remote node:" + nodeId + " with cluster:" + cluster);
                adminClient.updateRemoteCluster(node.getId(), cluster, clock);
            } catch(Exception e) {
                failures.add(node.getId());
                logger.debug(e);
            }
        }

        if(failures.size() > 0) {
            throw new VoldemortException("Failed to copy updated cluster.xml:" + cluster
                                         + " on required nodes:" + failures);
        }
    }

    /**
     * For a particular stealer node find all the "primary" partitions it will
     * steal
     * 
     * @param currentCluster The cluster definition of the existing cluster
     * @param targetCluster The target cluster definition
     * @param stealNodeId Node id of the stealer node
     * @return Returns a list of partitions which this stealer node will get
     */
    public static Set<Integer> getStolenPrimaries(final Cluster currentCluster,
                                                  final Cluster targetCluster,
                                                  final int stealNodeId) {
        List<Integer> targetList = new ArrayList<Integer>(targetCluster.getNodeById(stealNodeId)
                                                                       .getPartitionIds());

        List<Integer> currentList = new ArrayList<Integer>();
        if(RebalanceUtils.containsNode(currentCluster, stealNodeId))
            currentList = currentCluster.getNodeById(stealNodeId).getPartitionIds();

        // remove all current partitions from targetList
        targetList.removeAll(currentList);

        return new TreeSet<Integer>(targetList);
    }

    /**
     * For a particular stealer node find all the replica partitions it will
     * steal.
     * 
     * <br>
     * 
     * If the stealer node has a primary that was given away and later on this
     * partition became a replica then this replica is not going to be
     * considered one that needs to be migrated due to the fact that it's
     * already on the stealer node.
     * 
     * @param cluster Current cluster metadata
     * @param target Target cluster metadata
     * @param storeDefs List of store definitions
     * @param stealerId Stealer node id
     * @return Set of partition ids representing the replicas about to be
     *         stolen.
     */
    public static Set<Integer> getStolenReplicas(final Cluster cluster,
                                                 final Cluster target,
                                                 final List<StoreDefinition> storeDefs,
                                                 final int stealerId) {
        Map<Integer, Set<Integer>> nodeIdToReplicas = getNodeIdToAllPartitions(cluster,
                                                                               storeDefs,
                                                                               false);
        Map<Integer, Set<Integer>> targetNodeIdToReplicas = getNodeIdToAllPartitions(target,
                                                                                     storeDefs,
                                                                                     false);

        Set<Integer> clusterStealerReplicas = nodeIdToReplicas.get(stealerId);
        Set<Integer> targetStealerReplicas = targetNodeIdToReplicas.get(stealerId);

        Set<Integer> replicasAddedInTarget = RebalanceUtils.getAddedInTarget(clusterStealerReplicas,
                                                                             targetStealerReplicas);

        if(RebalanceUtils.containsNode(cluster, stealerId)) {
            replicasAddedInTarget.removeAll(cluster.getNodeById(stealerId).getPartitionIds());
        }
        return replicasAddedInTarget;
    }

    /**
     * For a particular cluster creates a mapping of node id to their
     * corresponding list of primary and replica partitions
     * 
     * @param cluster The cluster metadata
     * @param storeDefs The store definitions
     * @param includePrimary Include the primary partition?
     * @return Map of node id to set of "all" partitions
     */
    public static Map<Integer, Set<Integer>> getNodeIdToAllPartitions(final Cluster cluster,
                                                                      final List<StoreDefinition> storeDefs,
                                                                      boolean includePrimary) {
        final StoreDefinition maxReplicationStore = RebalanceUtils.getMaxReplicationStore(storeDefs);
        final RoutingStrategy routingStrategy = new RoutingStrategyFactory().updateRoutingStrategy(maxReplicationStore,
                                                                                                   cluster);

        final Map<Integer, Set<Integer>> nodeIdToReplicas = new HashMap<Integer, Set<Integer>>();
        final Map<Integer, Integer> partitionToNodeIdMap = getCurrentPartitionMapping(cluster);

        // Map initialization.
        for(Node node: cluster.getNodes()) {
            nodeIdToReplicas.put(node.getId(), new TreeSet<Integer>());
        }

        // Loops through all nodes
        for(Node node: cluster.getNodes()) {

            // Gets the partitions that this node was configured with.
            for(Integer primary: node.getPartitionIds()) {

                // Gets the list of replicating partitions.
                List<Integer> replicaPartitionList = routingStrategy.getReplicatingPartitionList(primary);

                if(!includePrimary)
                    replicaPartitionList.remove(primary);

                // Get the node that this replicating partition belongs to.
                for(Integer replicaPartition: replicaPartitionList) {
                    Integer replicaNodeId = partitionToNodeIdMap.get(replicaPartition);

                    // The replicating node will have a copy of primary.
                    nodeIdToReplicas.get(replicaNodeId).add(primary);
                }
            }
        }
        return nodeIdToReplicas;
    }

    /**
     * Print log to the following logger ( Info level )
     * 
     * @param stealerNodeId Stealer node id
     * @param logger Logger class
     * @param message The message to print
     */
    public static void printLog(int stealerNodeId, Logger logger, String message) {
        logger.info("Stealer node " + Integer.toString(stealerNodeId) + "] " + message);
    }

    /**
     * Print log to the following logger ( Error level )
     * 
     * @param stealerNodeId Stealer node id
     * @param logger Logger class
     * @param message The message to print
     */
    public static void printErrorLog(int stealerNodeId, Logger logger, String message, Exception e) {
        if(e == null) {
            logger.error("Stealer node " + Integer.toString(stealerNodeId) + "] " + message);
        } else {
            logger.error("Stealer node " + Integer.toString(stealerNodeId) + "] " + message, e);
        }
    }

    /**
     * Returns the Node associated to the provided partition.
     * 
     * @param cluster The cluster in which to find the node
     * @param partitionId Partition id for which we want the corresponding node
     * @return Node that owns the partition
     */
    public static Node getNodeByPartitionId(Cluster cluster, int partitionId) {
        for(Node node: cluster.getNodes()) {
            if(node.getPartitionIds().contains(partitionId)) {
                return node;
            }
        }
        return null;
    }

    /**
     * Returns a set of partitions that were added to the target list
     * 
     * getAddedInTarget(cluster, null) - nothing was added, returns null.
     * 
     * <br>
     * 
     * getAddedInTarget(null, target) - everything in target was added, return
     * target.
     * 
     * <br>
     * 
     * getAddedInTarget(null, null) - neither added nor deleted, return null.
     * 
     * <br>
     * 
     * getAddedInTarget(cluster, target)) - returns new partition not found in
     * cluster.
     * 
     * @param current Set of partitions present in current cluster for a given
     *        node.
     * @param target Set of partitions present in target cluster for a given
     *        node.
     * @return A set of added partitions in target cluster or empty set
     */
    public static Set<Integer> getAddedInTarget(Set<Integer> current, Set<Integer> target) {
        if(current == null || target == null) {
            return new TreeSet<Integer>();
        }
        return getDiff(target, current);
    }

    /**
     * Returns a set of partitions that were deleted in the target set
     * 
     * getDeletedInTarget(cluster, null) - everything was deleted, returns
     * cluster.
     * 
     * <br>
     * 
     * getDeletedInTarget(null, target) - everything in target was added, return
     * target.
     * 
     * <br>
     * 
     * getDeletedInTarget(null, null) - neither added nor deleted, return null.
     * 
     * <br>
     * 
     * getDeletedInTarget(cluster, target)) - returns deleted partition not
     * found in target.
     * 
     * @param current Set of partitions present in current cluster for a given
     *        node.
     * @param target Set of partitions present in target cluster for a given
     *        node.
     * @return A set of deleted partitions in Target cluster or empty set
     */
    public static Set<Integer> getDeletedInTarget(final Set<Integer> current,
                                                  final Set<Integer> target) {
        if(current == null || target == null) {
            return new TreeSet<Integer>();
        }
        return getDiff(current, target);
    }

    private static Set<Integer> getDiff(final Set<Integer> source, final Set<Integer> dest) {
        Set<Integer> diff = new TreeSet<Integer>();
        for(Integer id: source) {
            if(!dest.contains(id)) {
                diff.add(id);
            }
        }
        return diff;
    }

    public static AdminClient createTempAdminClient(VoldemortConfig voldemortConfig,
                                                    Cluster cluster,
                                                    int numThreads,
                                                    int numConnPerNode) {
        AdminClientConfig config = new AdminClientConfig().setMaxConnectionsPerNode(numConnPerNode)
                                                          .setMaxThreads(numThreads)
                                                          .setAdminConnectionTimeoutSec(voldemortConfig.getAdminConnectionTimeout())
                                                          .setAdminSocketTimeoutSec(voldemortConfig.getAdminSocketTimeout())
                                                          .setAdminSocketBufferSize(voldemortConfig.getAdminSocketBufferSize());

        return new AdminClient(cluster, config);
    }

    /**
     * Given the cluster metadata and admin client, retrieves the list of store
     * definitions.
     * 
     * <br>
     * 
     * It also checks if the store definitions are consistent across the cluster
     * 
     * @param cluster The cluster metadata
     * @param adminClient The admin client to use to retrieve the store
     *        definitions
     * @return List of store definitions
     */
    public static List<StoreDefinition> getStoreDefinition(Cluster cluster, AdminClient adminClient) {
        List<StoreDefinition> storeDefs = null;
        for(Node node: cluster.getNodes()) {
            List<StoreDefinition> storeDefList = getRebalanceStores(adminClient.getRemoteStoreDefList(node.getId())
                                                                               .getValue());
            if(storeDefs == null) {
                storeDefs = storeDefList;
            } else {

                // Compare against the previous store definitions
                if(!Utils.compareList(storeDefs, storeDefList)) {
                    throw new VoldemortException("Store definitions on node " + node.getId()
                                                 + " does not match those on other nodes");
                }
            }
        }

        if(storeDefs == null) {
            throw new VoldemortException("Could not retrieve list of store definitions correctly");
        } else {
            return storeDefs;
        }
    }

    /**
     * Given a list of store definitions returns the store definitions which the
     * rebalancing code currently supports
     * 
     * @param storeDefList List of store definitions
     * @return Filtered list of store definitions which rebalancing supports
     */
    public static List<StoreDefinition> getRebalanceStores(List<StoreDefinition> storeDefList) {
        List<StoreDefinition> storeNameList = new ArrayList<StoreDefinition>(storeDefList.size());

        for(StoreDefinition def: storeDefList) {
            if(!def.isView() && !rebalancingStoreEngineBlackList.contains(def.getType())) {
                storeNameList.add(def);
            } else {
                logger.debug("Ignoring store " + def.getName() + " for Rebalancing");
            }
        }
        return storeNameList;
    }

    /**
     * Given a list of store definitions, returns the store definition with the
     * max replication factor
     * 
     * @param storeDefList List of store definitions
     * @return The store definition with the max replication factor
     */
    public static StoreDefinition getMaxReplicationStore(List<StoreDefinition> storeDefList) {
        int maxReplication = 0;
        StoreDefinition maxStore = null;
        for(StoreDefinition def: storeDefList) {
            if(maxReplication < def.getReplicationFactor()) {
                maxReplication = def.getReplicationFactor();
                maxStore = def;
            }
        }

        return maxStore;
    }

    /**
     * Given a list of store definitions, cluster and admin client returns a
     * boolean indicating if all RO stores are in the correct format.
     * 
     * <br>
     * 
     * This function also takes into consideration nodes which are being
     * bootstrapped for the first time, in which case we can safely ignore
     * checking them
     * 
     * @param cluster Cluster metadata
     * @param storeDefs Complete list of store definitions
     * @param adminClient Admin client
     */
    public static void validateReadOnlyStores(Cluster cluster,
                                              List<StoreDefinition> storeDefs,
                                              AdminClient adminClient) {
        List<StoreDefinition> readOnlyStores = filterStores(storeDefs, true);

        if(readOnlyStores.size() == 0) {
            // No read-only stores
            return;
        }

        List<String> storeNames = RebalanceUtils.getStoreNames(readOnlyStores);
        for(Node node: cluster.getNodes()) {
            if(node.getNumberOfPartitions() != 0) {
                for(Entry<String, String> storeToStorageFormat: adminClient.getROStorageFormat(node.getId(),
                                                                                               storeNames)
                                                                           .entrySet()) {
                    if(storeToStorageFormat.getValue()
                                           .compareTo(ReadOnlyStorageFormat.READONLY_V2.getCode()) != 0) {
                        throw new VoldemortRebalancingException("Cannot rebalance since node "
                                                                + node.getId() + " has store "
                                                                + storeToStorageFormat.getKey()
                                                                + " not using format "
                                                                + ReadOnlyStorageFormat.READONLY_V2);
                    }
                }
            }
        }
    }

    /**
     * Given a list of partition plans and a set of stores, copies the store
     * names to every individual plan and creates a new list
     * 
     * @param existingPlanList Existing partition plan list
     * @param storeDefs List of store names we are rebalancing
     * @return List of updated partition plan
     */
    public static List<RebalancePartitionsInfo> updatePartitionPlanWithStores(List<RebalancePartitionsInfo> existingPlanList,
                                                                              List<StoreDefinition> storeDefs) {
        List<RebalancePartitionsInfo> plans = Lists.newArrayList();
        for(RebalancePartitionsInfo existingPlan: existingPlanList) {
            RebalancePartitionsInfo info = RebalancePartitionsInfo.create(existingPlan.toJsonString());

            // Copy over the new stores then
            info.setUnbalancedStoreList(RebalanceUtils.getStoreNames(storeDefs));

            plans.add(info);
        }

        return plans;
    }

    /**
     * Given a list of store definitions, filters the list depending on the
     * boolean
     * 
     * @param storeDefs Complete list of store definitions
     * @param isReadOnly Boolean indicating whether filter on read-only or not?
     * @return List of filtered store definition
     */
    public static List<StoreDefinition> filterStores(List<StoreDefinition> storeDefs,
                                                     final boolean isReadOnly) {
        List<StoreDefinition> filteredStores = Lists.newArrayList();
        for(StoreDefinition storeDef: storeDefs) {
            if(storeDef.getType().equals(ReadOnlyStorageConfiguration.TYPE_NAME) == isReadOnly) {
                filteredStores.add(storeDef);
            }
        }
        return filteredStores;
    }

    /**
     * Given a list of store definitions return a list of store names
     * 
     * @param storeDefList The list of store definitions
     * @return Returns a list of store names
     */
    public static List<String> getStoreNames(List<StoreDefinition> storeDefList) {
        List<String> storeList = new ArrayList<String>();
        for(StoreDefinition def: storeDefList) {
            storeList.add(def.getName());
        }
        return storeList;
    }

    /**
     * Given a list of nodes, retrieves the list of node ids
     * 
     * @param nodes The list of nodes
     * @return Returns a list of node ids
     */
    public static List<Integer> getNodeIds(List<Node> nodes) {
        List<Integer> nodeIds = new ArrayList<Integer>(nodes.size());
        for(Node node: nodes) {
            nodeIds.add(node.getId());
        }
        return nodeIds;
    }

    /**
     * Wait to shutdown service
     * 
     * @param executorService Executor service to shutdown
     * @param timeOutSec Time we wait for
     */
    public static void executorShutDown(ExecutorService executorService, int timeOutSec) {
        try {
            executorService.shutdown();
            executorService.awaitTermination(timeOutSec, TimeUnit.SECONDS);
        } catch(Exception e) {
            logger.warn("Error while stoping executor service.", e);
        }
    }

    public static ExecutorService createExecutors(int numThreads) {

        return Executors.newFixedThreadPool(numThreads, new ThreadFactory() {

            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName(r.getClass().getName());
                return thread;
            }
        });
    }
}
