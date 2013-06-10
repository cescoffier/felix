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

package org.apache.felix.ipojo.dependency.interceptors;

import org.apache.felix.ipojo.util.DependencyModel;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * A service to influence the visibility of services within a service dependency.
 * Services that are already in the base service set (i.e. accepted) cannot be modified or filtered out.
 */
public interface ServiceTrackingInterceptor {

    /**
     * A mandatory property published by provider of this service.
     * The value must be a LDAP filter. This filter will be confronted to the dependency property.
     * @see org.osgi.framework.Filter
     */
    public static String TARGET_PROPERTY = "target";

    /**
     * The interceptor is plugged on a dependency.
     * @param dependency the dependency
     * @param context the context
     */
    public void open(DependencyModel dependency, BundleContext context);

    /**
     * Does the interceptor accepts the reference of not ?
     * This methods has two goals. It can filter out undesirable services by returning {@literal null}. In addition,
     * it can <em>transform</em> the service reference to add / remove service properties. In this case,
     * it must return the <strong>same</strong> instance of {@link TransformedServiceReference},
     * but with the new set of properties.
     *
     * So to filter out the service, return {@literal null}. To accept the service,
     * return the reference as it is. To transform the service update the service reference and return it.
     *
     * When several interceptors are collaborating on teh same dependency, a chain is created. The received reference
     * is the reference modified by the preceding interceptor. Notice that once an interceptor returns {@literal
     * null} the chain is interrupted and the service rejected.
     *
     * @param dependency the dependency
     * @param context the context of the dependency
     * @param ref the reference
     * @param <S> the type of service
     * @return {@literal null} to filter out the service, the, optionally updated, reference to accept it.
     */
    public <S> TransformedServiceReference<S> accept(DependencyModel dependency, BundleContext context,
                                                     TransformedServiceReference<S> ref);

    /**
     * The interceptor was unplugged from a dependency.
     * @param dependency the dependency
     * @param context the context
     */
    public void close(DependencyModel dependency, BundleContext context);

    /**
     * The service object of the given reference is required.
     *
     * This method let interceptors being notified of service object request and can modified the returned object.
     *
     * @param dependency the dependency
     * @param service the original service object
     * @param reference the service reference (potentially transformed)
     * @param <S> the type of service
     * @return the service object to return based on the given service object.
     */
    public <S> S getService(DependencyModel dependency, S service, ServiceReference<S> reference);

    void ungetService(DependencyModel dependency, boolean noMoreUsage, ServiceReference reference);
}
