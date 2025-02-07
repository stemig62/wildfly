/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.connector.subsystems.datasources;

import static org.jboss.as.connector.subsystems.datasources.Constants.JNDI_NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ENABLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;

import javax.sql.DataSource;
import java.util.List;

import org.jboss.as.connector.logging.ConnectorLogger;
import org.jboss.as.connector.services.datasources.statistics.DataSourceStatisticsService;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.naming.deployment.ContextNames;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;

/**
 * Operation handler responsible for disabling an existing data-source.
 *
 * @author John Bailey
 */
public class DataSourceDisable implements OperationStepHandler {
    static final DataSourceDisable LOCAL_INSTANCE = new DataSourceDisable(false);
    static final DataSourceDisable XA_INSTANCE = new DataSourceDisable(true);

    private final boolean xa;

    public DataSourceDisable(boolean xa) {
        super();
        this.xa = xa;
    }

    public void execute(OperationContext context, ModelNode operation) {

        final ManagementResourceRegistration datasourceRegistration = context.getResourceRegistrationForUpdate();
        final Resource resource = context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS);
        final ModelNode model = resource.getModel();
        model.get(ENABLED).set(false);

        if (context.isNormalServer()) {

            DataSourceStatisticsService.removeStatisticsResources(resource);

            if (context.isResourceServiceRestartAllowed()) {
                context.addStep(new OperationStepHandler() {
                    public void execute(final OperationContext context, ModelNode operation) throws OperationFailedException {

                        final ModelNode address = operation.require(OP_ADDR);
                        final String dsName = PathAddress.pathAddress(address).getLastElement().getValue();
                        final String jndiName = JNDI_NAME.resolveModelAttribute(context, model).asString();

                        final ServiceRegistry registry = context.getServiceRegistry(true);

                        final ServiceName dataSourceServiceName = context.getCapabilityServiceName(Capabilities.DATA_SOURCE_CAPABILITY_NAME, dsName, DataSource.class);
                        final ServiceController<?> dataSourceController = registry.getService(dataSourceServiceName);
                        if (dataSourceController != null) {
                            if (ServiceController.State.UP.equals(dataSourceController.getState())) {
                                dataSourceController.setMode(ServiceController.Mode.NEVER);
                            } else {
                                throw new OperationFailedException(ConnectorLogger.ROOT_LOGGER.serviceNotEnabled("Data-source", dsName));
                            }
                        } else {
                            throw new OperationFailedException(ConnectorLogger.ROOT_LOGGER.serviceNotAvailable("Data-source", dsName));
                        }
                        context.removeService(CommonDeploymentService.SERVICE_NAME_BASE.append(jndiName));
                        context.removeService(dataSourceServiceName.append(Constants.STATISTICS));
                        final ServiceName referenceServiceName = DataSourceReferenceFactoryService.SERVICE_NAME_BASE.append(dsName);
                        final ServiceController<?> referenceController = registry.getService(referenceServiceName);
                        if (referenceController != null) {
                            context.removeService(referenceController);
                        }

                        final ServiceName binderServiceName = ContextNames.bindInfoFor(jndiName).getBinderServiceName();

                        final ServiceController<?> binderController = registry.getService(binderServiceName);
                        if (binderController != null) {
                            context.removeService(binderController);
                        }

                        final ServiceName dataSourceConfigServiceName = DataSourceConfigService.SERVICE_NAME_BASE.append(dsName);
                        final ServiceController<?> dataSourceConfigController = registry.getService(dataSourceConfigServiceName);


                        final List<ServiceName> serviceNames = registry.getServiceNames();


                        final ServiceName xaDataSourceConfigServiceName = XADataSourceConfigService.SERVICE_NAME_BASE.append(dsName);
                        final ServiceController<?> xaDataSourceConfigController = registry.getService(xaDataSourceConfigServiceName);


                        for (ServiceName name : serviceNames) {
                            if (dataSourceConfigServiceName.append("connection-properties").isParentOf(name)) {
                                final ServiceController<?> connPropertyController = registry.getService(name);

                                if (connPropertyController != null) {
                                    if (ServiceController.State.UP.equals(connPropertyController.getState())) {
                                        connPropertyController.setMode(ServiceController.Mode.NEVER);
                                    } else {
                                        throw new OperationFailedException(ConnectorLogger.ROOT_LOGGER.serviceAlreadyStarted("Data-source.connectionProperty", name));
                                    }
                                } else {
                                    throw new OperationFailedException(ConnectorLogger.ROOT_LOGGER.serviceNotAvailable("Data-source.connectionProperty", name));
                                }
                            }
                            if (xaDataSourceConfigServiceName.append("xa-datasource-properties").isParentOf(name)) {
                                final ServiceController<?> xaConfigPropertyController = registry.getService(name);

                                if (xaConfigPropertyController != null) {
                                    if (ServiceController.State.UP.equals(xaConfigPropertyController.getState())) {
                                        xaConfigPropertyController.setMode(ServiceController.Mode.NEVER);
                                    } else {
                                        throw new OperationFailedException(ConnectorLogger.ROOT_LOGGER.serviceAlreadyStarted("Data-source.xa-config-property", name));
                                    }
                                } else {
                                    throw new OperationFailedException(ConnectorLogger.ROOT_LOGGER.serviceNotAvailable("Data-source.xa-config-property", name));
                                }
                            }
                        }


                        if (xaDataSourceConfigController != null) {
                            context.removeService(xaDataSourceConfigController);
                        }

                        if (dataSourceConfigController != null) {
                            context.removeService(dataSourceConfigController);
                        }

                        context.completeStep(new OperationContext.RollbackHandler() {
                            @Override
                            public void handleRollback(OperationContext context, ModelNode operation) {
                                try {
                                    reEnable(context, operation, datasourceRegistration);
                                } catch (OperationFailedException e) {
                                    // ignored
                                }
                            }
                        });
                    }
                }, OperationContext.Stage.RUNTIME);
            } else {
                context.addStep(new OperationStepHandler() {
                    @Override
                    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                        context.reloadRequired();
                        context.completeStep(OperationContext.RollbackHandler.REVERT_RELOAD_REQUIRED_ROLLBACK_HANDLER);
                    }
                }, OperationContext.Stage.RUNTIME);
            }
        }
        context.stepCompleted();
    }

    private void reEnable(final OperationContext context, final ModelNode operation, final ManagementResourceRegistration datasourceRegistration) throws OperationFailedException {
        if (context.isNormalServer()) {
            PathAddress addr = PathAddress.pathAddress(operation.get(OP_ADDR));
            Resource resource = context.getOriginalRootResource();
            for (PathElement element : addr) {
                resource = resource.getChild(element);
            }
            DataSourceEnable.addServices(context, operation, datasourceRegistration,
                    Resource.Tools.readModel(resource), isXa());
        }
    }

    public boolean isXa() {
        return xa;
    }
}
