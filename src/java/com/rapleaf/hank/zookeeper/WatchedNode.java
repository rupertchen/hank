package com.rapleaf.hank.zookeeper;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.eclipse.jetty.util.log.Log;

public abstract class WatchedNode<T> {

  private static final Logger LOG = Logger.getLogger(WatchedNode.class);

  private T value;
  private final String nodePath;
  private final ZooKeeperPlus zk;

  private final Watcher watcher = new Watcher() {
    @Override
    public void process(WatchedEvent event) {
      // this lock is important so that when changes start happening, we
      // won't run into any concurrency issues
      synchronized (WatchedNode.this) {

        if (event.getState() != KeeperState.SyncConnected) {
          value = null;
        } else {
          try {
            if (event.getType().equals(Event.EventType.NodeCreated)) {
              watchForData();
            } else if (event.getType().equals(Event.EventType.NodeDeleted)) {
              watchForCreation();
            } else if (event.getType().equals(Event.EventType.NodeDataChanged)) {
              watchForData();
            }
          } catch (KeeperException e) {
            LOG.error("Exception while trying to update our cached value for " + nodePath, e);
          } catch (InterruptedException e) {
            if (LOG.isTraceEnabled()) {
              LOG.trace("Interrupted while trying to update our cached value for " + nodePath, e);
            }
          }
        }
      }
    }
  };

  /**
   * Start watching a node, optionnaly waiting for it to be created
   *
   * @param zk
   * @param nodePath
   * @param waitForCreation
   * @throws KeeperException
   * @throws InterruptedException
   */
  protected WatchedNode(final ZooKeeperPlus zk, final String nodePath, boolean waitForCreation)
      throws KeeperException, InterruptedException {
    this.zk = zk;
    this.nodePath = nodePath;
    if (waitForCreation) {
      NodeCreationBarrier.block(zk, nodePath);
    }
    initWatch();
  }

  /**
   * Start watching a node and create the underlying node with an initial value
   *
   * @param zk
   * @param nodePath
   * @param initValue
   * @throws KeeperException
   * @throws InterruptedException
   */
  public WatchedNode(ZooKeeperPlus zk, String nodePath, T initValue)
      throws KeeperException, InterruptedException {
    this.zk = zk;
    this.nodePath = nodePath;
    // Create
    if (zk.exists(nodePath, false) == null) {
      if (Log.isDebugEnabled()) {
        LOG.debug(String.format("Creating non-existent node %s with value %s", nodePath, initValue));
      }
      try {
        zk.create(nodePath, encode(initValue), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
      } catch (KeeperException e) {
        // The node was probably created just now, after we tested its existence. Rethrow if it still does not exist
        if (zk.exists(nodePath, false) == null) {
          throw e;
        }
      }
    }
    initWatch();
  }

  private void watchForCreation() throws InterruptedException, KeeperException {
    value = null;
    zk.exists(nodePath, watcher);
  }

  private void watchForData() throws InterruptedException, KeeperException {
    if (LOG.isTraceEnabled()) {
      LOG.trace(String.format("Getting value for %s", nodePath));
    }
    value = decode(zk.getData(nodePath, watcher, new Stat()));
  }

  private void initWatch() throws KeeperException, InterruptedException {
    if (zk.exists(nodePath, watcher) != null) {
      value = decode(zk.getData(nodePath, false, new Stat()));
    } else {
      value = null;
    }
  }

  protected abstract T decode(byte[] data);

  public T get() {
    return value;
  }

  public void set(T v) throws KeeperException, InterruptedException {
    zk.setData(nodePath, encode(v), -1);
    synchronized (this) {
      value = v;
    }
  }

  protected abstract byte[] encode(T v);

}
