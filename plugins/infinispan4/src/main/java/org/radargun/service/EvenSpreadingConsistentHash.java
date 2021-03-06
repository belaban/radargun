package org.radargun.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.distribution.ch.DefaultConsistentHash;
import org.infinispan.distribution.ch.TopologyInfo;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.Immutables;
import org.radargun.logging.Log;
import org.radargun.logging.LogFactory;
import org.radargun.stages.cache.generators.ObjectKeyGenerator;

/**
 * @author Mircea Markus &lt;Mircea.Markus@jboss.com&gt;
 */
public class EvenSpreadingConsistentHash implements ConsistentHash {

   private static Log log = LogFactory.getLog(EvenSpreadingConsistentHash.class);

   /**
    * Why static? because the consistent hash is recreated when cluster changes and there's no other way to pass these
    * across
    */
   private volatile static int threadCountPerNode = -1;
   private volatile static int keysPerThread = -1;
   private volatile DefaultConsistentHash existing;

   private final List<Address> cachesList = new ArrayList<Address>();


   public EvenSpreadingConsistentHash() {//needed for UT
      existing = new DefaultConsistentHash();
   }


   @Override
   public List<Address> locate(Object key, int replCount) {
      if(! (key instanceof ObjectKeyGenerator.ObjectKey)) {
         if (log.isTraceEnabled()) log.trace("Delegating key " + key + " to default CH");
         return existing.locate(key, replCount);
      }

      if (threadCountPerNode <= 0 || keysPerThread <= 0) throw new IllegalStateException("keysPerThread and threadCountPerNode need to be set!");

      Set<Address> caches = existing.getCaches();
      int clusterSize = caches.size();

      long keyIndexInCluster = getSequenceNumber((ObjectKeyGenerator.ObjectKey) key);
      int firstIndex = (int)(keyIndexInCluster % caches.size());

      List<Address> result = new ArrayList<Address>();

      for (int i = 0; i < replCount; i++) {
         Address address = cachesList.get((firstIndex + i) % clusterSize);
         result.add(address);
         if (result.size() == replCount) break;
      }
      if (log.isTraceEnabled())
         log.trace("Handling key " + key + ", clusterIndex==" + keyIndexInCluster +" and EvenSpreadingConsistentHash --> " + result);

      return Collections.unmodifiableList(result);
   }

   private long getSequenceNumber(ObjectKeyGenerator.ObjectKey key) {
      return key.getKeyIndexInCluster(threadCountPerNode, keysPerThread);
   }

   public void init(int threadCountPerNode, int keysPerThread) {
      log.trace("Setting threadCountPerNode =" + threadCountPerNode + " and keysPerThread = " + keysPerThread);
      this.threadCountPerNode = threadCountPerNode;
      this.keysPerThread = keysPerThread;
   }

   /**
    * No need to implement this: https://issues.jboss.org/browse/ISPN-899
    */
   @Override
   public int getHashId(Address a) {
      return 0;
   }

   /**
    * No need to implement this: https://issues.jboss.org/browse/ISPN-899
    */
   @Override
   public int getHashSpace() {
      return 0;
   }

   //following methods should only be used during rehashing, so no point in implementing them


   @Override
   public List<Address> getStateProvidersOnLeave(Address leaver, int replCount) {
      return existing.getStateProvidersOnLeave(leaver, replCount);
   }

   @Override
   public List<Address> getStateProvidersOnJoin(Address joiner, int replCount) {
      return existing.getStateProvidersOnJoin(joiner, replCount);
   }

   @Override
   public List<Address> getBackupsForNode(Address node, int replCount) {
      if (log.isTraceEnabled()) log.trace("getBackupsForNode (" + node +")");
      return existing.getBackupsForNode(node, replCount);
   }

   @Override
   public void setCaches(Set<Address> caches) {
      existing.setCaches(caches);

      cachesList.addAll(caches);
      Collections.sort(cachesList, new Comparator<Address>() {
         @Override
         public int compare(Address o1, Address o2) {
            return o1.toString().compareTo(o2.toString());
         }
      });
   }

   @Override
   public void setTopologyInfo(TopologyInfo topologyInfo) {
      existing.setTopologyInfo(topologyInfo);
   }

   public Map<Object, List<Address>> locateAll(Collection<Object> keys, int replCount) {
      Map<Object, List<Address>> locations = new HashMap<Object, List<Address>>();
      for (Object k : keys) locations.put(k, locate(k, replCount));
      return locations;
   }

   public boolean isKeyLocalToAddress(Address a, Object key, int replCount) {
      // simple, brute-force impl
      return locate(key, replCount).contains(a);
   }

   @Override
   public Set<Address> getCaches() {
      return existing.getCaches();
   }
}
