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
 * A service tracking interceptor doing nothing.
 * It accepts all references.
 */
public class EmptyServiceTrackingInterceptor implements  ServiceTrackingInterceptor {
    public void open(DependencyModel dependency, BundleContext context) { }

    public <S> TransformedServiceReference<S> accept(DependencyModel dependency, BundleContext context, TransformedServiceReference<S> ref) {
        return  ref;
    }

    public void close(DependencyModel dependency, BundleContext context) { }

    public <S> S getService(DependencyModel dependency, S service, ServiceReference<S> reference) {
        return service;
    }

    public void ungetService(DependencyModel dependency, boolean noMoreUsage, ServiceReference reference) { }
}
