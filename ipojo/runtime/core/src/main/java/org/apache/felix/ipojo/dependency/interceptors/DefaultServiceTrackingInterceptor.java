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

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of the default service tracking interceptor.
 * It accepts all references and keeps the dependencies in the `dependencies` list. This list is guarded by the
 * monitor lock.
 */
public class DefaultServiceTrackingInterceptor implements  ServiceTrackingInterceptor {

    protected List<DependencyModel> dependencies = new ArrayList<DependencyModel>();

    public void open(DependencyModel dependency) {
        synchronized (this) {
            dependencies.add(dependency);
        }
    }

    public <S> TransformedServiceReference<S> accept(DependencyModel dependency, BundleContext context, TransformedServiceReference<S> ref) {
        return  ref;
    }

    public void close(DependencyModel dependency) {
        synchronized (this) {
            dependencies.remove(dependency);
        }
    }

    public <S> S getService(DependencyModel dependency, S service, ServiceReference<S> reference) {
        return service;
    }

    public void ungetService(DependencyModel dependency, boolean noMoreUsage, ServiceReference reference) { }

    public void notifyDependencies() {
        List<DependencyModel> list = new ArrayList<DependencyModel>();
        synchronized (this) {
            list.addAll(dependencies);
        }

        for (DependencyModel dep : list) {
            dep.invalidateMatchingServices();
        }
    }
}
