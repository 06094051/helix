package org.apache.helix.controller.stages;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.helix.HelixConstants;
import org.apache.helix.HelixDefinedState;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerProperties;
import org.apache.helix.ZNRecord;
import org.apache.helix.HelixConstants.StateModelToken;
import org.apache.helix.controller.pipeline.AbstractBaseStage;
import org.apache.helix.controller.pipeline.StageException;
import org.apache.helix.model.CurrentState;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.LiveInstance;
import org.apache.helix.model.Partition;
import org.apache.helix.model.Resource;
import org.apache.helix.model.StateModelDefinition;
import org.apache.helix.model.IdealState.IdealStateModeProperty;
import org.apache.log4j.Logger;


/**
 * For partition compute best possible (instance,state) pair based on
 * IdealState,StateModel,LiveInstance
 *
 */
// TODO: refactor this
public class BestPossibleStateCalcStage extends AbstractBaseStage
{
  private static final Logger logger =
      Logger.getLogger(BestPossibleStateCalcStage.class.getName());

  @Override
  public void process(ClusterEvent event) throws Exception
  {
    long startTime = System.currentTimeMillis();
    logger.info("START BestPossibleStateCalcStage.process()");

    CurrentStateOutput currentStateOutput =
        event.getAttribute(AttributeName.CURRENT_STATE.toString());
    Map<String, Resource> resourceMap =
        event.getAttribute(AttributeName.RESOURCES.toString());
    ClusterDataCache cache = event.getAttribute("ClusterDataCache");

    if (currentStateOutput == null || resourceMap == null || cache == null)
    {
      throw new StageException("Missing attributes in event:" + event
          + ". Requires CURRENT_STATE|RESOURCES|DataCache");
    }

    BestPossibleStateOutput bestPossibleStateOutput =
        compute(event, resourceMap, currentStateOutput);
    event.addAttribute(AttributeName.BEST_POSSIBLE_STATE.toString(),
                       bestPossibleStateOutput);

    long endTime = System.currentTimeMillis();
    logger.info("END BestPossibleStateCalcStage.process(). took: "
        + (endTime - startTime) + " ms");
  }

  private BestPossibleStateOutput compute(ClusterEvent event,
                                          Map<String, Resource> resourceMap,
                                          CurrentStateOutput currentStateOutput)
  {
    // for each ideal state
    // read the state model def
    // for each resource
    // get the preference list
    // for each instanceName check if its alive then assign a state
    ClusterDataCache cache = event.getAttribute("ClusterDataCache");
    HelixManager manager = event.getAttribute("helixmanager");

    BestPossibleStateOutput output = new BestPossibleStateOutput();

    for (String resourceName : resourceMap.keySet())
    {
      logger.debug("Processing resource:" + resourceName);

      Resource resource = resourceMap.get(resourceName);
      // Ideal state may be gone. In that case we need to get the state model name
      // from the current state
      IdealState idealState = cache.getIdealState(resourceName);

      String stateModelDefName;

      if (idealState == null)
      {
        // if ideal state is deleted, use an empty one
        logger.info("resource:" + resourceName + " does not exist anymore");
        stateModelDefName = currentStateOutput.getResourceStateModelDef(resourceName);
        idealState = new IdealState(resourceName);
      }
      else
      {
        stateModelDefName = idealState.getStateModelDefRef();
      }

      StateModelDefinition stateModelDef = cache.getStateModelDef(stateModelDefName);
      if (idealState.getIdealStateMode() == IdealStateModeProperty.AUTO_REBALANCE)
      {
        calculateAutoBalancedIdealState(cache,
                                        idealState,
                                        stateModelDef,
                                        currentStateOutput);
      }


      for (Partition partition : resource.getPartitions())
      {
        Map<String, String> currentStateMap =
            currentStateOutput.getCurrentStateMap(resourceName, partition);

        Map<String, String> bestStateForPartition;
        Set<String> disabledInstancesForPartition =
            cache.getDisabledInstancesForPartition(partition.toString());

        if (idealState.getIdealStateMode() == IdealStateModeProperty.CUSTOMIZED)
        {
          Map<String, String> idealStateMap =
              idealState.getInstanceStateMap(partition.getPartitionName());
          bestStateForPartition =
              computeCustomizedBestStateForPartition(cache,
                                                     stateModelDef,
                                                     idealStateMap,
                                                     currentStateMap,
                                                     disabledInstancesForPartition,
                                                     manager.getProperties());
        }
        else
        // both AUTO and AUTO_REBALANCE mode
        {
          List<String> instancePreferenceList =
              getPreferenceList(cache, partition, idealState, stateModelDef);

          bestStateForPartition =
              computeAutoBestStateForPartition(cache,
                                               stateModelDef,
                                               instancePreferenceList,
                                               currentStateMap,
                                               disabledInstancesForPartition,
                                               manager.getProperties());
        }
        output.setState(resourceName, partition, bestStateForPartition);
      }
    }
    return output;
  }

  /**
   * Compute best state for resource in AUTO_REBALANCE ideal state mode. the algorithm
   * will make sure that the master partition are evenly distributed; Also when instances
   * are added / removed, the amount of diff in master partitions are minimized
   *
   * @param cache
   * @param idealState
   * @param instancePreferenceList
   * @param stateModelDef
   * @param currentStateOutput
   * @return
   */
  private void calculateAutoBalancedIdealState(ClusterDataCache cache,
                                               IdealState idealState,
                                               StateModelDefinition stateModelDef,
                                               CurrentStateOutput currentStateOutput)
  {
    String topStateValue = stateModelDef.getStatesPriorityList().get(0);
    Set<String> liveInstances = cache._liveInstanceMap.keySet();
    Set<String> taggedInstances = new HashSet<String>();

    // If there are instances tagged with resource name, use only those instances
    if(idealState.getInstanceGroupTag() != null)
    {
      for(String instanceName : liveInstances)
      {
        if(cache._instanceConfigMap.get(instanceName).containsTag(idealState.getInstanceGroupTag()))
        {
          taggedInstances.add(instanceName);
        }
      }
    }
    if(taggedInstances.size() > 0)
    {
      logger.info("found the following instances with tag " + idealState.getResourceName() + " " + taggedInstances);
      liveInstances = taggedInstances;
    }
    // Obtain replica number
    int replicas = 1;
    try
    {
      replicas = Integer.parseInt(idealState.getReplicas());
    }
    catch (Exception e)
    {
      logger.error("", e);
    }
    // Init for all partitions with empty list
    Map<String, List<String>> defaultListFields = new TreeMap<String, List<String>>();
    List<String> emptyList = new ArrayList<String>(0);
    for (String partition : idealState.getPartitionSet())
    {
      defaultListFields.put(partition, emptyList);
    }
    idealState.getRecord().setListFields(defaultListFields);
    // Return if no live instance
    if (liveInstances.size() == 0)
    {
      logger.info("No live instances, return. Idealstate : "
          + idealState.getResourceName());
      return;
    }
    Map<String, List<String>> masterAssignmentMap = new HashMap<String, List<String>>();
    for (String instanceName : liveInstances)
    {
      masterAssignmentMap.put(instanceName, new ArrayList<String>());
    }
    Set<String> orphanedPartitions = new HashSet<String>();
    orphanedPartitions.addAll(idealState.getPartitionSet());
    // Go through all current states and fill the assignments
    for (String liveInstanceName : liveInstances)
    {
      CurrentState currentState =
          cache.getCurrentState(liveInstanceName,
                                cache.getLiveInstances()
                                     .get(liveInstanceName)
                                     .getSessionId()).get(idealState.getId());
      if (currentState != null)
      {
        Map<String, String> partitionStates = currentState.getPartitionStateMap();
        for (String partitionName : partitionStates.keySet())
        {
          String state = partitionStates.get(partitionName);
          if (state.equals(topStateValue))
          {
            masterAssignmentMap.get(liveInstanceName).add(partitionName);
            orphanedPartitions.remove(partitionName);
          }
        }
      }
    }
    List<String> orphanedPartitionsList = new ArrayList<String>();
    orphanedPartitionsList.addAll(orphanedPartitions);
    int maxPartitionsPerInstance = idealState.getMaxPartitionsPerInstance();
    normalizeAssignmentMap(masterAssignmentMap, orphanedPartitionsList, maxPartitionsPerInstance);
    idealState.getRecord()
              .setListFields(generateListFieldFromMasterAssignment(masterAssignmentMap,
                                                                   replicas));

  }

  /**
   * Given the current master assignment map and the partitions not hosted, generate an
   * evenly distributed partition assignment map
   *
   * @param masterAssignmentMap
   *          current master assignment map
   * @param orphanPartitions
   *          partitions not hosted by any instance
   * @return
   */
  private void normalizeAssignmentMap(Map<String, List<String>> masterAssignmentMap,
                                      List<String> orphanPartitions, int maxPartitionsPerInstance)
  {
    int totalPartitions = 0;
    String[] instanceNames = new String[masterAssignmentMap.size()];
    masterAssignmentMap.keySet().toArray(instanceNames);
    Arrays.sort(instanceNames);
    // Find out total partition number
    for (String key : masterAssignmentMap.keySet())
    {
      totalPartitions += masterAssignmentMap.get(key).size();
      Collections.sort(masterAssignmentMap.get(key));
    }
    totalPartitions += orphanPartitions.size();

    // Find out how many partitions an instance should host
    int partitionNumber = totalPartitions / masterAssignmentMap.size();
    int leave = totalPartitions % masterAssignmentMap.size();

    for (int i = 0; i < instanceNames.length; i++)
    {
      int targetPartitionNo = leave > 0 ? (partitionNumber + 1) : partitionNumber;
      leave--;
      // For hosts that has more partitions, move those partitions to "orphaned"
      while (masterAssignmentMap.get(instanceNames[i]).size() > targetPartitionNo)
      {
        int lastElementIndex = masterAssignmentMap.get(instanceNames[i]).size() - 1;
        orphanPartitions.add(masterAssignmentMap.get(instanceNames[i])
                                                .get(lastElementIndex));
        masterAssignmentMap.get(instanceNames[i]).remove(lastElementIndex);
      }
    }
    leave = totalPartitions % masterAssignmentMap.size();
    Collections.sort(orphanPartitions);
    // Assign "orphaned" partitions to hosts that do not have enough partitions
    for (int i = 0; i < instanceNames.length; i++)
    {
      int targetPartitionNo = leave > 0 ? (partitionNumber + 1) : partitionNumber;
      leave--;
      if(targetPartitionNo > maxPartitionsPerInstance)
      {
        targetPartitionNo = maxPartitionsPerInstance;
      }
      while (masterAssignmentMap.get(instanceNames[i]).size() < targetPartitionNo)
      {
        int lastElementIndex = orphanPartitions.size() - 1;
        masterAssignmentMap.get(instanceNames[i])
                           .add(orphanPartitions.get(lastElementIndex));
        orphanPartitions.remove(lastElementIndex);
      }
    }
    if (orphanPartitions.size() > 0)
    {
      logger.warn("orphanPartitions still contains elements");
    }
  }

  /**
   * Generate full preference list from the master assignment map evenly distribute the
   * slave partitions mastered on a host to other hosts
   *
   * @param masterAssignmentMap
   *          current master assignment map
   * @param orphanPartitions
   *          partitions not hosted by any instance
   * @return
   */
  Map<String, List<String>> generateListFieldFromMasterAssignment(Map<String, List<String>> masterAssignmentMap,
                                                                  int replicas)
  {
    Map<String, List<String>> listFields = new HashMap<String, List<String>>();
    int slaves = replicas - 1;
    String[] instanceNames = new String[masterAssignmentMap.size()];
    masterAssignmentMap.keySet().toArray(instanceNames);
    Arrays.sort(instanceNames);

    for (int i = 0; i < instanceNames.length; i++)
    {
      String instanceName = instanceNames[i];
      List<String> otherInstances = new ArrayList<String>(masterAssignmentMap.size() - 1);
      for (int x = 0; x < instanceNames.length - 1; x++)
      {
        int index = (x + i + 1) % instanceNames.length;
        otherInstances.add(instanceNames[index]);
      }

      List<String> partitionList = masterAssignmentMap.get(instanceName);
      for (int j = 0; j < partitionList.size(); j++)
      {
        String partitionName = partitionList.get(j);
        listFields.put(partitionName, new ArrayList<String>());
        listFields.get(partitionName).add(instanceName);

        int slavesCanAssign = Math.min(slaves, otherInstances.size());
        for (int k = 0; k < slavesCanAssign; k++)
        {
          int index = (j + k + 1) % otherInstances.size();
          listFields.get(partitionName).add(otherInstances.get(index));
        }
      }
    }
    return listFields;
  }

  /**
   * Is participant version support error->dropped transition
   */
  private boolean isDropErrorSupported(HelixManagerProperties properties,
                                       ClusterDataCache cache,
                                       String instance)
  {
    if (properties == null)
    {
      return false;
    }

    LiveInstance liveInstance = cache.getLiveInstances().get(instance);
    String participantVersion = null;
    if (liveInstance != null) {
      participantVersion = liveInstance.getHelixVersion();
    }

    return properties.isFeatureSupported("drop_error_partition", participantVersion);
  }

  private boolean isNotError(Map<String, String> currentStateMap, String instance)
  {
    return currentStateMap == null
        || currentStateMap.get(instance) == null
        || !currentStateMap.get(instance).equals(HelixDefinedState.ERROR.toString());
  }

  /**
   * compute best state for resource in AUTO ideal state mode
   *
   * @param cache
   * @param stateModelDef
   * @param instancePreferenceList
   * @param currentStateMap
   *          : instance->state for each partition
   * @param disabledInstancesForPartition
   * @return
   */
  private Map<String, String> computeAutoBestStateForPartition(ClusterDataCache cache,
                                                               StateModelDefinition stateModelDef,
                                                               List<String> instancePreferenceList,
                                                               Map<String, String> currentStateMap,
                                                               Set<String> disabledInstancesForPartition,
                                                               HelixManagerProperties properties)
  {
    Map<String, String> instanceStateMap = new HashMap<String, String>();

    // if the ideal state is deleted, instancePreferenceList will be empty and
    // we should drop all resources.
    if (currentStateMap != null)
    {
      for (String instance : currentStateMap.keySet())
      {
        boolean isDropErrorSupported = isDropErrorSupported(properties, cache, instance);
        boolean isNotError = isNotError(currentStateMap, instance);

        if ((instancePreferenceList == null || !instancePreferenceList.contains(instance))
            && !disabledInstancesForPartition.contains(instance))
        {
          if (isDropErrorSupported || isNotError)
          {
            // if dropped and not disabled, transit to DROPPED
            instanceStateMap.put(instance, HelixDefinedState.DROPPED.toString());
          }
        }
        else if ( isNotError && disabledInstancesForPartition.contains(instance))
        {
          // if disabled and not in ERROR state, transit to initial-state (e.g. OFFLINE)
          instanceStateMap.put(instance, stateModelDef.getInitialState());
        }
      }
    }

    // ideal state is deleted
    if (instancePreferenceList == null)
    {
      return instanceStateMap;
    }

    List<String> statesPriorityList = stateModelDef.getStatesPriorityList();
    boolean assigned[] = new boolean[instancePreferenceList.size()];

    Map<String, LiveInstance> liveInstancesMap = cache.getLiveInstances();

    for (String state : statesPriorityList)
    {
      String num = stateModelDef.getNumInstancesPerState(state);
      int stateCount = -1;
      if ("N".equals(num))
      {
        Set<String> liveAndEnabled = new HashSet<String>(liveInstancesMap.keySet());
        liveAndEnabled.removeAll(disabledInstancesForPartition);
        stateCount = liveAndEnabled.size();
      }
      else if ("R".equals(num))
      {
        stateCount = instancePreferenceList.size();
      }
      else
      {
        try
        {
          stateCount = Integer.parseInt(num);
        }
        catch (Exception e)
        {
          logger.error("Invalid count for state:" + state + " ,count=" + num);
        }
      }
      if (stateCount > -1)
      {
        int count = 0;
        for (int i = 0; i < instancePreferenceList.size(); i++)
        {
          String instanceName = instancePreferenceList.get(i);

          boolean notInErrorState = isNotError(currentStateMap, instanceName);

          if (liveInstancesMap.containsKey(instanceName) && !assigned[i]
              && notInErrorState && !disabledInstancesForPartition.contains(instanceName))
          {
            instanceStateMap.put(instanceName, state);
            count = count + 1;
            assigned[i] = true;
            if (count == stateCount)
            {
              break;
            }
          }
        }
      }
    }
    return instanceStateMap;
  }

  /**
   * compute best state for resource in CUSTOMIZED ideal state mode
   *
   * @param cache
   * @param stateModelDef
   * @param idealStateMap
   * @param currentStateMap
   * @param disabledInstancesForPartition
   * @return
   */
  private Map<String, String> computeCustomizedBestStateForPartition(ClusterDataCache cache,
                                                                     StateModelDefinition stateModelDef,
                                                                     Map<String, String> idealStateMap,
                                                                     Map<String, String> currentStateMap,
                                                                     Set<String> disabledInstancesForPartition,
                                                                     HelixManagerProperties properties)
  {
    Map<String, String> instanceStateMap = new HashMap<String, String>();

    // if the ideal state is deleted, idealStateMap will be null/empty and
    // we should drop all resources.
    if (currentStateMap != null)
    {
      for (String instance : currentStateMap.keySet())
      {
        boolean isDropErrorSupported = isDropErrorSupported(properties, cache, instance);
        boolean isNotError = isNotError(currentStateMap, instance);

        if ((idealStateMap == null || !idealStateMap.containsKey(instance))
            && !disabledInstancesForPartition.contains(instance))
        {
          if (isDropErrorSupported || isNotError)
          {
            // if dropped and not disabled, transit to DROPPED
            instanceStateMap.put(instance, HelixDefinedState.DROPPED.toString());
          }
        }
        else if (isNotError && disabledInstancesForPartition.contains(instance))
        {
          // if disabled and not in ERROR state, transit to initial-state (e.g. OFFLINE)
          instanceStateMap.put(instance, stateModelDef.getInitialState());
        }
      }
    }

    // ideal state is deleted
    if (idealStateMap == null)
    {
      return instanceStateMap;
    }

    Map<String, LiveInstance> liveInstancesMap = cache.getLiveInstances();
    for (String instance : idealStateMap.keySet())
    {
      boolean notInErrorState = isNotError(currentStateMap, instance);

      if (liveInstancesMap.containsKey(instance) && notInErrorState
          && !disabledInstancesForPartition.contains(instance))
      {
        instanceStateMap.put(instance, idealStateMap.get(instance));
      }
    }

    return instanceStateMap;
  }

  private List<String> getPreferenceList(ClusterDataCache cache,
                                         Partition resource,
                                         IdealState idealState,
                                         StateModelDefinition stateModelDef)
  {
    List<String> listField = idealState.getPreferenceList(resource.getPartitionName());

    if (listField != null && listField.size() == 1
        && StateModelToken.ANY_LIVEINSTANCE.toString().equals(listField.get(0)))
    {
      Map<String, LiveInstance> liveInstances = cache.getLiveInstances();
      List<String> prefList = new ArrayList<String>(liveInstances.keySet());
      Collections.sort(prefList);
      return prefList;
    }
    else
    {
      return listField;
    }
  }
}
