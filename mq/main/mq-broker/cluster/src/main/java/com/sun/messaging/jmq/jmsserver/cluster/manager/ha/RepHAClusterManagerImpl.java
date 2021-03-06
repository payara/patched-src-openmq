/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2017 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://oss.oracle.com/licenses/CDDL+GPL-1.1
 * or LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 */ 

package com.sun.messaging.jmq.jmsserver.cluster.manager.ha;

import java.util.*;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.UnknownHostException;

import com.sun.messaging.jmq.io.MQAddress;
import com.sun.messaging.jmq.io.Status;
import com.sun.messaging.jmq.util.log.*;
import com.sun.messaging.jmq.util.UID;
import com.sun.messaging.jmq.jmsserver.util.BrokerException;
import com.sun.messaging.jmq.jmsserver.config.*;
import com.sun.messaging.jmq.jmsserver.persist.api.Store;
import com.sun.messaging.jmq.jmsserver.persist.api.MigratableStoreUtil;
import com.sun.messaging.jmq.jmsserver.persist.api.StoreManager;
import com.sun.messaging.jmq.jmsserver.cluster.manager.*;
import com.sun.messaging.jmq.jmsserver.cluster.api.*;
import com.sun.messaging.jmq.jmsserver.multibroker.BrokerInfo;
import com.sun.messaging.jmq.jmsserver.resources.*;
import com.sun.messaging.jmq.jmsserver.Globals;
import org.jvnet.hk2.annotations.Service;
import javax.inject.Singleton;

// XXX FOR TEST CLASS
import java.io.*;


/**
 * This class extends ClusterManagerImpl and is used to obtain and
 * distribute cluster information in an HA cluster.
 */
@Service(name = "com.sun.messaging.jmq.jmsserver.cluster.manager.ha.RepHAClusterManagerImpl")
@Singleton
public class RepHAClusterManagerImpl extends ClusterManagerImpl 
{

    private UID localStoreSessionUID = null;

   /**
    */
   public RepHAClusterManagerImpl() 
       throws BrokerException
   {
       super();
   }


   /**
    * Returns if the cluster is "highly available".
    *
    * @return true if the cluster is HA
    * @throws RuntimeException if called before the cluster has
    *         been initialized by calling ClusterManager.setMQAddress
    * @see ClusterManager#setMQAddress
    */
   public boolean isHA() {
       if (!initialized)
           throw new RuntimeException("Cluster not initialized");

       return false;
   }

   /**
    * Reload the cluster properties from config 
    *
    */
   public void reloadConfig() throws BrokerException {
       if (!initialized)
           throw new RuntimeException("Cluster not initialized");

       String[] props = { CLUSTERURL_PROPERTY };
       config.reloadProps(Globals.getConfigName(), props, false);
   }

   /**
    * Method which initializes the broker cluster. (Called by
    * ClusterManager.setMQAddress()).
    *
    * @param address the address for the portmapper
    *
    * @see ClusterManager#setMQAddress
    * @throws BrokerException if something goes wrong during intialzation 
    */
   public String initialize(MQAddress address) 
       throws BrokerException {
       logger.log(Logger.DEBUG, "initializingCluster at " + address);

       if (!Globals.isBDBStore()) {
           throw new BrokerException(
           "Store not "+Store.BDB_STORE_TYPE+" type for cluster manage["+getClass().getSimpleName()+"]"); 
       }
       String url = super.initialize(address);

       if (getConfigBrokerCount() > 0) {
           if (Globals.getClusterID() == null) {
               throw new BrokerException("imq.cluster.clusterid must set");
           }
       }
       return url;
   }

   public ClusteredBroker newClusteredBroker(MQAddress URL,
                                   boolean isLocal, UID sid)
                                   throws BrokerException {
       ClusteredBroker  b = new RepHAClusteredBrokerImpl(this, URL, isLocal, sid);
       if (allBrokers instanceof AutoClusterBrokerMap) {
           ((ClusteredBrokerImpl)b).setConfigBroker(true);
       }
       return b;
   }

   /**
    * Retrieve the broker that creates the specified store session ID.
    * @param uid store session ID
    * @return the broker ID
    */
   public String getStoreSessionCreator(UID uid)
   {
       return null;
   }

   protected ClusteredBroker updateBrokerOnActivation(ClusteredBroker broker,
                                                      Object userData) {
       ((RepHAClusteredBrokerImpl)broker).setStoreSessionUID(
           ((BrokerInfo)userData).getBrokerAddr().getStoreSessionUID());
       return broker;
   }

   protected ClusteredBroker updateBrokerOnDeactivation(ClusteredBroker broker,
                                                      Object userData) {
       return broker;
   }

   public ClusteredBroker getBrokerByNodeName(String nodeName) 
   throws BrokerException {

       if (!initialized) {
           throw new RuntimeException("Cluster not initialized");
       }
       RepHAClusteredBrokerImpl cb = null;
       synchronized (allBrokers) {
           Iterator itr = allBrokers.values().iterator();
           while (itr.hasNext()) {

               cb = (RepHAClusteredBrokerImpl)itr.next();
               String instn = cb.getInstanceName();
               UID ss = cb.getStoreSessionUID();
               if (instn != null && ss != null) {
                   if (MigratableStoreUtil.makeEffectiveBrokerID(
                       instn, ss).equals(nodeName)) {
                       return cb;
                   }
               }
           }
       }
       return null;
   }

   /**
    * Gets the session UID associated with the local broker
    *
    * @return the broker session uid (if known)
    */
   public UID getStoreSessionUID()
   {
       if (localStoreSessionUID == null) {
           localStoreSessionUID = ((RepHAClusteredBrokerImpl)getLocalBroker()).getStoreSessionUID();
       }
       return localStoreSessionUID;
   }


   /**
    * Adds an old UID to the list of supported sessions
    * for this broker.
    *
    * @param uid the broker's store session UID that has been taken over
    */
   protected void addSupportedStoreSessionUID(UID uid) {
       super.addSupportedStoreSessionUID(uid);
   }

    /***************************************************
     * override super class methods for auto-clustering
     ***************************************************/

    protected Map initAllBrokers(MQAddress myaddr) throws BrokerException {

        String cstr = Globals.getConfig().getProperty
                      (Globals.AUTOCLUSTER_BROKERMAP_CLASS_PROP);
        if (cstr == null) {
            return super.initAllBrokers(myaddr);
        }
        try {
            Class c = Class.forName(cstr);
            Class[] paramTypes = { ClusterManagerImpl.class, MQAddress.class };
            Constructor cons = c.getConstructor(paramTypes);
            Object[] paramArgs = { this, myaddr };
            return (Map)cons.newInstance(paramArgs);
        } catch (Exception e) {
             throw new BrokerException(e.getMessage(), e);
        }
    }

    protected LinkedHashSet parseBrokerList()
    throws MalformedURLException, UnknownHostException {

       if (!(allBrokers instanceof AutoClusterBrokerMap)) {
           return super.parseBrokerList();
       }

        String val = config.getProperty(AUTOCONNECT_PROPERTY);
        if (val != null) {
             logger.log(Logger.INFO,
                 BrokerResources.W_IGNORE_PROP_SETTING,
                 AUTOCONNECT_PROPERTY+"="+val);
        }

        val = config.getProperty(Globals.IMQ
                         + ".cluster.brokerlist.manual");
        if (val != null) {
             logger.log(Logger.INFO,
                 BrokerResources.W_IGNORE_PROP_SETTING,
                 Globals.IMQ + ".cluster.brokerlist.manual"+"="+val);
        }
        LinkedHashSet brokers = new LinkedHashSet();
        synchronized(allBrokers) {
            Iterator itr = allBrokers.values().iterator();
            while (itr.hasNext()) {
                Object o = itr.next();
                RepHAClusteredBrokerImpl b = (RepHAClusteredBrokerImpl)o;
                if (!b.isLocalBroker()) {
                    brokers.add(b.getBrokerURL());
                }
            }
        }
        return brokers;
   }

    public String lookupBrokerID(MQAddress address) {

        if (!initialized) {
            throw new RuntimeException("Cluster manager is not initialized");
        }
        if (allBrokers instanceof AutoClusterBrokerMap) {
            try {
                synchronized(allBrokers) {
                    ((AutoClusterBrokerMap)allBrokers).updateMap();
                }
            } catch (BrokerException e) {
                logger.logStack(logger.WARNING, e.getMessage(), e);
            }
        }
        return super.lookupBrokerID(address);
    }


    public Iterator getConfigBrokers() {
        if (allBrokers instanceof AutoClusterBrokerMap) {
            return getKnownBrokers(true);
        }
        return super.getConfigBrokers();
    }    

    public int getConfigBrokerCount() {
        if (allBrokers instanceof AutoClusterBrokerMap) {
            return super.getKnownBrokerCount();
        }
        return super.getConfigBrokerCount();
    }

    public Iterator getKnownBrokers(boolean refresh) {

        if (!initialized) {
            throw new RuntimeException("Cluster manager is not initialized");
        }

        if (refresh && (allBrokers instanceof AutoClusterBrokerMap)) {
            try {
                synchronized(allBrokers) {
                    ((AutoClusterBrokerMap)allBrokers).updateMap(true);
                }
            } catch (BrokerException e) {
                logger.logStack(logger.WARNING, e.getMessage(), e);
            }
        }
        return super.getKnownBrokers(refresh);
    }

    public ClusteredBroker getBroker(String brokerid) {

        if (allBrokers instanceof AutoClusterBrokerMap) {
            ClusteredBroker cb = super.getBroker(brokerid);
            if (cb != null) {
                return cb;
            }
            try {
                synchronized(allBrokers) {
                    ((AutoClusterBrokerMap)allBrokers).updateMap(true);
                }
            } catch (BrokerException e) {
                logger.logStack(logger.WARNING, e.getMessage(), e);
            }
        }
        return super.getBroker(brokerid);
    }

    /**
     */
    protected String addBroker(MQAddress url,
        boolean isLocal, boolean isConfig, UID uid)
        throws NoSuchElementException, BrokerException {

        if (!initialized) {
            throw new RuntimeException("Cluster not initialized");
        }
 
        if (!(allBrokers instanceof AutoClusterBrokerMap)) {
            return super.addBroker(url, isLocal, isConfig, uid);
        }

        String name = null;
        ClusteredBroker cb = null;
        if (isLocal) {
            synchronized(allBrokers) {
                name  = lookupBrokerID(url);
                if (name == null) {
                    cb = newClusteredBroker(url, isLocal, uid);
                    name = cb.getBrokerName();
                } else {
                    cb = getBroker(name);
                }
                synchronized(allBrokers) {
                    allBrokers.put(name, cb);
                }
            }
        } else {
            name = lookupBrokerID(url);
            if (name != null) {
                cb = getBroker(name);
            }
            if (name == null || cb == null) {
                throw new NoSuchElementException("Unknown broker "+url);
            }
        }
        if (uid != null) {
            cb.setBrokerSessionUID(uid);
        }
        if (isLocal) {
           cb.setStatus(BrokerStatus.ACTIVATE_BROKER, null);
        }
        brokerChanged(ClusterReason.ADDED,
                      cb.getBrokerName(), null, cb, uid, null);
        return name;
    }


    protected void setupListeners() {
       if (allBrokers instanceof AutoClusterBrokerMap) {
           config.addListener(TRANSPORT_PROPERTY, this);
           config.addListener(HOST_PROPERTY, this);
           config.addListener(PORT_PROPERTY, this);
           return;
       }
       super.setupListeners(); 
    }
}

