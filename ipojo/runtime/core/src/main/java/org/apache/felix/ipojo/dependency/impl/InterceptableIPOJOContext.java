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

package org.apache.felix.ipojo.dependency.impl;

import org.apache.felix.ipojo.IPojoContext;
import org.apache.felix.ipojo.dependency.interceptors.ServiceTrackingInterceptor;
import org.apache.felix.ipojo.util.DependencyModel;
import org.apache.felix.ipojo.util.Tracker;
import org.apache.felix.ipojo.util.TrackerCustomizer;
import org.osgi.framework.*;

import java.util.*;

import static org.apache.felix.ipojo.dependency.impl.DependencyProperties.getDependencyProperties;

/**
 * An `interceptable` implementation of the iPOJO Context. Each instance of this class are attached to a specific
 * dependency.
 * Service Tracking interceptor are used to influence the resolution of services.
 */
public class InterceptableIPOJOContext extends IPojoContext implements TrackerCustomizer {

    //TODO Add getService interception.

    private final BundleContext m_context;
    private final DependencyModel m_dependency;
    private final List<UponAcceptationServiceListener> m_listeners = new ArrayList<UponAcceptationServiceListener>();
    private final Tracker m_tracker;

    public InterceptableIPOJOContext(DependencyModel dependency, BundleContext origin) {
        super(origin);
        m_context = origin;
        m_dependency = dependency;

        m_tracker = new Tracker(m_context, ServiceTrackingInterceptor.class.getName(), this);
        m_tracker.open();
    }

    private <S> ServiceReference<S> accept(ServiceReference<S> reference) {
        final Object[] services = m_tracker.getServices();

        // No interceptor.
        if (services == null) {
            return reference;
        }

        ServiceReference<S> accumulator = reference;
        for (Object svc : services) {
            ServiceTrackingInterceptor interceptor = (ServiceTrackingInterceptor) svc;
            ServiceReference<S> accepted = interceptor.accept(m_dependency, m_context, reference);
            if (accepted != null) {
                accumulator = accepted;
            } else {
                // refused by an interceptor
                return null;
            }
        }

        return accumulator;
    }

    private ServiceReference[] accept(ServiceReference[] references) {
        if (references == null) {
            return null;
        }

        List<ServiceReference> refs = new ArrayList<ServiceReference>();
        for (ServiceReference ref : references) {
            ServiceReference accepted = accept(ref);
            if (accepted != null) {
                refs.add(accepted);
            }
        }
        return refs.toArray(new ServiceReference[refs.size()]);
    }

    @Override
    public void addServiceListener(final ServiceListener listener, final String filter) throws InvalidSyntaxException {
        UponAcceptationServiceListener meta = new UponAcceptationServiceListener(listener);
        synchronized (this) {
            m_listeners.add(meta);
        }
        m_context.addServiceListener(listener, filter);
    }

    @Override
    public void addServiceListener(ServiceListener listener) {
        try {
            this.addServiceListener(listener, null);
        } catch (InvalidSyntaxException e) {
            // Cannot happen.
        }
    }

    @Override
    public ServiceReference[] getAllServiceReferences(String clazz, String filter) throws InvalidSyntaxException {
        ServiceReference[] references = m_context.getAllServiceReferences(clazz, filter);
        return accept(references);
    }

    @Override
    public ServiceReference getServiceReference(String clazz) {
        ServiceReference ref = m_context.getServiceReference(clazz);
        if (ref != null) {
            return accept(ref);
        } else {
            return null;
        }
    }

    @Override
    public <S> ServiceReference<S> getServiceReference(Class<S> sClass) {
        ServiceReference<S> ref = m_context.getServiceReference(sClass);
        if (ref != null) {
            return accept(ref);
        } else {
            return null;
        }
    }

    @Override
    public <S> Collection<ServiceReference<S>> getServiceReferences(Class<S> sClass, String filter) throws InvalidSyntaxException {
        Collection<ServiceReference<S>> references = m_context.getServiceReferences(sClass, filter);
        // This method returns an empty set if no services match.
        HashSet<ServiceReference<S>> result = new HashSet<ServiceReference<S>>();
        for (ServiceReference<S> reference : references) {
            ServiceReference<S> accepted = accept(reference);
            if (accepted != null) {
                result.add(accepted);
            }
        }
        return result;
    }

    @Override
    public ServiceReference[] getServiceReferences(String clazz, String filter) throws InvalidSyntaxException {
        return super.getServiceReferences(clazz, filter);    //To change body of overridden methods use File | Settings | File Templates.
    }

    @Override
    public void removeServiceListener(ServiceListener listener) {
        synchronized (this) {
            List<ServiceListener> toRemove = new ArrayList<ServiceListener>();
            for (UponAcceptationServiceListener upon : m_listeners) {
                if (upon.m_listener.equals(listener)) {
                    toRemove.add(upon);
                }
            }
            m_listeners.removeAll(toRemove);
        }
    }

    public boolean addingService(ServiceReference reference) {
        return DependencyProperties.match(reference, m_dependency);
    }

    public void addedService(ServiceReference reference) {
        ServiceTrackingInterceptor interceptor = (ServiceTrackingInterceptor) m_tracker.getService(reference);
        interceptor.open(m_dependency, m_context);
    }

    public void modifiedService(ServiceReference reference, Object service) {
        // Not supported yet.
        // TODO it would be nice to support the modification of the interceptor TARGET property.
    }

    public void removedService(ServiceReference reference, Object service) {
        if (service != null) {
            ((ServiceTrackingInterceptor) service).close(m_dependency, m_context);
        }
    }

    private class UponAcceptationServiceListener implements ServiceListener {

        private final ServiceListener m_listener;

        private UponAcceptationServiceListener(ServiceListener listener) {
            m_listener = listener;
        }

        public void serviceChanged(final ServiceEvent event) {
            final ServiceReference accepted = accept(event.getServiceReference());
            if (accepted != null) {
                m_listener.serviceChanged(new WrappedServiceEvent(event, accepted));
            }
        }
    }

    private class WrappedServiceEvent extends ServiceEvent {

        private final ServiceReference<?> m_modified;

        /**
         * Creates a new service event object.
         *
         * @param event    The wrapped event
         * @param modified The modified service reference
         */
        public WrappedServiceEvent(ServiceEvent event, ServiceReference<?> modified) {
            super(event.getType(), event.getServiceReference());
            m_modified = modified;
        }

        @Override
        public ServiceReference<?> getServiceReference() {
            return m_modified;
        }

        public ServiceReference<?> getOriginalServiceReference() {
            return super.getServiceReference();
        }
    }
}
