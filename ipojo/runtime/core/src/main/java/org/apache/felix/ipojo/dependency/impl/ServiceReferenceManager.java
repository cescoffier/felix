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

import org.apache.felix.ipojo.context.ServiceReferenceImpl;
import org.apache.felix.ipojo.dependency.interceptors.ServiceRankingInterceptor;
import org.apache.felix.ipojo.util.DependencyModel;
import org.apache.felix.ipojo.util.Tracker;
import org.apache.felix.ipojo.util.TrackerCustomizer;
import org.osgi.framework.Filter;
import org.osgi.framework.ServiceReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A class managing the tracking and the choice of services.
 * As this class is tied to the dependency model, it reuses the same locks objects.
 */
public class ServiceReferenceManager implements TrackerCustomizer {

    /**
     * The dependency.
     */
    private final DependencyModel m_dependency;
    /**
     * The list of all matching service references. This list is a
     * subset of tracked references. This set is computed according
     * to the filter and the {@link DependencyModel#match(ServiceReference)} method.
     */
    private final List<ServiceReference> m_matchingReferences = new ArrayList<ServiceReference>();
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
     * The list of selected service references.
     */
    private List<ServiceReference> m_selectedReferences = new ArrayList<ServiceReference>();
    /**
     * The service ranking interceptor.
     */
    private ServiceRankingInterceptor m_interceptor;


    /**
     * Creates the service reference manager.
     *
     * @param dep        the dependency
     * @param filter     the filter
     * @param comparator the comparator
     */
    public ServiceReferenceManager(DependencyModel dep, Filter filter, Comparator<ServiceReference> comparator) {
        m_dependency = dep;
        m_filter = filter;
        if (comparator != null) {
            m_comparator = comparator;
            m_interceptor = new ComparatorBasedServiceRankingInterceptor(comparator);
        } else {
            m_interceptor = new EmptyBasedServiceRankingInterceptor();
        }
    }

    /**
     * Checks if the given service reference match the current filter.
     * This method aims to avoid calling {@link Filter#match(ServiceReference)}
     * method when manipulating a composite reference. In fact, this method thrown
     * a {@link ClassCastException} on Equinox.
     *
     * @param ref the service reference to check.
     * @return <code>true</code> if the service reference matches.
     */
    public static boolean match(Filter filter, ServiceReference ref) {
        boolean match = true;
        if (filter != null) {
            if (ref instanceof ServiceReferenceImpl) {
                // Can't use the match(ref) as it throw a class cast exception on Equinox.
                //noinspection unchecked
                match = filter.match(((ServiceReferenceImpl) ref).getProperties());
            } else { // Non composite reference.
                match = filter.match(ref);
            }
        }
        return match;
    }

    public List<ServiceReference> getAllServices() {
        try {
            m_dependency.acquireReadLockIfNotHeld();
            return new ArrayList<ServiceReference>(m_matchingReferences);
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

    public boolean matchingContains(ServiceReference ref) {
        try {
            m_dependency.acquireReadLockIfNotHeld();
            return m_matchingReferences.contains(ref);
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }

    }

    public void reset() {
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            m_matchingReferences.clear();
            m_selectedReferences.clear();
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }

    }

    public boolean addingService(ServiceReference reference) {
        // We are doing two tests, we must get the read lock
        try {
            m_dependency.acquireReadLockIfNotHeld();
            return !(m_dependency.getState() == DependencyModel.BROKEN || m_dependency.isFrozen());
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }
    }

    public void addedService(ServiceReference reference) {
        boolean match;
        try {
            m_dependency.acquireReadLockIfNotHeld();
            match = match(m_filter, reference) && m_dependency.match(reference) && !m_matchingReferences.contains(reference);
        } finally {
            m_dependency.releaseReadLockIfHeld();
        }
        if (match) {
            // Callback invoked outside of locks.
            // The called method is taking the write lock anyway.
            onNewMatchingService(reference);
        }
    }

    private void onNewMatchingService(ServiceReference reference) {
        ServiceReference oldFirst;
        RankingResult result;
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            // We store the currently 'first' service reference.
            oldFirst = getFirstService();

            // We add the reference to the matching list.
            m_matchingReferences.add(reference);

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

    private void onModificationOfAMatchingService(ServiceReference reference, Object service) {
        ServiceReference oldFirst;
        RankingResult result;
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            // We store the currently 'first' service reference.
            oldFirst = getFirstService();

            // We add the reference to the matching list if not there yet.
            if (!m_matchingReferences.contains(reference)) {
                m_matchingReferences.add(reference);
            }

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
        List<ServiceReference> references = m_interceptor.onServiceModified(m_dependency, getAllServices(),
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
        List<ServiceReference> references = m_interceptor.onServiceArrival(m_dependency, getAllServices(),
                ref);
        // compute the differences
        return computeDifferences(beforeRanking, references);
    }

    private RankingResult applyRankingOnDeparture(ServiceReference ref) {
        // TODO we are holding the lock here.
        List<ServiceReference> beforeRanking = getSelectedServices();
        List<ServiceReference> references = m_interceptor.onServiceDeparture(m_dependency, getAllServices(),
                ref);
        return computeDifferences(beforeRanking, references);

    }

    private RankingResult computeDifferences(List<ServiceReference> beforeRanking, List<ServiceReference> ranked) {
        // compute the differences
        List<ServiceReference> departures = new ArrayList<ServiceReference>();
        List<ServiceReference> arrivals = new ArrayList<ServiceReference>();
        // All references that are no more in the set are considered as leaving services.
        for (ServiceReference old : beforeRanking) {
            if (!ranked.contains(old)) {
                departures.add(old);
            }
        }
        // All references that are in `references` but not in `beforeRanking` are new services
        for (ServiceReference newRef : ranked) {
            if (!beforeRanking.contains(newRef)) {
                arrivals.add(newRef);
            }
        }

        return new RankingResult(departures, arrivals, ranked);
    }

    public void modifiedService(ServiceReference reference, Object service) {
        // We are handling a modified event, we have three case to handle
        // 1) the service was matching and does not march anymore -> it's a departure.
        // 2) the service was not matching and matches -> it's an arrival
        // 3) the service was matching and still matches -> it's a modification.

        if (matchingContains(reference)) {
            // Either case 1 or 3
            if (match(m_filter, reference) && m_dependency.match(reference)) {
                // Case 3
                // We need to re-apply ranking.
                onModificationOfAMatchingService(reference, service);
            } else {
                // Case 1
                removedService(reference, service);
            }
        } else {
            if (match(m_filter, reference) && m_dependency.match(reference)) {
                // Case 2
                onNewMatchingService(reference);
            }
        }
    }

    public void removedService(ServiceReference reference, Object service) {
        ServiceReference oldFirst;
        RankingResult result = null;
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            // We store the currently 'first' service reference.
            oldFirst = getFirstService();
            if (m_matchingReferences.remove(reference)) {
                // We apply our ranking strategy.
                result = applyRankingOnDeparture(reference);
                // Set the selected services.
                m_selectedReferences = result.selected;
            }
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }
        // Fire the event (outside from the synchronized region)
        if (result != null) {
            fireUpdate(getSelectedServices(), result.departures, result.arrivals, oldFirst,
                    getFirstService(), service, null);
        }
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
                    if (match(m_filter, reference) && m_dependency.match(reference)) {
                        // Matching service
                        m_matchingReferences.add(reference);
                    }
                }

                // We have the new matching set.

                List<ServiceReference> beforeRanking = getSelectedServices();
                List<ServiceReference> references = m_interceptor.getServiceReferences(m_dependency, getAllServices());
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

    public ChangeSet setComparator(Comparator<ServiceReference> cmp) {
        try {
            m_dependency.acquireWriteLockIfNotHeld();
            m_comparator = cmp;
            ServiceReference oldBest = getFirstService();
            List<ServiceReference> beforeRanking = getSelectedServices();
            m_interceptor = new ComparatorBasedServiceRankingInterceptor(cmp);
            List<ServiceReference> references = m_interceptor.getServiceReferences(m_dependency, getAllServices());
            RankingResult result = computeDifferences(beforeRanking, references);
            m_selectedReferences = result.selected;
            return new ChangeSet(getSelectedServices(), result.departures, result.arrivals, oldBest, getFirstService(),
                    null, null);
        } finally {
            m_dependency.releaseWriteLockIfHeld();
        }
    }

    public void close() {
        m_interceptor.close(m_dependency);
        reset();
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
