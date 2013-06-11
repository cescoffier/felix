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

import org.apache.felix.ipojo.Factory;
import org.apache.felix.ipojo.dependency.interceptors.ServiceRankingInterceptor;
import org.apache.felix.ipojo.dependency.interceptors.ServiceTrackingInterceptor;
import org.apache.felix.ipojo.dependency.interceptors.TransformedServiceReference;
import org.apache.felix.ipojo.util.DependencyModel;
import org.apache.felix.ipojo.util.Log;
import org.apache.felix.ipojo.util.Tracker;
import org.apache.felix.ipojo.util.TrackerCustomizer;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.ServiceReference;

import java.util.*;

/**
 * This class is handling the transformations between the base service set and the selected service set.
 * It handles the matching services and the selected service set.
 * As this class is tied to the dependency model, it reuses the same locks objects.
 */
public class SelectedServicesManager implements TrackerCustomizer {

    /**
     * The dependency.
     */
    private final DependencyModel m_dependency;
    /**
     * The comparator to sort service references.
     */
    private Comparator<ServiceReference> m_comparator;
    /**
     * The LDAP filter object selecting service references
     * from the set of providers providing the required specification.
     */
    private Filter m_filter;

    /**
     * The list of all matching service references. This list is a
     * subset of tracked references. This set is computed according
     * to the filter and the {@link DependencyModel#match(ServiceReference)} method.
     */
    private final Map<ServiceReference, TransformedServiceReference> m_matchingReferences = new
            LinkedHashMap<ServiceReference, TransformedServiceReference>();

    /**
     * The list of selected service references.
     */
    private List<? extends ServiceReference> m_selectedReferences = new ArrayList<ServiceReference>();
    /**
     * The service ranking interceptor.
     */
    private ServiceRankingInterceptor m_rankingInterceptor;
    /**
     * Service interceptor tracker.
     */
    private Tracker m_rankingInterceptorTracker;
    private Tracker m_trackingInterceptorTracker;

    /**
     * The set of tracking interceptors.
     * TODO this set should be ranking according to the OSGi ranking policy.
     * The filter is always the last interceptor.
     */
    private LinkedList<ServiceTrackingInterceptor> m_trackingInterceptors = new
            LinkedList<ServiceTrackingInterceptor>();

    /**
     * Creates the service reference manager.
     *
     * @param dep        the dependency
     * @param filter     the filter
     * @param comparator the comparator
     */
    public SelectedServicesManager(DependencyModel dep, Filter filter, Comparator<ServiceReference> comparator) {
        m_dependency = dep;
        m_filter = filter;
        if (m_filter != null) {
            m_trackingInterceptors.addLast(new FilterBasedServiceTrackingInterceptor(m_filter));
        }
        if (comparator != null) {
            m_comparator = comparator;
            m_rankingInterceptor = new ComparatorBasedServiceRankingInterceptor(comparator);
        } else {
            m_rankingInterceptor = new EmptyBasedServiceRankingInterceptor();
        }
    }

    public void open() {
        m_trackingInterceptorTracker = new Tracker(m_dependency.getBundleContext(),
                ServiceTrackingInterceptor.class.getName(),
                new TrackerCustomizer() {

                    public boolean addingService(ServiceReference reference) {
                        return DependencyProperties.match(reference, m_dependency);
                    }

                    public void addedService(ServiceReference reference) {
                        ServiceTrackingInterceptor interceptor = (ServiceTrackingInterceptor) m_trackingInterceptorTracker
                                .getService(reference);

                        if (interceptor != null) {
                            addTrackingInterceptor(interceptor);
                        } else {
                            m_dependency.getComponentInstance().getFactory().getLogger().log(Log.ERROR,
                                    "Cannot retrieve the interceptor object from service reference " + reference
                                            .getProperty(Constants.SERVICE_ID) + " - " + reference.getProperty
                                            (Factory.INSTANCE_NAME_PROPERTY));
                        }
                    }

                    public void modifiedService(ServiceReference reference, Object service) {
                        // Not supported yet.
                        // TODO it would be nice to support the modification of the interceptor TARGET property.
                    }

                    public void removedService(ServiceReference reference, Object service) {
                        if (service != null && m_trackingInterceptors.contains(service)) {
                            removeTrackingInterceptor((ServiceTrackingInterceptor) service);
                        }
                    }
                });

        m_trackingInterceptorTracker.open();

        // Initialize the service interceptor tracker.
        m_rankingInterceptorTracker = new Tracker(m_dependency.getBundleContext(), ServiceRankingInterceptor.class.getName(),
                new TrackerCustomizer() {

                    public boolean addingService(ServiceReference reference) {
                        return DependencyProperties.match(reference, m_dependency);
                    }

                    public void addedService(ServiceReference reference) {
                        ServiceRankingInterceptor interceptor = (ServiceRankingInterceptor) m_rankingInterceptorTracker
                                .getService(reference);
                        if (interceptor != null) {
                            setRankingInterceptor(interceptor);
                        } else {
                            m_dependency.getComponentInstance().getFactory().getLogger().log(Log.ERROR,
                                    "Cannot retrieve the interceptor object from service reference " + reference
                                            .getProperty(Constants.SERVICE_ID) + " - " + reference.getProperty
                                            (Factory.INSTANCE_NAME_PROPERTY));
                        }
                    }

                    public void modifiedService(ServiceReference reference, Object service) {
                        // Not supported yet.
                        // TODO it would be nice to support the modification of the interceptor TARGET property.
                    }

                    public void removedService(ServiceReference reference, Object service) {
                        if (service == m_rankingInterceptor) {
                            m_rankingInterceptor.close(m_dependency);
                            // Do we have another one ?
                            ServiceReference anotherReference = m_rankingInterceptorTracker.getServiceReference();
                            if (anotherReference != null) {
                                ServiceRankingInterceptor interceptor = (ServiceRankingInterceptor) m_rankingInterceptorTracker
                                        .getService(anotherReference);
                                if (interceptor != null) setRankingInterceptor(interceptor);
                            } else if (m_comparator != null) {
                                // If we have a comparator, we restore the comparator.
                                setComparator(m_comparator);
                            } else {
                                // If we have neither comparator nor interceptor use an empty interceptor.
                                setRankingInterceptor(new EmptyBasedServiceRankingInterceptor());
                            }
                        }
                    }
                });

        m_rankingInterceptorTracker.open();
    }

    private void addTrackingInterceptor(ServiceTrackingInterceptor interceptor) {
        // A new interceptor arrives. Insert it at the beginning of the list.
        // TODO Locking.
        m_trackingInterceptors.addFirst(interceptor);
        interceptor.open(m_dependency, m_dependency.getBundleContext());
        m_dependency.onChange(fireBaseSetChanges());
    }

    private void removeTrackingInterceptor(ServiceTrackingInterceptor interceptor) {
        // TODO Locking
        m_trackingInterceptors.remove(interceptor);
        interceptor.close(m_dependency, m_dependency.getBundleContext());
        m_dependency.onChange(fireBaseSetChanges());
    }

    private ChangeSet fireBaseSetChanges() {
        if (m_dependency.getTracker()  == null  || m_dependency.getTracker().getServiceReferences() == null) {
            // Tracker closed, no problem
            return new ChangeSet(Collections.<ServiceReference>emptyList(),
                    Collections.<ServiceReference>emptyList(),
                    Collections.<ServiceReference>emptyList(),
                    null,
                    null,
                    null,
                    null);
        }
        // The set of interceptor has changed.
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            // The tracker is open, we must recheck all services.
            ServiceReference oldBest = getFirstService();
                // Recompute the matching services.
                m_matchingReferences.clear();
                for (ServiceReference reference : m_dependency.getTracker().getServiceReferencesList()) {
                    TransformedServiceReference ref = new TransformedServiceReferenceImpl(reference);
                    ref = accept(ref);
                    if (ref != null) {
                        m_matchingReferences.put(reference, ref);
                    }
                }

                // We have the new matching set.

                List<ServiceReference> beforeRanking = getSelectedServices();

                final List<ServiceReference> allServices = getMatchingServices();
                List<ServiceReference> references;
                if (allServices.isEmpty()) {
                    references = Collections.emptyList();
                } else {
                    references = m_rankingInterceptor.getServiceReferences(m_dependency, allServices);
                }

                RankingResult result = computeDifferences(beforeRanking, references);
                m_selectedReferences = result.selected;

                ServiceReference newFirst = getFirstService();
                ServiceReference modified = null;
                if (ServiceReferenceUtils.haveSameServiceId(oldBest, newFirst)  && ServiceReferenceUtils
                        .areStrictlyEquals(oldBest, newFirst)) {
                    modified = newFirst;
                }


                return new ChangeSet(getSelectedServices(), result.departures, result.arrivals, oldBest, getFirstService(),
                        null, modified);
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }
    }

    public List<ServiceReference> getMatchingServices() {
        try {
            m_dependency.acquireReadLockIfNotHeld();
            return new ArrayList<ServiceReference>(m_matchingReferences.values());
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }
    }

    public List<ServiceReference> getSelectedServices() {
        try {
            m_dependency.acquireReadLockIfNotHeld();
            return new ArrayList<ServiceReference>(m_selectedReferences);
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }
    }

    public ServiceReference getFirstService() {
        try {
            m_dependency.acquireReadLockIfNotHeld();
            if (m_selectedReferences.isEmpty()) {
                return null;
            }
            return m_selectedReferences.get(0);
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }
    }

    public boolean contains(ServiceReference ref) {
        try {
            m_dependency.acquireReadLockIfNotHeld();
            return m_selectedReferences.contains(ref);
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }
    }

    public void reset() {
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            m_rankingInterceptor.close(m_dependency);
            for (ServiceTrackingInterceptor interceptor : m_trackingInterceptors) {
                interceptor.close(m_dependency, m_dependency.getBundleContext());
            }
            m_trackingInterceptors.clear();
            m_matchingReferences.clear();
            m_selectedReferences = new ArrayList<TransformedServiceReference>();
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }

    }

    public boolean addingService(ServiceReference reference) {
        // We accept all service references except if we are frozen or broken. In these case, just ignore everything.

        // We are doing two tests, we must get the read lock
        try {
            m_dependency.acquireReadLockIfNotHeld();
            return !(m_dependency.getState() == DependencyModel.BROKEN || m_dependency.isFrozen());
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }
    }

    private <S> TransformedServiceReference<S> accept(TransformedServiceReference<S> reference) {
        TransformedServiceReference<S> accumulator = reference;
        for (ServiceTrackingInterceptor interceptor : m_trackingInterceptors) {
            TransformedServiceReference<S> accepted = interceptor.accept(m_dependency, m_dependency.getBundleContext(), reference);
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

    public void addedService(ServiceReference reference) {
        // A service was added to the tracker.

        // First, check is the tracking interceptors are accepting it.
        // The transformed reference is creates and check outside of the protected region.
        TransformedServiceReference ref = new TransformedServiceReferenceImpl(reference);

        boolean match;
        try {
            m_dependency.acquireReadLockIfNotHeld();
            ref = accept(ref);
            if (ref != null) {
                m_matchingReferences.put(reference, ref);
            }
            match = ref != null;
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }

        if (match) {
            // Callback invoked outside of locks.
            // The called method is taking the write lock anyway.
            onNewMatchingService(ref);
        }
    }

    private void onNewMatchingService(TransformedServiceReference reference) {
        ServiceReference oldFirst;
        RankingResult result;
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            // We store the currently 'first' service reference.
            oldFirst = getFirstService();

            // We apply our ranking strategy.
            result = applyRankingOnArrival(reference);
            // Set the selected services.
            m_selectedReferences = result.selected;
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }
        // Fire the event (outside from the synchronized region)
        fireUpdate(getSelectedServices(), result.departures, result.arrivals, oldFirst,
                getFirstService(), null, null);
    }

    private void onModificationOfAMatchingService(TransformedServiceReference reference, Object service) {
        ServiceReference oldFirst;
        RankingResult result;
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            // We store the currently 'first' service reference.
            oldFirst = getFirstService();

            // We apply our ranking strategy.
            result = applyRankingOnModification(reference);
            // Set the selected services.
            m_selectedReferences = result.selected;
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }
        // Fire the event (outside from the synchronized region)
        fireUpdate(getSelectedServices(), result.departures, result.arrivals, oldFirst,
                getFirstService(), service, reference);
    }

    private RankingResult applyRankingOnModification(ServiceReference reference) {
        // TODO we are holding the lock here.
        List<ServiceReference> beforeRanking = getSelectedServices();
        List<ServiceReference> references = m_rankingInterceptor.onServiceModified(m_dependency, getMatchingServices(),
                reference);
        return computeDifferences(beforeRanking, references);
    }

    private void fireUpdate(List<ServiceReference> selectedServices, List<ServiceReference> departures,
                            List<ServiceReference> arrivals, ServiceReference oldFirst,
                            ServiceReference firstService, Object service, ServiceReference modified) {
        ChangeSet set = new ChangeSet(selectedServices, departures, arrivals, oldFirst, firstService, service, modified);
        m_dependency.onChange(set);
    }

    private RankingResult applyRankingOnArrival(ServiceReference ref) {
        // TODO we are holding the lock here.
        List<ServiceReference> beforeRanking = getSelectedServices();
        List<ServiceReference> references = m_rankingInterceptor.onServiceArrival(m_dependency, getMatchingServices(),
                ref);
        // compute the differences
        return computeDifferences(beforeRanking, references);

    }

    private RankingResult applyRankingOnDeparture(ServiceReference ref) {
        // TODO we are holding the lock here.
        List<ServiceReference> beforeRanking = getSelectedServices();
        List<ServiceReference> references = m_rankingInterceptor.onServiceDeparture(m_dependency, getMatchingServices(),
                ref);
        return computeDifferences(beforeRanking, references);
    }

    private RankingResult computeDifferences(List<ServiceReference> beforeRanking, List<ServiceReference> ranked) {
        // compute the differences
        List<ServiceReference> departures = new ArrayList<ServiceReference>();
        List<ServiceReference> arrivals = new ArrayList<ServiceReference>();
        // All references that are no more in the set are considered as leaving services.
        for (ServiceReference old : beforeRanking) {
            if (!ServiceReferenceUtils.containsReferenceById(ranked, old)) {
                departures.add(old);
            }
        }
        // All references that are in `references` but not in `beforeRanking` are new services
        for (ServiceReference newRef : ranked) {
            if (!ServiceReferenceUtils.containsReferenceById(beforeRanking, newRef)) {
                arrivals.add(newRef);
            }
        }

        return new RankingResult(departures, arrivals, ranked);
    }

    public void modifiedService(ServiceReference reference, Object service) {
        // We are handling a modified event, we have three case to handle
        // 1) the service was matching and does not match anymore -> it's a departure.
        // 2) the service was not matching and matches -> it's an arrival
        // 3) the service was matching and still matches -> it's a modification.

        if (m_matchingReferences.containsKey(reference)) {
            // do we still accept the reference
            TransformedServiceReference initial = m_matchingReferences.get(reference);
            TransformedServiceReference transformed = new TransformedServiceReferenceImpl(reference);
            transformed = accept(transformed);
            if (transformed == null) {
                // case 1
                m_matchingReferences.remove(reference);
                onDepartureOfAMatchingService(initial, service);
            }  else {
                // Do we have a real change
                if (! ServiceReferenceUtils.areStrictlyEquals(initial, transformed)) {
                    // case 3
                    m_matchingReferences.put(reference, transformed);
                    onModificationOfAMatchingService(transformed, service);
                }
            }
        } else {
            // Base does not contain the service, let's try to add it.
            TransformedServiceReference transformed = new TransformedServiceReferenceImpl(reference);
            transformed = accept(transformed);
            if (transformed != null) {
                // case 2
                m_matchingReferences.put(reference, transformed);
                onNewMatchingService(transformed);
            }
        }
    }

    public void onDepartureOfAMatchingService(TransformedServiceReference reference, Object service) {
        ServiceReference oldFirst;
        RankingResult result = null;
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            // We store the currently 'first' service reference.
            oldFirst = getFirstService();
            // We apply our ranking strategy.
            result = applyRankingOnDeparture(reference);
            // Set the selected services.
            m_selectedReferences = result.selected;
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }
        // Fire the event (outside from the synchronized region)
        fireUpdate(getSelectedServices(), result.departures, result.arrivals, oldFirst,
                getFirstService(), service, null);
    }

    public void removedService(ServiceReference reference, Object service) {
        // A service is leaving
        // 1 - the service was in the matching set => real departure
        // 2 - the service was not in the matching set => nothing do do.

        // TODO Lock
        TransformedServiceReference initial = m_matchingReferences.remove(reference);
        if (initial != null) {
            // Case 1
            onDepartureOfAMatchingService(initial, service);
        }
        // else case 2.
    }

    /**
     * A new filter is set.
     * We have to recompute the set of matching services.
     *
     * @param filter  the new filter
     * @param tracker the tracker
     */
    public ChangeSet setFilter(Filter filter, Tracker tracker) {
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            m_filter = filter;
            if (tracker == null) {
                ServiceTrackingInterceptor interceptor = m_trackingInterceptors.getLast();
                if (interceptor != null  && interceptor instanceof FilterBasedServiceTrackingInterceptor) {
                    // Remove it first.
                    m_trackingInterceptors.removeLast();
                }
                if (m_filter != null) {
                    // Add the new one.
                    ServiceTrackingInterceptor newInterceptor = new FilterBasedServiceTrackingInterceptor(m_filter);
                    m_trackingInterceptors.addLast(newInterceptor);
                }

                // Tracker closed, no problem
                return new ChangeSet(Collections.<ServiceReference>emptyList(),
                        Collections.<ServiceReference>emptyList(),
                        Collections.<ServiceReference>emptyList(),
                        null,
                        null,
                        null,
                        null);
            } else {
                // The tracker is open, we must recheck all services.
                ServiceReference oldBest = getFirstService();

                // Recompute the matching services.
                m_matchingReferences.clear();
                for (ServiceReference reference : tracker.getServiceReferencesList()) {
                    TransformedServiceReference ref = new TransformedServiceReferenceImpl(reference);
                    ref = accept(ref);
                    if (ref != null) {
                        m_matchingReferences.put(reference, ref);
                    }
                }

                // We have the new matching set.

                List<ServiceReference> beforeRanking = getSelectedServices();

                final List<ServiceReference> allServices = getMatchingServices();
                List<ServiceReference> references;
                if (allServices.isEmpty()) {
                    references = Collections.emptyList();
                } else {
                    references = m_rankingInterceptor.getServiceReferences(m_dependency, allServices);
                }

                RankingResult result = computeDifferences(beforeRanking, references);
                m_selectedReferences = result.selected;
                return new ChangeSet(getSelectedServices(), result.departures, result.arrivals, oldBest, getFirstService(),
                        null, null);
            }
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }
    }

    public boolean isEmpty() {
        try {
            m_dependency.acquireReadLockIfNotHeld();
            return m_selectedReferences.isEmpty();
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }
    }

    public Comparator<ServiceReference> getComparator() {
        try {
            m_dependency.acquireReadLockIfNotHeld();
            return m_comparator;
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }
    }

    public Filter getFilter() {
        try {
            m_dependency.acquireReadLockIfNotHeld();
            return m_filter;
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }
    }

    public ChangeSet setRankingInterceptor(ServiceRankingInterceptor interceptor) {
        m_dependency.getComponentInstance().getFactory().getLogger().log(Log.INFO, "Dependency " + m_dependency.getId
                () + " is getting a new ranking interceptor : " + interceptor);
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            ServiceReference oldBest = getFirstService();
            List<ServiceReference> beforeRanking = getSelectedServices();
            m_rankingInterceptor = interceptor;
            m_rankingInterceptor.open(m_dependency);

            final List<ServiceReference> allServices = getMatchingServices();
            List<ServiceReference> references = Collections.emptyList();
            if (!allServices.isEmpty()) {
                references = m_rankingInterceptor.getServiceReferences(m_dependency, allServices);
            }
            RankingResult result = computeDifferences(beforeRanking, references);
            m_selectedReferences = result.selected;
            return new ChangeSet(getSelectedServices(), result.departures, result.arrivals, oldBest, getFirstService(),
                    null, null);
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }
    }

    public ChangeSet setComparator(Comparator<ServiceReference> cmp) {
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            m_comparator = cmp;
            return setRankingInterceptor(new ComparatorBasedServiceRankingInterceptor(cmp));
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }
    }

    public void close() {
        reset();
    }

    public void invalidateMatchingServices() {
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            m_matchingReferences.clear();
            fireBaseSetChanges();
            m_dependency.onChange(fireBaseSetChanges());
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }
    }

    public void invalidateSelectedServices() {
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            m_selectedReferences.clear();
            m_rankingInterceptor.getServiceReferences(m_dependency, getMatchingServices());
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }
    }

    private class RankingResult {
        final List<ServiceReference> departures;
        final List<ServiceReference> arrivals;
        final List<ServiceReference> selected;

        private RankingResult(List<ServiceReference> departures, List<ServiceReference> arrivals,
                              List<ServiceReference> selected) {
            this.departures = departures;
            this.arrivals = arrivals;
            this.selected = selected;
        }
    }

    public class ChangeSet {
        public final List<ServiceReference> selected;
        public final List<ServiceReference> departures;
        public final List<ServiceReference> arrivals;
        public final ServiceReference oldFirstReference;
        public final ServiceReference newFirstReference;
        public final Object service;
        public final ServiceReference modified;

        public ChangeSet(List<ServiceReference> selectedServices,
                         List<ServiceReference> departures, List<ServiceReference> arrivals,
                         ServiceReference oldFirst, ServiceReference newFirst,
                         Object service, ServiceReference modified) {
            this.selected = selectedServices;
            this.departures = departures;
            this.arrivals = arrivals;
            this.oldFirstReference = oldFirst;
            this.newFirstReference = newFirst;
            this.service = service;
            this.modified = modified;
        }
    }
}
