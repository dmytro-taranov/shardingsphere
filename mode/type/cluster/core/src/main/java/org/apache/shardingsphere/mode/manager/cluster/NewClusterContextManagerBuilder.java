/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.mode.manager.cluster;

import com.google.common.base.Preconditions;
import org.apache.shardingsphere.infra.instance.ComputeNodeInstance;
import org.apache.shardingsphere.infra.instance.InstanceContext;
import org.apache.shardingsphere.infra.instance.InstanceContextAware;
import org.apache.shardingsphere.infra.util.eventbus.EventBusContext;
import org.apache.shardingsphere.infra.util.spi.type.typed.TypedSPILoader;
import org.apache.shardingsphere.metadata.persist.NewMetaDataPersistService;
import org.apache.shardingsphere.mode.lock.GlobalLockContext;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.mode.manager.ContextManagerAware;
import org.apache.shardingsphere.mode.manager.ContextManagerBuilder;
import org.apache.shardingsphere.mode.manager.ContextManagerBuilderParameter;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.RegistryCenter;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.workerid.generator.ClusterWorkerIdGenerator;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.subscriber.NewContextManagerSubscriberFacade;
import org.apache.shardingsphere.mode.metadata.MetaDataContexts;
import org.apache.shardingsphere.mode.metadata.NewMetaDataContextsFactory;
import org.apache.shardingsphere.mode.repository.cluster.ClusterPersistRepository;
import org.apache.shardingsphere.mode.repository.cluster.ClusterPersistRepositoryConfiguration;

import java.sql.SQLException;

/**
 * TODO replace the old implementation after meta data refactor completed
 * New cluster context manager builder.
 */
public final class NewClusterContextManagerBuilder implements ContextManagerBuilder {
    
    @Override
    public ContextManager build(final ContextManagerBuilderParameter param) throws SQLException {
        ClusterPersistRepository repository = getClusterPersistRepository((ClusterPersistRepositoryConfiguration) param.getModeConfiguration().getRepository());
        RegistryCenter registryCenter = new RegistryCenter(repository, new EventBusContext(), param.getInstanceMetaData(), param.getDatabaseConfigs());
        InstanceContext instanceContext = buildInstanceContext(registryCenter, param);
        if (registryCenter.getRepository() instanceof InstanceContextAware) {
            ((InstanceContextAware) registryCenter.getRepository()).setInstanceContext(instanceContext);
        }
        NewMetaDataPersistService persistService = new NewMetaDataPersistService(repository);
        MetaDataContexts metaDataContexts = NewMetaDataContextsFactory.create(persistService, param, instanceContext, registryCenter.getStorageNodeStatusService().loadStorageNodes());
        ContextManager result = new ContextManager(metaDataContexts, instanceContext);
        setContextManagerAware(result);
        registerOnline(persistService, registryCenter, param, result);
        return result;
    }
    
    private ClusterPersistRepository getClusterPersistRepository(final ClusterPersistRepositoryConfiguration config) {
        Preconditions.checkNotNull(config, "Cluster persist repository configuration cannot be null.");
        ClusterPersistRepository result = TypedSPILoader.getService(ClusterPersistRepository.class, config.getType(), config.getProps());
        result.init(config);
        return result;
    }
    
    private InstanceContext buildInstanceContext(final RegistryCenter registryCenter, final ContextManagerBuilderParameter param) {
        return new InstanceContext(new ComputeNodeInstance(param.getInstanceMetaData()), new ClusterWorkerIdGenerator(registryCenter, param.getInstanceMetaData()),
                param.getModeConfiguration(), new ClusterModeContextManager(), new GlobalLockContext(registryCenter.getGlobalLockPersistService()), registryCenter.getEventBusContext());
    }
    
    private void setContextManagerAware(final ContextManager contextManager) {
        ((ContextManagerAware) contextManager.getInstanceContext().getModeContextManager()).setContextManagerAware(contextManager);
    }
    
    private void registerOnline(final NewMetaDataPersistService persistService, final RegistryCenter registryCenter, final ContextManagerBuilderParameter param, final ContextManager contextManager) {
        loadClusterStatus(registryCenter, contextManager);
        contextManager.getInstanceContext().getInstance().setLabels(param.getLabels());
        contextManager.getInstanceContext().getAllClusterInstances().addAll(registryCenter.getComputeNodeStatusService().loadAllComputeNodeInstances());
        new NewContextManagerSubscriberFacade(persistService, registryCenter, contextManager);
        registryCenter.onlineInstance(contextManager.getInstanceContext().getInstance());
    }
    
    private void loadClusterStatus(final RegistryCenter registryCenter, final ContextManager contextManager) {
        registryCenter.persistClusterState(contextManager);
        contextManager.updateClusterState(registryCenter.getClusterStatusService().loadClusterStatus());
    }
    
    @Override
    public String getType() {
        return "New_Cluster";
    }
}
