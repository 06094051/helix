package com.linkedin.helix.manager.zk;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.apache.zookeeper.data.Stat;

import com.linkedin.helix.BaseDataAccessor;
import com.linkedin.helix.store.zk.ZNode;

public class WriteThroughCache<T>
{
  final ReadWriteLock                    _lock;
  final ConcurrentHashMap<String, ZNode> _wtCache;
  final BaseDataAccessor<T> _accessor;

  public WriteThroughCache(BaseDataAccessor<T> accessor, List<String> paths)
  {
    _lock = new ReentrantReadWriteLock();
    _wtCache = new ConcurrentHashMap<String, ZNode>();
    _accessor = accessor;
    
    // init cache
    if (paths != null && paths.size() > 0)
    {
      for (String path : paths)
      {
        updateRecursive(path);
      }
    }
  }
  
  private void addToParentChildSet(ConcurrentHashMap<String, ZNode> map, String path)
  {
    // add to parent's childSet
    String parentPath = new File(path).getParent();
    map.putIfAbsent(parentPath, new ZNode(parentPath, null, null));
    ZNode zNode = map.get(parentPath);

    String name = new File(path).getName();
    zNode.addChild(name);
  }

  public void update(String path, T data)
  {
    addToParentChildSet(_wtCache, path);
    _wtCache.put(path, new ZNode(path, data, null));
  }

  void updateRecursive(String path)
  {
    if (path == null)
      return;

    try
    {
      _lock.writeLock().lock();

      // update parent's childSet
      addToParentChildSet(_wtCache, path);

      // update this node
      Stat stat = new Stat();
      T readData = _accessor.get(path, stat, 0);

      ZNode zNode = _wtCache.get(path);
      if (zNode == null)
      {
        zNode = new ZNode(path, readData, stat);
        _wtCache.put(path, zNode);
      }
      else
      {
        zNode.setData(readData);
        zNode.setStat(stat);
      }

      // recursively update children nodes
      List<String> childNames = _accessor.getChildNames(path, 0);
      if (childNames != null)
      {
        for (String childName : childNames)
        {
          String childPath = path + "/" + childName;
          if (!zNode.hasChild(childName))
          {
            zNode.addChild(childName);
            updateRecursive(childPath);
          }
        }
      }
    }
    catch (ZkNoNodeException e)
    {
      // OK. someone delete znode while we are updating cache
    }
    finally
    {
      _lock.writeLock().unlock();
    }
  }
  
  
  private void removeFromParentChildSet(ConcurrentHashMap<String, ZNode> map, String path)
  {
    // remove from parent's childSet
    String parentPath = new File(path).getParent();
    ZNode zNode = map.get(parentPath);
    if (zNode != null)
    {
      String name = new File(path).getName();
      zNode.removeChild(name);
    }
  }
  
  void purgeRecursive(String path)
  {
    try
    {
      _lock.writeLock().unlock();

      // remove from parent's childSet
      removeFromParentChildSet(_wtCache, path);

      ZNode zNode = _wtCache.remove(path);
      if (zNode != null)
      {
        // recursively remove children nodes
        Set<String> childNames = zNode.getChild();
        for (String childName : childNames)
        {
          String childPath = path + "/" + childName;
          purgeRecursive(childPath);
        }
      }
    }
    finally
    {
      _lock.writeLock().unlock();
    }
  }
  
  public ZNode get(String path)
  {
    try
    {
      _lock.readLock().lock();
      return _wtCache.get(path);
    } finally
    {
      _lock.readLock().unlock();
    }
  }
  
  public boolean exists(String path)
  {
    return _wtCache.containsKey(path);
  }
  
  public void lockWrite()
  {
    _lock.writeLock().lock();
  }
  
  public void unlockWrite()
  {
    _lock.writeLock().unlock();
  }
  
  public void lockRead()
  {
    _lock.readLock().lock();
  }
  
  public void unlockRead()
  {
    _lock.readLock().unlock();
  }
}