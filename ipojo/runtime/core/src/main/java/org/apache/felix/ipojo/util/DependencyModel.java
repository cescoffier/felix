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
package org.apache.felix.ipojo.util;

import org.apache.felix.ipojo.ComponentInstance;
import org.apache.felix.ipojo.ConfigurationException;
import org.apache.felix.ipojo.IPOJOServiceFactory;
import org.apache.felix.ipojo.dependency.impl.InterceptableIPOJOContext;
import org.apache.felix.ipojo.dependency.impl.ServiceReferenceManager;
import org.apache.felix.ipojo.metadata.Element;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import java.util.*;

/**
 * Abstract dependency model.
 * This class is the parent class of every service dependency. It manages the most
 * part of dependency management. This class creates an interface between the service
 * tracker and the concrete dependency.
 *
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public abstract class DependencyModel {

    /**
     * Dependency state : BROKEN.
     * A broken dependency cannot be fulfilled anymore. The dependency becomes
     * broken when a used service disappears in the static binding policy.
     */
    public static final int BROKEN = -1;
    /**
     * Dependency state : UNRESOLVED.
     * A dependency is unresolved if the dependency is not valid and no service
     * providers are available.
     */
    public static final int UNRESOLVED = 0;
    /**
     * Dependency state : RESOLVED.
     * A dependency is resolved if the dependency is optional or at least one
     * provider is available.
     */
    public static final int RESOLVED = 1;
    /**
     * Binding policy : Dynamic.
     * In this policy, services can appears and departs without special treatment.
     */
    public static final int DYNAMIC_BINDING_POLICY = 0;
    /**
     * Binding policy : Static.
     * Once a service is used, if this service disappears the dependency becomes
     * {@link DependencyModel#BROKEN}. The instance needs to be recreated.
     */
    public static final int STATIC_BINDING_POLICY = 1;
    /**
     * Binding policy : Dynamic-Priority.
     * In this policy, services can appears and departs. However, once a service
     * with a highest ranking (according to the used comparator) appears, this
     * new service is re-injected.
     */
    public static final int DYNAMIC_PRIORITY_BINDING_POLICY = 2;
    /**
     * The service reference manager.
     */
    protected final ServiceReferenceManager m_serviceReferenceManager;
    /**
     * The manager handling context sources.
     */
    private final ContextSourceManager m_contextSourceManager;
    /**
     * Listener object on which invoking the {@link DependencyStateListener#validate(DependencyModel)}
     * and {@link DependencyStateListener#invalidate(DependencyModel)} methods.
     */
    private final DependencyStateListener m_listener;
    /**
     * The instance requiring the service.
     */
    private final ComponentInstance m_instance;
    /**
     * Does the dependency bind several providers ?
     */
    private boolean m_aggregate;
    /**
     * Is the dependency optional ?
     */
    private boolean m_optional;
    /**
     * The required specification.
     * Cannot change once set.
     */
    private Class m_specification;
    /**
     * Bundle context used by the dependency.
     * (may be a {@link org.apache.felix.ipojo.ServiceContext}).
     */
    private BundleContext m_context;
    /**
     * The actual state of the dependency.
     * {@link DependencyModel#UNRESOLVED} at the beginning.
     */
    private int m_state;
    /**
     * The Binding policy of the dependency.
     */
    private int m_policy = DYNAMIC_BINDING_POLICY;
    /**
     * The tracker used by this dependency to track providers.
     */
    private Tracker m_tracker;
    /**
     * Map {@link ServiceReference} -> Service Object.
     * This map stores service object, and so is able to handle
     * iPOJO custom policies.
     */
    private Map<ServiceReference, Object> m_serviceObjects = new HashMap<ServiceReference, Object>();
    /**
     * The current list of bound services.
     */
    private List<ServiceReference> m_boundServices = new ArrayList<ServiceReference>();

    /**
     * Creates a DependencyModel.
     * If the dependency has no comparator and follows the
     * {@link DependencyModel#DYNAMIC_PRIORITY_BINDING_POLICY} policy
     * the OSGi Service Reference Comparator is used.
     *
     * @param specification the required specification
     * @param aggregate     is the dependency aggregate ?
     * @param optional      is the dependency optional ?
     * @param filter        the LDAP filter
     * @param comparator    the comparator object to sort references
     * @param policy        the binding policy
     * @param context       the bundle context (or service context)
     * @param listener      the dependency lifecycle listener to notify from dependency
     * @param ci            instance managing the dependency
     *                      state changes.
     */
    public DependencyModel(Class specification, boolean aggregate, boolean optional, Filter filter,
                           Comparator<ServiceReference> comparator, int policy,
                           BundleContext context, DependencyStateListener listener, ComponentInstance ci) {
        m_specification = specification;
        m_aggregate = aggregate;
        m_optional = optional;

        m_instance = ci;

        m_policy = policy;
        // If the dynamic priority policy is chosen, and we have no comparator, fix it to OSGi standard service reference comparator.
        if (m_policy == DYNAMIC_PRIORITY_BINDING_POLICY && comparator == null) {
            comparator = new ServiceReferenceRankingComparator();
        }

        if (context != null) {
            m_context = new InterceptableIPOJOContext(this, context);
            // If the context is null, it gonna be set later using the setBundleContext method.
        }

        m_serviceReferenceManager = new ServiceReferenceManager(this, filter, comparator);

        if (filter != null) {
            try {
                m_contextSourceManager = new ContextSourceManager(this);
            } catch (InvalidSyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        } else {
            m_contextSourceManager = null;
        }



        m_state = UNRESOLVED;
        m_listener = listener;
    }

    /**
     * Helper method parsing the comparator attribute and returning the
     * comparator object. If the 'comparator' attribute is not set, this method
     * returns null. If the 'comparator' attribute is set to 'osgi', this method
     * returns the normal OSGi comparator. In other case, it tries to create
     * an instance of the declared comparator class.
     *
     * @param dep     the Element describing the dependency
     * @param context the bundle context (to load the comparator class)
     * @return the comparator object, <code>null</code> if not set.
     * @throws ConfigurationException the comparator class cannot be load or the
     *                                comparator cannot be instantiated correctly.
     */
    public static Comparator getComparator(Element dep, BundleContext context) throws ConfigurationException {
        Comparator cmp = null;
        String comp = dep.getAttribute("comparator");
        if (comp != null) {
            if (comp.equalsIgnoreCase("osgi")) {
                cmp = new ServiceReferenceRankingComparator();
            } else {
                try {
                    Class cla = context.getBundle().loadClass(comp);
                    cmp = (Comparator) cla.newInstance();
                } catch (ClassNotFoundException e) {
                    throw new ConfigurationException("Cannot load a customized comparator", e);
                } catch (IllegalAccessException e) {
                    throw new ConfigurationException("Cannot create a customized comparator", e);
                } catch (InstantiationException e) {
                    throw new ConfigurationException("Cannot create a customized comparator", e);
                }
            }
        }
        return cmp;
    }

    /**
     * Loads the given specification class.
     *
     * @param specification the specification class name to load
     * @param context       the bundle context
     * @return the class object for the given specification
     * @throws ConfigurationException if the class cannot be loaded correctly.
     */
    public static Class loadSpecification(String specification, BundleContext context) throws ConfigurationException {
        Class spec;
        try {
            spec = context.getBundle().loadClass(specification);
        } catch (ClassNotFoundException e) {
            throw new ConfigurationException("A required specification cannot be loaded : " + specification, e);
        }
        return spec;
    }

    /**
     * Helper method parsing the binding policy.
     * If the 'policy' attribute is not set in the dependency, the method returns
     * the 'DYNAMIC BINDING POLICY'. Accepted policy values are : dynamic,
     * dynamic-priority and static.
     *
     * @param dep the Element describing the dependency
     * @return the policy attached to this dependency
     * @throws ConfigurationException if an unknown binding policy was described.
     */
    public static int getPolicy(Element dep) throws ConfigurationException {
        String policy = dep.getAttribute("policy");
        if (policy == null || policy.equalsIgnoreCase("dynamic")) {
            return DYNAMIC_BINDING_POLICY;
        } else if (policy.equalsIgnoreCase("dynamic-priority")) {
            return DYNAMIC_PRIORITY_BINDING_POLICY;
        } else if (policy.equalsIgnoreCase("static")) {
            return STATIC_BINDING_POLICY;
        } else {
            throw new ConfigurationException("Binding policy unknown : " + policy);
        }
    }

    /**
     * Opens the tracking.
     * This method computes the dependency state
     *
     * @see DependencyModel#computeDependencyState()
     */
    public void start() {
        m_state = UNRESOLVED;
        m_tracker = new Tracker(m_context, m_specification.getName(), m_serviceReferenceManager);
        m_tracker.open();

        if (m_contextSourceManager != null) {
            m_contextSourceManager.start();
        }

        computeDependencyState();
    }

    /**
     * Closes the tracking.
     * The dependency becomes {@link DependencyModel#UNRESOLVED}
     * at the end of this method.
     */
    public void stop() {
        if (m_tracker != null) {
            m_tracker.close();
            m_tracker = null;
        }
        m_boundServices.clear();
        m_serviceReferenceManager.close();
        ungetAllServices();
        m_state = UNRESOLVED;
        if (m_contextSourceManager != null) {
            m_contextSourceManager.stop();
        }
    }

    /**
     * Ungets all 'get' service references.
     * This also clears the service object map.
     */
    private void ungetAllServices() {
        for (Map.Entry<ServiceReference, Object> entry : m_serviceObjects.entrySet()) {
            ServiceReference ref = entry.getKey();
            Object svc = entry.getValue();
            if (m_tracker != null) {
                m_tracker.ungetService(ref);
            }
            if (svc instanceof IPOJOServiceFactory) {
                ((IPOJOServiceFactory) svc).ungetService(m_instance, svc);
            }
        }
        m_serviceObjects.clear();
    }

    /**
     * Is the reference set frozen (cannot change anymore)?
     * This method must be override by concrete dependency to support
     * the static binding policy. In fact, this method allows optimizing
     * the static dependencies to become frozen only when needed.
     * This method returns <code>false</code> by default.
     * The method must always return <code>false</code> for non-static dependencies.
     *
     * @return <code>true</code> if the reference set is frozen.
     */
    public boolean isFrozen() {
        return false;
    }

    /**
     * Unfreezes the dependency.
     * This method must be overide by concrete dependency to support
     * the static binding policy. This method is called after tracking restarting.
     */
    public void unfreeze() {
        // nothing to do
    }

    /**
     * Does the service reference match ? This method must be override by
     * concrete dependencies if they need advanced testing on service reference
     * (that cannot be expressed in the LDAP filter). By default this method
     * returns <code>true</code>.
     *
     * @param ref the tested reference.
     * @return <code>true</code> if the service reference matches.
     */
    public boolean match(ServiceReference ref) {
        return true;
    }

    /**
     * Computes the actual dependency state.
     * This methods invokes the {@link DependencyStateListener}.
     */
    private void computeDependencyState() {
        // The dependency is broken, nothing else can be done
        if (m_state == BROKEN) {
            return;
        }

        boolean mustCallValidate = false;
        boolean mustCallInvalidate = false;
        synchronized (this) {
            if (m_optional || ! m_serviceReferenceManager.isEmpty()) {
                // The dependency is valid
                if (m_state == UNRESOLVED) {
                    m_state = RESOLVED;
                    mustCallValidate = true;
                }
            } else {
                // The dependency is invalid
                if (m_state == RESOLVED) {
                    m_state = UNRESOLVED;
                    mustCallInvalidate = true;
                }
            }
        }

        // Invoke callback in a non-synchronized region
        if (mustCallInvalidate) {
            invalidate();
        } else if (mustCallValidate) {
            validate();
        }

    }

    /**
     * Gets the first bound service reference.
     *
     * @return <code>null</code> if no more provider is available,
     *         else returns the first reference from the matching set.
     */
    public ServiceReference getServiceReference() {
        synchronized (this) {
            if (m_boundServices.isEmpty()) {
                return null;
            } else {
                return m_boundServices.get(0);
            }
        }
    }

    /**
     * Gets bound service references.
     *
     * @return the sorted (if a comparator is used) array of matching service
     *         references, <code>null</code> if no references are available.
     */
    public ServiceReference[] getServiceReferences() {
        synchronized (this) {
            if (m_boundServices.isEmpty()) {
                return null;
            }
            return m_boundServices.toArray(new ServiceReference[m_boundServices.size()]);
        }
    }

    /**
     * Gets the list of currently used service references.
     * If no service references, returns <code>null</code>
     *
     * @return the list of used reference (according to the service tracker).
     */
    public List<ServiceReference> getUsedServiceReferences() {
        synchronized (this) {
            // The list must confront actual matching services with already get services from the tracker.

            int size = m_boundServices.size();
            List<ServiceReference> usedByTracker = null;
            if (m_tracker != null) {
                usedByTracker = m_tracker.getUsedServiceReferences();
            }
            if (size == 0 || usedByTracker == null) {
                return null;
            }

            List<ServiceReference> list = new ArrayList<ServiceReference>(1);
            for (ServiceReference ref : m_boundServices) {
                if (usedByTracker.contains(ref)) {
                    list.add(ref); // Add the service in the list.
                    if (!isAggregate()) { // IF we are not multiple, return the list when the first element is found.
                        return list;
                    }
                }
            }

            return list;
        }
    }

    /**
     * @return the component instance on which this dependency is plugged.
     */
    public ComponentInstance getComponentInstance() {
        return m_instance;
    }

    /**
     * Gets the number of actual matching references.
     *
     * @return the number of matching references
     */
    public int getSize() {
        return m_boundServices.size();
    }

    /**
     * Concrete dependency callback.
     * This method is called when a new service needs to be
     * re-injected in the underlying concrete dependency.
     *
     * @param ref the service reference to inject.
     */
    public abstract void onServiceArrival(ServiceReference ref);

    /**
     * Concrete dependency callback.
     * This method is called when a used service (already injected) is leaving.
     *
     * @param ref the leaving service reference.
     */
    public abstract void onServiceDeparture(ServiceReference ref);

    /**
     * Concrete dependency callback.
     * This method is called when a used service (already injected) is modified.
     *
     * @param ref the modified service reference.
     */
    public abstract void onServiceModification(ServiceReference ref);

    /**
     * Concrete dependency callback.
     * This method is called when the dependency is reconfigured and when this
     * reconfiguration implies changes on the matching service set ( and by the
     * way on the injected service).
     *
     * @param departs  the service leaving the matching set.
     * @param arrivals the service arriving in the matching set.
     */
    public abstract void onDependencyReconfiguration(ServiceReference[] departs, ServiceReference[] arrivals);

    /**
     * Calls the listener callback to notify the new state of the current
     * dependency.
     */
    private void invalidate() {
        m_listener.invalidate(this);
    }

    /**
     * Calls the listener callback to notify the new state of the current
     * dependency.
     */
    private void validate() {
        m_listener.validate(this);
    }

    /**
     * Gets the actual state of the dependency.
     *
     * @return the state of the dependency.
     */
    public int getState() {
        return m_state;
    }

    /**
     * Gets the tracked specification.
     *
     * @return the Class object tracked by the dependency.
     */
    public Class getSpecification() {
        return m_specification;
    }

    /**
     * Sets the required specification of this service dependency.
     * This operation is not supported if the dependency tracking has already begun.
     *
     * @param specification the required specification.
     */
    public void setSpecification(Class specification) {
        if (m_tracker == null) {
            m_specification = specification;
        } else {
            throw new UnsupportedOperationException("Dynamic specification change is not yet supported");
        }
    }

    /**
     * Returns the dependency filter (String form).
     *
     * @return the String form of the LDAP filter used by this dependency,
     *         <code>null</code> if not set.
     */
    public String getFilter() {
        Filter filter = m_serviceReferenceManager.getFilter();
        if (filter == null) {
            return null;
        } else {
            return filter.toString();
        }
    }

    /**
     * Sets the filter of the dependency. This method recomputes the
     * matching set and call the onDependencyReconfiguration callback.
     *
     * @param filter the new LDAP filter.
     */
    public void setFilter(Filter filter) {
        ServiceReferenceManager.ChangeSet changeSet;
        synchronized (this) {
            changeSet = m_serviceReferenceManager.setFilter(filter, m_tracker);
        }

        applyReconfiguration(changeSet);
    }

    public void applyReconfiguration(ServiceReferenceManager.ChangeSet changeSet) {
        List<ServiceReference> arr = new ArrayList<ServiceReference>();
        List<ServiceReference> dep = new ArrayList<ServiceReference>();

        synchronized (this) {
            if (m_tracker == null) {
                // Nothing else to do.
                return;
            } else {
                // Update bindings
                m_boundServices.clear();
                if (m_aggregate) {
                    m_boundServices = new ArrayList<ServiceReference>(changeSet.selected);
                    arr = changeSet.arrivals;
                    dep = changeSet.departures;
                } else {
                    ServiceReference used = null;
                    if (!m_boundServices.isEmpty()) {
                        used = m_boundServices.get(0);
                    }

                    if (!changeSet.selected.isEmpty()) {
                        final ServiceReference best = changeSet.newFirstReference;
                        // We didn't a provider
                        if (used == null) {
                            // We are not bound with anyone yet, so take the first of the selected set
                            m_boundServices.add(best);
                            arr.add(best);
                        } else {
                            // A provider was already bound, did we changed ?
                            if (changeSet.selected.contains(used)) {
                                // We are still valid - but in dynamic priority, we may have to change
                                if (getBindingPolicy() == DYNAMIC_PRIORITY_BINDING_POLICY && used != best) {
                                    m_boundServices.add(best);
                                    dep.add(used);
                                    arr.add(best);
                                } else {
                                    // We restore the old binding.
                                    m_boundServices.add(used);
                                }
                            } else {
                                // The used service has left.
                                m_boundServices.add(best);
                                dep.add(used);
                                arr.add(best);
                            }
                        }
                    } else {
                        // We don't have any service anymore
                        if (used != null) {
                            arr.add(used);
                        }
                    }
                }
            }
        }

        onDependencyReconfiguration(
                dep.toArray(new ServiceReference[dep.size()]),
                arr.toArray(new ServiceReference[arr.size()]));
        // Now, compute the new dependency state.
        computeDependencyState();
    }

    public synchronized boolean isAggregate() {
        return m_aggregate;
    }

    /**
     * Sets the aggregate attribute of the current dependency.
     * If the tracking is opened, it will call arrival and departure callbacks.
     *
     * @param isAggregate the new aggregate attribute value.
     */
    public synchronized void setAggregate(boolean isAggregate) {
        if (m_tracker == null) { // Not started ...
            m_aggregate = isAggregate;
        } else {
            // We become aggregate.
            if (!m_aggregate && isAggregate) {
                m_aggregate = true;
                // Call the callback on all non already injected service.
                if (m_state == RESOLVED) {

                    for (ServiceReference ref : m_serviceReferenceManager.getSelectedServices()) {
                        if (!m_boundServices.contains(ref)) {
                            m_boundServices.add(ref);
                            onServiceArrival(ref);
                        }
                    }
                }
            } else if (m_aggregate && !isAggregate) {
                m_aggregate = false;
                // We become non-aggregate.
                if (m_state == RESOLVED) {
                    List<ServiceReference> list = new ArrayList<ServiceReference>(m_boundServices);
                    for (int i = 1; i < list.size(); i++) { // The loop begin at 1, as the 0 stays injected.
                        m_boundServices.remove(list.get(i));
                        onServiceDeparture(list.get(i));
                    }
                }
            }
            // Else, do nothing.
        }
    }

    /**
     * Sets the optionality attribute of the current dependency.
     *
     * @param isOptional the new optional attribute value.
     */
    public void setOptionality(boolean isOptional) {
        if (m_tracker == null) { // Not started ...
            m_optional = isOptional;
        } else {
            computeDependencyState();
        }
    }

    public boolean isOptional() {
        return m_optional;
    }

    /**
     * Gets the used binding policy.
     *
     * @return the current binding policy.
     */
    public int getBindingPolicy() {
        return m_policy;
    }

    /**
     * Sets the binding policy.
     * Not yet supported.
     */
    public void setBindingPolicy() {
        throw new UnsupportedOperationException("Binding Policy change is not yet supported");
    }

    /**
     * Gets the used comparator name.
     * <code>Null</code> if no comparator (i.e. the OSGi one is used).
     *
     * @return the comparator class name or <code>null</code> if the dependency doesn't use a comparator.
     */
    public synchronized String getComparator() {
        final Comparator<ServiceReference> comparator = m_serviceReferenceManager.getComparator();
        if (comparator != null) {
            return comparator.getClass().getName();
        } else {
            return null;
        }
    }

    public void setComparator(Comparator<ServiceReference> cmp) {
        ServiceReferenceManager.ChangeSet changeSet;
        synchronized (this) {
            changeSet = m_serviceReferenceManager.setComparator(cmp);
        }

        applyReconfiguration(changeSet);
    }

    /**
     * Sets the bundle context used by this dependency.
     * This operation is not supported if the tracker is already opened.
     *
     * @param context the bundle context or service context to use
     */
    public void setBundleContext(BundleContext context) {
        if (m_tracker == null) { // Not started ...
            m_context = new InterceptableIPOJOContext(this, context);
        } else {
            throw new UnsupportedOperationException("Dynamic bundle (i.e. service) context change is not supported");
        }
    }

    /**
     * Gets a service object for the given reference.
     * The service object is stored to handle custom policies.
     *
     * @param ref the wanted service reference
     * @return the service object attached to the given reference
     */
    public Object getService(ServiceReference ref) {
        return getService(ref, true);
    }

    /**
     * Gets a service object for the given reference.
     *
     * @param ref   the wanted service reference
     * @param store enables / disables the storing of the reference.
     * @return the service object attached to the given reference
     */
    public Object getService(ServiceReference ref, boolean store) {
        Object svc = m_tracker.getService(ref);

        if (svc instanceof IPOJOServiceFactory) {
            Object obj = ((IPOJOServiceFactory) svc).getService(m_instance);
            if (store) {
                m_serviceObjects.put(ref, svc); // We store the factory !
            }
            return obj;
        } else {
            if (store) {
                m_serviceObjects.put(ref, svc);
            }
            return svc;
        }
    }

    /**
     * Ungets a used service reference.
     *
     * @param ref the reference to unget.
     */
    public void ungetService(ServiceReference ref) {
        m_tracker.ungetService(ref);
        Object obj = m_serviceObjects.remove(ref);  // Remove the service object
        if (obj != null && obj instanceof IPOJOServiceFactory) {
            ((IPOJOServiceFactory) obj).ungetService(m_instance, obj);
        }
    }

    public ContextSourceManager getContextSourceManager() {
        return m_contextSourceManager;
    }

    /**
     * Gets the dependency id.
     *
     * @return the dependency id. Specification name by default.
     */
    public String getId() {
        return getSpecification().getName();
    }

    public void onChange(ServiceReferenceManager.ChangeSet set) {
        // The selected service have changed.
        //TODO Synchro

        // First handle the static case with a frozen state
        if (isFrozen() && getState() != BROKEN) {
            for (ServiceReference ref : set.departures) {
                // Check if any of the service that have left was in used.
                if (m_boundServices.contains(ref)) {
                    // Static dependency broken.

                    // Reinitialize the dependency tracking
                    ComponentInstance instance;
                    synchronized (this) {
                        instance = m_instance;
                        // Static dependency broken.
                        m_state = BROKEN;
                    }
                    invalidate();  // This will invalidate the instance.
                    instance.stop(); // Stop the instance
                    unfreeze();
                    instance.start();
                    return;
                }
            }
        }

        // Manage departures
        // We unbind all bound services that are leaving.
        for (ServiceReference ref : set.departures) {
            if (m_boundServices.contains(ref)) {
                // We were using the reference
                m_boundServices.remove(ref);
                onServiceDeparture(ref);
            }
        }

        // Manage arrivals
        // For aggregate dependencies, call onServiceArrival for all services not-yet-bound and in the order of the
        // selection.
        if (isAggregate()) {
            // If the dependency is not already in used,
            // the bindings must be sorted as in set.selected
            if (m_serviceObjects.isEmpty()  || DYNAMIC_PRIORITY_BINDING_POLICY == getBindingPolicy()) {
                m_boundServices.clear();
                m_boundServices.addAll(set.selected);
            }

            // Now we notify from the arrival.
            // If we didn't add the reference yet, we add it.
            for (ServiceReference ref : set.arrivals) {
                // We bind all not-already bound services, so it's an arrival
                if (!m_boundServices.contains(ref)) {
                    m_boundServices.add(ref);
                }
                onServiceArrival(ref);
            }
        } else {
            if (!set.selected.isEmpty()) {
                final ServiceReference best = set.selected.get(0);
                // We have a provider
                if (m_boundServices.isEmpty()) {
                    // We are not bound with anyone yet, so take the first of the selected set
                    m_boundServices.add(best);
                    onServiceArrival(best);
                } else {
                    final ServiceReference current = m_boundServices.get(0);
                    // We are already bound, to the rebinding decision depends on the binding strategy
                    if (getBindingPolicy() == DYNAMIC_PRIORITY_BINDING_POLICY) {
                        // Rebinding in the DP binding policy if the bound one if not the new best one.
                        if (current != best) {
                            m_boundServices.remove(current);
                            m_boundServices.add(best);
                            onServiceDeparture(current);
                            onServiceArrival(best);
                        }
                    } else {
                        // In static and dynamic binding policy, if the service is not yet used and the new best is not
                        // the currently selected one, we should switch.
                        boolean isUsed = m_serviceObjects.containsKey(current);
                        if (! isUsed  && current != best) {
                            m_boundServices.remove(current);
                            m_boundServices.add(best);
                            onServiceDeparture(current);
                            onServiceArrival(best);
                        }
                    }
                }
            }
        }

        // Did our state changed ?
        computeDependencyState();

        // Do we have a modified service ?
        if (set.modified != null && m_boundServices.contains(set.modified)) {
            onServiceModification(set.modified);
        }

    }

    public ServiceReferenceManager getServiceReferenceManager() {
        return m_serviceReferenceManager;
    }
}
