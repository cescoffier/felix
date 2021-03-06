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
package org.apache.felix.dm.runtime;

import java.util.HashMap;
import java.util.Map;

import org.apache.felix.dm.Component;
import org.apache.felix.dm.DependencyActivatorBase;
import org.apache.felix.dm.DependencyManager;
import org.apache.felix.dm.ServiceDependency;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.log.LogService;
import org.osgi.service.packageadmin.PackageAdmin;

/*
 * This is the Activator for our DependencyManager Component Runtime.
 * Here, we'll track started/stopped bundles which have some DependencyManager
 * descriptors (META-INF/dependencymanager/*.dm). Such descriptors are generated 
 * by the Bnd plugin which parses DependencyManager annotations at compile time.
 * 
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public class Activator extends DependencyActivatorBase
{
    /**
     * Name of bundle context property telling if log service is required or not.
     * (default = false)
     */
    final static String CONF_LOG = "dm.runtime.log";
    
    /**
     * Name of bundle context property telling if PackageAdmin service is required or not.
     * (default = false)
     */
    private final static String CONF_PACKAGE_ADMIN = "dm.runtime.packageAdmin";
    
    /**
     * Name of bundle context property telling if Components must be auto configured
     * with BundleContext/ServiceRegistration etc .. (default = false) 
     */
    final static String CONF_ENABLE_AUTOCONFIG = "dm.runtime.autoconfig";
    
    /**
     * Initialize our DependencyManager Runtime service.
     * 
     * We depend on the OSGi LogService, and we track all started bundles which do have a 
     * "DependencyManager-Component" Manifest header.
     * If the "dm.runtime.log=true" or "dm.runtime.packageAdmin=true" parameter is configured in the Felix config.properties
     * then we'll use a required/temporal service dependency over the respective service.
     * These temporal dependencies avoid us to be restarted if the respective service is temporarily
     * unavailable (that is: when the service is updating).
     * if the "dm.runtime.log" or "dm.runtime.packageAdmin" is not configured or it it is set to false, then we'll use
     * an optional dependency over the respective service, in order to use a NullObject in case
     * the service is not available.  
     */
    @Override
    public void init(BundleContext context, DependencyManager dm) throws Exception
    {
        Component component = createComponent()
            .setImplementation( DependencyManagerRuntime.class )
            .setComposition( "getComposition" )
            .add(createBundleDependency()
                .setRequired( false )
                .setAutoConfig( false )
                .setStateMask( Bundle.ACTIVE )
                .setFilter( "(DependencyManager-Component=*)" )
                .setCallbacks( "bundleStarted", "bundleStopped" ) );
                
        Map<String, Class<?>> services = new HashMap<String, Class<?>>( 2 );
        services.put( CONF_LOG, LogService.class );
        services.put( CONF_PACKAGE_ADMIN, PackageAdmin.class );
        for (Map.Entry<String, Class<?>> entry : services.entrySet())
        {
            String serviceProperty = entry.getKey();
            Class<?> service = entry.getValue();
            boolean serviceActive = "true".equalsIgnoreCase(context.getProperty(serviceProperty));
            ServiceDependency serviceDep =
                    serviceActive ? createTemporalServiceDependency()
                                 : createServiceDependency().setRequired(false);
            serviceDep.setService(service).setAutoConfig(true);
            component.add(serviceDep);
        }
        dm.add( component );
    }

    /**
     * Our bundle is stopping: shutdown our Dependency Manager Runtime service.
     */
    @Override
    public void destroy(BundleContext context, DependencyManager dm) throws Exception
    {
    }    
}
