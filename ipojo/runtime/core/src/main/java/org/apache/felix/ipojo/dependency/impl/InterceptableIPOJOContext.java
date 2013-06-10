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
import org.apache.felix.ipojo.dependency.interceptors.TransformedServiceReference;
import org.apache.felix.ipojo.util.DependencyModel;
import org.apache.felix.ipojo.util.Log;
import org.apache.felix.ipojo.util.Tracker;
import org.apache.felix.ipojo.util.TrackerCustomizer;
import org.osgi.framework.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/**
 * An `interceptable` implementation of the iPOJO Context. Each instance of this class are attached to a specific
 * dependency.
 * Service Tracking interceptor are used to influence the resolution of services.
 */
public class InterceptableIPOJOContext extends IPojoContext implements TrackerCustomizer {

    private final BundleContext m_context;
    private final DependencyModel m_dependency;
    private final List<UponAcceptationServiceListener> m_listeners = new ArrayList<UponAcceptationServiceListener>();
    private final Tracker m_interceptorTracker;

    public InterceptableIPOJOContext(DependencyModel dependency, BundleContext origin) {
        super(origin);
        m_context = origin;
        m_dependency = dependency;
        m_interceptorTracker = new Tracker(m_context, ServiceTrackingInterceptor.class.getName(), this);
    }

    private <S> TransformedServiceReference<S> accept(TransformedServiceReference<S> reference) {
        final Object[] services = m_interceptorTracker.getServices();

        // No interceptor.
        if (services == null) {
            return reference;
        }

        TransformedServiceReference<S> accumulator = reference;
        for (Object svc : services) {
            ServiceTrackingInterceptor interceptor = (ServiceTrackingInterceptor) svc;
            TransformedServiceReference<S> accepted = interceptor.accept(m_dependency, m_context, reference);
            if (accepted != null) {
                accumulator = accepted;
            } else {
                // refused by an interceptor
                m_dependency.getComponentInstance().getFactory().getLogger().log(Log.INFO,
                        "The service reference " + reference.getProperty(Constants.SERVICE_ID) + " was rejected by " +
                                "interceptor " + interceptor);
                return null;
            }
        }

        return accumulator;
    }

    public void open() {
        m_interceptorTracker.open();
    }

    public void close() {
        final Object[] services = m_interceptorTracker.getServices();

        if (services != null) {
            for (Object svc : services) {
                ServiceTrackingInterceptor interceptor = (ServiceTrackingInterceptor) svc;
                interceptor.close(m_dependency, m_context);
            }
        }

        m_interceptorTracker.close();
    }

    private TransformedServiceReference[] accept(TransformedServiceReference[] references) {
        if (references == null) {
            return null;
        }

        List<TransformedServiceReference> refs = new ArrayList<TransformedServiceReference>();
        for (TransformedServiceReference ref : references) {
            TransformedServiceReference accepted = accept(ref);
            if (accepted != null) {
                refs.add(accepted);
            }
        }
        if (refs.isEmpty()) {
            return null;
        } else {
            return refs.toArray(new TransformedServiceReference[refs.size()]);
        }
    }

    @Override
    public <S> S getService(ServiceReference<S> ref) {
        final Object[] services = m_interceptorTracker.getServices();

        S result;
        if (ref instanceof TransformedServiceReference) {
            result = super.getService(((TransformedServiceReference<S>) ref).getInitialReference());
        } else {
            // Should not happen.
            result = super.getService(ref);
        }

        if (services != null && result != null) {
            for (Object svc : services) {
                ServiceTrackingInterceptor interceptor = (ServiceTrackingInterceptor) svc;
                result = interceptor.getService(m_dependency, result, ref);
            }
        }
        return result;
    }

    @Override
    public boolean ungetService(ServiceReference reference) {
        final Object[] services = m_interceptorTracker.getServices();
        boolean result = super.ungetService(reference);
        if (services != null) {
            for (Object svc : services) {
                ServiceTrackingInterceptor interceptor = (ServiceTrackingInterceptor) svc;
                interceptor.ungetService(m_dependency, result, reference);
            }
        }
        return result;
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
        ServiceReference[] references = super.getAllServiceReferences(clazz, filter);
        return accept(transform(references));
    }

    private <S> TransformedServiceReference<S>[] transform(ServiceReference<S>[] references) {
        if (references == null) {
            return null;
        }
        TransformedServiceReference<S>[] transformed = new TransformedServiceReference[references.length];
        for (int i = 0; i < references.length; i++) {
            transformed[i] = transform(references[i]);
        }
        return transformed;
    }

    private <S> TransformedServiceReference<S> transform(ServiceReference<S> reference) {
        return new TransformedServiceReferenceImpl<S>(reference);
    }

    @Override
    public ServiceReference getServiceReference(String clazz) {
        ServiceReference ref = super.getServiceReference(clazz);
        if (ref != null) {
            return accept(transform(ref));
        } else {
            return null;
        }
    }

    @Override
    public <S> ServiceReference<S> getServiceReference(Class<S> sClass) {
        ServiceReference<S> ref = super.getServiceReference(sClass);
        if (ref != null) {
            return accept(transform(ref));
        } else {
            return null;
        }
    }

    @Override
    public <S> Collection<ServiceReference<S>> getServiceReferences(Class<S> sClass, String filter) throws InvalidSyntaxException {
        Collection<ServiceReference<S>> references = super.getServiceReferences(sClass, filter);
        // This method returns an empty set if no services match.
        HashSet<ServiceReference<S>> result = new HashSet<ServiceReference<S>>();
        for (ServiceReference<S> reference : references) {
            ServiceReference<S> accepted = accept(transform(reference));
            if (accepted != null) {
                result.add(accepted);
            }
        }
        return result;
    }

    @Override
    public ServiceReference[] getServiceReferences(String clazz, String filter) throws InvalidSyntaxException {
        ServiceReference[] refs = super.getServiceReferences(clazz, filter);
        return accept(transform(refs));
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
        List<ServiceReference> base = m_dependency.getBaseServiceSet();

        ServiceTrackingInterceptor interceptor = (ServiceTrackingInterceptor) m_interceptorTracker.getService(reference);
        interceptor.open(m_dependency, m_context);
        // The new interceptor may change the visibility of the services, even the service reference.
        // We must notify the dependency of the changes.

        fireServiceEvents(base);
    }

    private void fireServiceEvents(List<ServiceReference> base) {
        System.out.println(base);
        ServiceReference[] references = ServiceReferenceUtils.getServiceReferencesBySpecification(m_dependency.getSpecification()
                .getName(), m_context);

        if (references == null) {
            // All services are gone !
            for (ServiceReference ref : base) {
                TransformedServiceReference newReference = transform(ref);
                if (newReference != null) {
                    fireServiceEvent(new WrappedServiceEvent(ServiceEvent.UNREGISTERING, newReference));
                }
            }
            return;
        }

        for (ServiceReference ref : references) {
            TransformedServiceReference newReference = accept(transform(ref));
            System.out.println(newReference);
            // Is the reference already in base ?
            ServiceReference baseServiceReference = ServiceReferenceUtils.getServiceReferenceById(base, ref);
            if (baseServiceReference != null && newReference == null) {
                // The new interceptor has rejected the reference.
                // Fire a service departure event - the ref is not accepted by the new interceptor
                fireServiceEvent(new WrappedServiceEvent(ServiceEvent.UNREGISTERING, newReference));
            } else if (baseServiceReference == null && newReference != null) {
                // Can this really happen ? A reference that was filtered out before becomes visible.
                // This could happen if the interceptor having dropped the reference checks the availability of the new
                // interceptor.
                fireServiceEvent(new WrappedServiceEvent(ServiceEvent.REGISTERED, newReference));
            } else if (baseServiceReference != null
                    && !ServiceReferenceUtils.areStrictlyEquals(baseServiceReference, newReference)) {
                System.out.println("Modification of " + newReference);
                fireServiceEvent(new WrappedServiceEvent(ServiceEvent.MODIFIED, newReference));
            }
        }
    }

    public void modifiedService(ServiceReference reference, Object service) {
        // Not supported yet.
        // TODO it would be nice to support the modification of the interceptor TARGET property.
    }

    public void removedService(ServiceReference reference, Object service) {
        if (service != null) {
            ((ServiceTrackingInterceptor) service).close(m_dependency, m_context);
            System.out.println("recomputing base set after departure");
            // The base set was not yet updated.
            List<ServiceReference> base = m_dependency.getBaseServiceSet();
            fireServiceEvents(base);
        }
    }

    private void fireServiceEvent(WrappedServiceEvent event) {
        System.out.println("Firing event for " + event.getServiceReference() + " " + event.getType());
        for (UponAcceptationServiceListener listener : m_listeners) {
            listener.serviceChanged(event);
        }
    }

    private class UponAcceptationServiceListener implements ServiceListener {

        private final ServiceListener m_listener;

        private UponAcceptationServiceListener(ServiceListener listener) {
            m_listener = listener;
        }

        public void serviceChanged(final ServiceEvent event) {
            final ServiceReference accepted = accept(new TransformedServiceReferenceImpl(event.getServiceReference()));
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

        /**
         * Creates a new service event object from scratch.
         *
         * @param type     the type of event.
         * @param modified the modified service reference
         */
        public WrappedServiceEvent(int type, TransformedServiceReference<?> modified) {
            super(type, modified.getInitialReference());
            m_modified = modified;
        }

        /**
         * Creates a new service event object from scratch.
         *
         * @param type     the type of event.
         * @param modified the service reference
         */
        public WrappedServiceEvent(int type, ServiceReference<?> modified) {
            super(type, modified);
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
