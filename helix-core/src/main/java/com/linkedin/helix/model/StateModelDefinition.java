package com.linkedin.helix.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.ZNRecordDecorator;

/**
 * Describe the state model
 */
public class StateModelDefinition extends ZNRecordDecorator
{
  public enum StateModelDefinitionProperty
  {
    INITIAL_STATE,
    STATE_TRANSITION_PRIORITYLIST,
    STATE_PRIORITY_LIST
  }
  private static final Logger _logger = Logger.getLogger(StateModelDefinition.class.getName());
  /**
   * State Names in priority order. Indicates the order in which states are
   * fulfilled
   */
  private final List<String> _statesPriorityList;

  /**
   * Specifies the number of instances for a given state <br>
   * -1 don't care, don't try to keep any resource in this state on any instance <br>
   * >0 any integer number greater than 0 specifies the number of instances
   * needed to be in this state <br>
   * R all instances in the preference list can be in this state <br>
   * N all instances in the cluster will be put in this state.PreferenceList
   * must be denoted as '*'
   */
  private final Map<String, String> _statesCountMap;

  private final List<String> _stateTransitionPriorityList;

  /**
   * StateTransition which is used to find the nextState given StartState and
   * FinalState
   */
  private final Map<String, Map<String, String>> _stateTransitionTable;

  public StateModelDefinition(ZNRecord record)
  {
    super(record);

    _statesPriorityList = record.getListField(StateModelDefinitionProperty.STATE_PRIORITY_LIST.toString());
    _stateTransitionPriorityList = record
        .getListField(StateModelDefinitionProperty.STATE_TRANSITION_PRIORITYLIST.toString());
    _stateTransitionTable = new HashMap<String, Map<String, String>>();
    _statesCountMap = new HashMap<String, String>();
    if (_statesPriorityList != null)
    {
      for (String state : _statesPriorityList)
      {
        Map<String, String> metaData = record.getMapField(state + ".meta");
        if (metaData != null)
        {
          if (metaData.get("count") != null)
          {
            _statesCountMap.put(state, metaData.get("count"));
          }
        }
        Map<String, String> nextData = record.getMapField(state + ".next");
        _stateTransitionTable.put(state, nextData);
      }
    }
  }

  public List<String> getStateTransitionPriorityList()
  {
    return _stateTransitionPriorityList;
  }

  public List<String> getStatesPriorityList()
  {
    return _statesPriorityList;
  }

  public String getNextStateForTransition(String fromState, String toState)
  {
    Map<String, String> map = _stateTransitionTable.get(fromState);
    if (map != null)
    {
      return map.get(toState);
    }
    return null;
  }

  public String getInitialState()
  {
    return _record.getSimpleField(StateModelDefinitionProperty.INITIAL_STATE.toString());
  }

  public String getNumInstancesPerState(String state)
  {
    return _statesCountMap.get(state);
  }

  @Override
  public boolean isValid()
  {
    if(getInitialState() == null)
    {
      _logger.error("State model does not contain init state, statemodel:" + _record.getId());
      return false;
    }
    if(_record.getListField(StateModelDefinitionProperty.STATE_PRIORITY_LIST.toString()) == null)
    {
      _logger.error("CurrentState does not contain StatesPriorityList, state model : " + _record.getId());
      return false;
    }

    // STATE_TRANSITION_PRIORITYLIST is optional
//    if(_record.getListField(StateModelDefinitionProperty.STATE_TRANSITION_PRIORITYLIST.toString()) == null)
//    {
//      _logger.error("CurrentState does not contain StateTransitionPriorityList, state model : " + _record.getId());
//      return false;
//    }
    return true;
  }
}